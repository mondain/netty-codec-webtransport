## Development Diary

### 2024/07/01 (Mon)

- Started development of WebTransport codec for Netty
- Researched various WebTransport specifications
- Created project structure

### 2024/07/02 (Tue)

- Added license files
- Set up build environment with mvn-wrapper
- Set up test environment
- Configured Playwright environment

### 2024/07/03 (Wed)

- Set up Netty HTTP/3 environment
- Added Netty HTTP/3 test cases

### 2024/07/06 (Sat)

- Added HTTP/3 test cases using Playwright + Chrome
- Implemented WebTransport connection establishment

#### Research Notes

- Self-signed certificate limitations
  - [Must use ECDSA](https://stackoverflow.com/questions/75979276/do-i-have-to-get-a-valid-ssl-certificate-to-make-webtranport-server-examples-wor)
  - [Validity period must be within two weeks](https://qiita.com/alivelime/items/e5c75288f56cd0949dca)
- [Chromium's WebTransport implementation](https://chromium.googlesource.com/chromium/src/+/d622da780b2abe8ae376506323c3a3d26e9ac7da/third_party/blink/renderer/modules/webtransport)

### 2024/07/07 (Sun)

- Implemented client→server bidirectional stream support

### 2024/07/08 (Mon)

- Added WebTransportStreamFrame class

### 2024/07/09 (Tue)

- Refactored class structure

### 2024/07/10 (Wed)

- Implemented server→client bidirectional stream support

### 2024/07/12 (Fri)

- Implemented unidirectional stream support
- Implemented datagram support
- Refactored classes

### 2024/07/13 (Sat)

- Implemented Transport#close support

### 2024/07/14 (Sun)

- Implemented unidirectional stream close support
- Implemented bidirectional stream close support
- Improved datagram-related API
