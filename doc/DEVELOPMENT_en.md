## Advantages over WebSocket

- Datagram support
- Shorter connection times
- Avoids Head-of-Line (HoL) blocking
- Can be used in WebWorkers
- Handover across networks

## Specifications

- [WebTransport over HTTP/3](https://datatracker.ietf.org/doc/draft-ietf-webtrans-http3/) (implements draft-14)
- [The WebTransport Protocol Framework](https://www.ietf.org/archive/id/draft-ietf-webtrans-overview-07.html)

- [MDN WebTransport API](https://developer.mozilla.org/en-US/docs/Web/API/WebTransport_API)

## Differentiation Strategy

- Support coexistence with Netty's HTTP/3 codec
- Quality assurance through E2E testing with real browsers
  - Testing with Chrome/Edge/Firefox using Playwright
- Support for `WebTransport over HTTP/2` and `WebTransport over WebSocket`
- Support for analysis with [qlog/qvis](https://github.com/quiclog/qvis)
- Support for other protocols built on top of WebTransport

## Open Source Implementations

- [webtransport-go](https://github.com/quic-go/webtransport-go)
- [WTransport](https://github.com/BiagioFesta/wtransport)
- [webtransport.rs](https://github.com/security-union/webtransport.rs)
  - Rust implementation
  - More of a sample than a library
- [WebTransportServer](https://github.com/langhuihui/WebTransport-Go)
  - Go implementation
  - More of a sample than a library

## Additional Resources

- [History leading up to WebTransport](https://qiita.com/yuki_uchida/items/d9de148bb2ee418563cf)
  - Includes command-line examples for ignoring certificate errors when launching Chrome
- [WebTransport over HTTP/3 protocol overview](https://asnokaze.hatenablog.com/entry/2021/04/18/235837)
- [Browser WebTransport API overview](https://tech.aptpod.co.jp/entry/2022/05/17/100000)
    - [Google's samples](https://github.com/GoogleChrome/samples/tree/gh-pages/webtransport)
- [WebTransport packet overview](https://qiita.com/alivelime/items/58154961d5c6b0ac150b)
