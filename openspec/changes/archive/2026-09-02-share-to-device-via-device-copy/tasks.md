## 1. Scoped device grant

- [x] 1.1 Add a sender-side grant that binds peer device id, TLS fingerprint, share nonce, expiry, and the normalized shared path allow-list
- [x] 1.2 Record the grant when share heartbeat reaches COMPLETED, and drop it on cancel, reject, expiry, or Save completion
- [x] 1.3 Cover grant matching and path allow-list (file vs directory vs unshared parent listing) in `commonTest`

## 2. Sender path enforcement

- [x] 2.1 Enforce the allow-list on Session path/file RPCs and streams for a share-granted peer
- [x] 2.2 Enforce the same allow-list on WebRTC device file/path RPC for a share-granted peer
- [x] 2.3 Reject symbolic links and unshared paths with forbidden/not-found, with tests

## 3. Receiver connect after approval

- [x] 3.1 After Save/View/Auto-Save approval, connect with `DeviceState.connect` (Session first, WebRTC fallback) instead of `HttpShareRouteClientManager.share` as the file channel
- [x] 3.2 Pass the share nonce / fingerprint so the sender can bind the grant
- [x] 3.3 On connect failure, set share status ERROR and notify; do not fall back to `/api/share/read-bytes`

## 4. Save through device copy

- [x] 4.1 Implement Save / Auto-Save as device-to-local `copyTo` of the shared list into the cached save path
- [x] 4.2 Reuse the existing copy task runtime (progress, pause, cancel, directory queue)
- [x] 4.3 Disconnect the share-granted device connection after Save success or failure
- [x] 4.4 Keep success/error notifications with the save path
- [x] 4.5 Add tests that Save copies via the device file client and never calls share HTTP byte routes

## 5. View as scoped device desk

- [x] 5.1 Open the sender as the current device desk after View approval, limited to the shared list
- [x] 5.2 Keep the connection until the user leaves or the sender cancels
- [x] 5.3 Keep View connect success/failure notifications
- [x] 5.4 Add tests that View does not mount `FileProtocol.Share`

## 6. Leave link share alone

- [x] 6.1 Confirm browser link-share and system share still use HTTP / OS share
- [x] 6.2 Keep `/api/share/heartbeat` as the native App approval signal
- [x] 6.3 Run `openspec validate --change share-to-device-via-device-copy --strict` and fix any gaps
- [x] 6.4 Route native approval heartbeat to a separately advertised TLS HTTP listener instead of the Session ALPN port, with endpoint-selection and discovery regression tests
- [x] 6.5 Remove development-stage compatibility fallbacks so missing or invalid approval endpoint metadata is rejected instead of reusing the Session port
