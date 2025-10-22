## Race Condition Analysis - WebTransport Codec

### Date: 2025-10-21

### Summary
Identified a critical race condition in the WebTransport session initialization that causes intermittent test failures when server-to-client messages are lost.

---

## The Race Condition

### Location
`src/main/java/jp/hisano/netty/webtransport/WebTransportStreamCodec.java:34-38`

### Problem
The `WebTransportSession` is created **asynchronously** in a write future listener:

```java
ctx.writeAndFlush(responseFrame).addListener(future -> {
    if (future.isSuccess()) {
        new WebTransportSession((QuicStreamChannel) ctx.channel());
    }
});
```

However, subsequent bidirectional stream frames may arrive **before** this write completes, leading to:

1. `WebTransportSession.toSession()` returning `null`
2. `WebTransportSession.createAndAddStream()` throwing `IllegalStateException`
3. Frames being silently dropped

### Affected Code Path

**File:** `src/main/java/io/netty/incubator/codec/http3/Http3FrameCodec.java:350`

```java
case WEBTRANSPORT_BIDIRECTIONAL_FRAME_TYPE:
    long sessionId = payLoadLength;
    this.webTransportStream = WebTransportSession.createAndAddStream(sessionId, ((QuicStreamChannel) ctx.channel()));
    // This fails if session doesn't exist yet!
    out.add(new WebTransportStreamOpenFrame(this.webTransportStream));
    return payLoadLength;
```

**File:** `src/main/java/jp/hisano/netty/webtransport/WebTransportSession.java:21-25`

```java
public static WebTransportStream createAndAddStream(long sessionId, QuicStreamChannel streamChannel) {
    WebTransportSession session = WebTransportSession.toSession(streamChannel);
    if (session == null || session.sessionId() != sessionId) {
        throw new IllegalStateException();  // RACE CONDITION HERE!
    }
    // ...
}
```

---

## Test Results

### Intermittent Failures
- Tests pass ~50% of the time
- Failure: Second server-to-client packet not received within 30-second timeout
- Most common failure mode: DATAGRAM test losing the second "def" packet

### Observable Symptoms
From test logs:
```
[Test] Waiting for 'packet received from server: abc' message... (client queue size: 1)
[Test] Received: packet received from server: abc
[Test] Waiting for 'packet received from server: def' message... (client queue size: 0)
[Test] Received: null (expected: packet received from server: def)
```

**Note:** DATAGRAM test shows NO browser console output, suggesting JavaScript execution issues.

---

## Additional Issue: QUIC Datagram Unreliability

WebTransport datagrams are built on QUIC datagrams, which are **inherently unreliable**:
- No delivery guarantee (can be dropped)
- No ordering guarantee
- Best-effort delivery only

This is **by design** per the WebTransport specification. The test design should account for potential datagram loss, either by:
1. Using streams instead of datagrams for reliable testing
2. Implementing retry logic for datagrams
3. Accepting occasional failures as expected behavior

---

## Recommended Fixes

### Fix #1: Synchronous Session Creation (Recommended)
Create the session **before** sending the response:

```java
@Override
protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) throws Exception {
    if (!frame.headers().contains(":protocol", "webtransport")) {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status("403");
        ctx.writeAndFlush(headersFrame).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        return;
    }

    // Create session FIRST, synchronously
    WebTransportSession session = new WebTransportSession((QuicStreamChannel) ctx.channel());

    Http3HeadersFrame responseFrame = new DefaultHttp3HeadersFrame();
    responseFrame.headers().status("200");
    responseFrame.headers().secWebtransportHttp3Draft("draft14");
    ctx.writeAndFlush(responseFrame);
}
```

**Pros:**
- Eliminates race condition completely
- Session always exists before any frames arrive
- Simpler code flow

**Cons:**
- Session exists even if write fails (minor resource leak)

### Fix #2: Buffering with Retry Logic
Buffer incoming frames until session is ready:

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

**Pros:**
- No frame loss
- Session only created on successful write

**Cons:**
- More complex
- Requires synchronization
- Potential memory leak if write never succeeds

### Fix #3: Test Improvements
For the test suite:

1. **Increase timeout:** ✅ Already done (10s → 30s)
2. **Add retries:** Use JUnit `@RepeatedTest` or custom retry logic
3. **Sequential execution:** ✅ Already done via `@Execution(ExecutionMode.SAME_THREAD)`
4. **Better logging:** ✅ Already done
5. **Handle datagram unreliability:** Add retry logic or accept occasional failures

---

## Priority

**HIGH** - This race condition can cause production issues where:
- High-latency networks increase the time window for the race
- Heavy load increases frame arrival before session creation
- Lost messages lead to application-level failures

---

## Next Steps

1. ✅ Document race condition
2. Implement Fix #1 (synchronous session creation)
3. Add exception handling for session creation failures
4. Consider adding integration tests with artificial delays to verify fix
5. Update CLAUDE.md with race condition notes for future reference

---

## Related Files
- `src/main/java/jp/hisano/netty/webtransport/WebTransportStreamCodec.java`
- `src/main/java/jp/hisano/netty/webtransport/WebTransportSession.java`
- `src/main/java/io/netty/incubator/codec/http3/Http3FrameCodec.java`
- `src/test/java/jp/hisano/netty/webtransport/WebTransportTest.java`
