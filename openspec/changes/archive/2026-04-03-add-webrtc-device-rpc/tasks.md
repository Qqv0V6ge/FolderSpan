## 1. Implementation
- [x] 1.1 Add OpenSpec deltas for WebRTC approval-gated device connect and signaling message routing.
- [x] 1.2 Extend signaling server/client models to support `connect-request`, `connect-approved`, and `connect-rejected`.
- [x] 1.3 Update the WebRTC controller and shared device state so WebRTC connects enter approval flow before PeerConnection setup.
- [x] 1.4 Reuse existing device-connect notifications for target-side approval/rejection and propagate the result back to the initiator.
- [x] 1.5 Update drawer/settings WebRTC connect triggers to use the approval-gated flow.
- [x] 1.6 Validate the change with strict OpenSpec checks and targeted compilation/tests.
