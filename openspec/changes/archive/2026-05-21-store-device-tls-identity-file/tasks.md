## 1. Implementation
- [x] 1.1 Add/choose platform app-private TLS identity directory resolvers for JVM, Android, and iOS.
- [x] 1.2 Implement an encrypted/obfuscated filesystem-safe identity file name without storing the clear text name on disk.
- [x] 1.3 Implement the three-segment encrypted identity container with randomized segment order and randomized filler sizes.
- [x] 1.4 Replace JVM/Android settings-backed PKCS12 persistence with file-backed segmented persistence.
- [x] 1.5 Replace iOS settings-backed PEM persistence with file-backed segmented persistence.
- [x] 1.6 Ensure missing or invalid file-backed identity data regenerates a new TLS identity without reading legacy settings keys.

## 2. Verification
- [x] 2.1 Add unit coverage for segment read/write roundtrip, identity segment discovery, randomized filler, and corrupt-file regeneration where platform-testable.
- [x] 2.2 Verify the published `tlsFingerprintSha256` remains derived from the loaded file-backed certificate.
- [x] 2.3 Run relevant Gradle compilation/tests for shared and compose targets.
