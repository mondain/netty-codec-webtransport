## 競合状態の分析 - WebTransport コーデック

### 日付: 2025-10-21

### 概要

WebTransportセッション初期化における重大な競合状態を特定しました。この競合状態により、サーバーからクライアントへのメッセージが失われ、断続的なテスト失敗が発生していました。

---

## 競合状態

### 場所

`src/main/java/jp/hisano/netty/webtransport/WebTransportStreamCodec.java:34-38`

### 問題

`WebTransportSession`が書き込みfutureリスナー内で**非同期に**作成されています:

```java
ctx.writeAndFlush(responseFrame).addListener(future -> {
    if (future.isSuccess()) {
        new WebTransportSession((QuicStreamChannel) ctx.channel());
    }
});
```

しかし、この書き込みが完了する**前に**後続の双方向ストリームフレームが到着する可能性があり、以下の問題が発生します:

1. `WebTransportSession.toSession()`が`null`を返す
2. `WebTransportSession.createAndAddStream()`が`IllegalStateException`をスローする
3. フレームが黙って破棄される

### 影響を受けるコードパス

**ファイル:** `src/main/java/io/netty/incubator/codec/http3/Http3FrameCodec.java:350`

```java
case WEBTRANSPORT_BIDIRECTIONAL_FRAME_TYPE:
    long sessionId = payLoadLength;
    this.webTransportStream = WebTransportSession.createAndAddStream(sessionId, ((QuicStreamChannel) ctx.channel()));
    // セッションがまだ存在しない場合、これは失敗します！
    out.add(new WebTransportStreamOpenFrame(this.webTransportStream));
    return payLoadLength;
```

**ファイル:** `src/main/java/jp/hisano/netty/webtransport/WebTransportSession.java:21-25`

```java
public static WebTransportStream createAndAddStream(long sessionId, QuicStreamChannel streamChannel) {
    WebTransportSession session = WebTransportSession.toSession(streamChannel);
    if (session == null || session.sessionId() != sessionId) {
        throw new IllegalStateException();  // ここで競合状態が発生！
    }
    // ...
}
```

---

## テスト結果

### 断続的な失敗

- テストは約50%の確率で成功
- 失敗: 30秒のタイムアウト内に2番目のサーバーからクライアントへのパケットが受信されない
- 最も一般的な失敗モード: DATAGRAMテストで2番目の"def"パケットが失われる

### 観察可能な症状

テストログから:

```
[Test] Waiting for 'packet received from server: abc' message... (client queue size: 1)
[Test] Received: packet received from server: abc
[Test] Waiting for 'packet received from server: def' message... (client queue size: 0)
[Test] Received: null (expected: packet received from server: def)
```

**注:** DATAGRAMテストではブラウザのコンソール出力が表示されず、JavaScript実行の問題を示唆しています。

---

## 追加の問題: QUICデータグラムの信頼性の欠如

WebTransportデータグラムはQUICデータグラム上に構築されており、**本質的に信頼性がありません**:

- 配信保証なし（ドロップされる可能性があります）
- 順序保証なし
- ベストエフォート配信のみ

これはWebTransport仕様に従った**設計上の仕様**です。テスト設計は潜在的なデータグラム損失を考慮する必要があります:

1. 信頼性のあるテストにはデータグラムの代わりにストリームを使用する
2. データグラムのリトライロジックを実装する
3. 時折の失敗を期待される動作として受け入れる

---

## 推奨される修正

### 修正 #1: 同期的なセッション作成（推奨）

レスポンスを送信する**前に**セッションを作成:

```java
@Override
protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) throws Exception {
    if (!frame.headers().contains(":protocol", "webtransport")) {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status("403");
        ctx.writeAndFlush(headersFrame).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        return;
    }

    // 最初に同期的にセッションを作成
    WebTransportSession session = new WebTransportSession((QuicStreamChannel) ctx.channel());

    Http3HeadersFrame responseFrame = new DefaultHttp3HeadersFrame();
    responseFrame.headers().status("200");
    responseFrame.headers().secWebtransportHttp3Draft("draft14");
    ctx.writeAndFlush(responseFrame);
}
```

**利点:**

- 競合状態を完全に排除
- フレームが到着する前に常にセッションが存在
- よりシンプルなコードフロー

**欠点:**

- 書き込みが失敗してもセッションが存在する（軽微なリソースリーク）

### 修正 #2: リトライロジック付きのバッファリング

セッションが準備できるまで受信フレームをバッファリング:

```java
private final Queue<Object> pendingFrames = new LinkedList<>();
private volatile boolean sessionReady = false;

ctx.writeAndFlush(responseFrame).addListener(future -> {
    if (future.isSuccess()) {
        new WebTransportSession((QuicStreamChannel) ctx.channel());
        sessionReady = true;
        synchronized (pendingFrames) {
            while (!pendingFrames.isEmpty()) {
                ctx.fireChannelRead(pendingFrames.poll());
            }
        }
    }
});
```

**利点:**

- フレーム損失なし
- 書き込み成功時のみセッションを作成

**欠点:**

- より複雑
- 同期が必要
- 書き込みが決して成功しない場合、メモリリークの可能性

### 修正 #3: テストの改善

テストスイートに対して:

1. **タイムアウトの延長:** ✅ 完了（10秒→30秒、その後10秒に調整）
2. **リトライの追加:** JUnitの`@RepeatedTest`またはカスタムリトライロジックを使用
3. **順次実行:** ✅ 完了（`@Execution(ExecutionMode.SAME_THREAD)`経由）
4. **より良いログ:** ✅ 完了
5. **データグラムの信頼性の欠如への対処:** リトライロジックの追加または時折の失敗を受け入れる

---

## 優先度

**高** - この競合状態は以下のような本番環境の問題を引き起こす可能性があります:

- 高遅延ネットワークが競合の時間ウィンドウを増加させる
- 重い負荷がセッション作成前のフレーム到着を増加させる
- メッセージの損失がアプリケーションレベルの失敗につながる

---

## 次のステップ

1. ✅ 競合状態を文書化
2. ✅ 修正 #1（同期的なセッション作成）を実装
3. ✅ セッション作成失敗時の例外処理を追加
4. 修正を検証するために人為的な遅延を伴う統合テストの追加を検討
5. 将来の参考のためにCLAUDE.mdに競合状態のノートを更新

---

## 関連ファイル

- `src/main/java/jp/hisano/netty/webtransport/WebTransportStreamCodec.java`
- `src/main/java/jp/hisano/netty/webtransport/WebTransportSession.java`
- `src/main/java/io/netty/incubator/codec/http3/Http3FrameCodec.java`
- `src/test/java/jp/hisano/netty/webtransport/WebTransportTest.java`
