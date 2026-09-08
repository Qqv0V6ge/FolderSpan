## Context

See proposal.md for why App「分享到设备」must leave the HTTP share byte path. Today:

- Sender `DeviceState.share` short-polls `/api/share/heartbeat` until the receiver approves.
- Receiver `HttpShareRouteClientManager.share` calls `/api/share/connect`, then Save copies through share HTTP (`read-bytes` / archive-download).
- The same two devices already speak Session (LAN) or WebRTC (WAN) for drawer device copy.

Link-share HTTP (`LinkShareRawHttpServer`) stays. This change only reroutes **native App device-share** bytes onto the existing device copy stack.

## Goals / Non-Goals

**Goals:**

- After approval, file bytes use `Device.files.copyTo` / runtime copy queue.
- Transport selection matches ordinary device connect: Session if possible, otherwise WebRTC.
- Access is limited to the shared file list.
- Save / View / reject notifications and Auto-Save disconnect behavior stay.

**Non-Goals:**

- FSAR archive over Session/WebRTC (follow-up; this path will inherit it).
- Rewriting browser link-share or system share.
- Removing `/api/share/heartbeat` as the approval signal.
- Giving the receiver a full unscoped device disk.

## Decisions

### 1. Pull on the receiver (Device → Local), not push from the sender

Save is already a receiver action with a local save path. Device-to-local copy is the same shape: remote source, local dest, receiver drives `copyTo`.

Push (sender Local → Device into the receiver save path) would need the receiver to expose a writable device endpoint and to publish the save path back through share signaling. Pull reuses the current Save ownership and the existing DeviceRoute.

Alternative considered: keep HTTP share for View and only switch Save. Rejected — View would still be a second protocol, and later copies from that desk would miss Session/WebRTC.

### 2. Keep heartbeat for approval, then upgrade to a device connection

`/api/share/heartbeat` plus notifications already implement waiting / save / view / reject / auto-save. Replacing that with device `Connect` approval would churn UX and mix "share these files" with "pair as a full device".

After COMPLETED:

1. Sender records a **scoped device grant** (peer id, nonce, shared paths, expiry).
2. Receiver calls the normal device connect (Session or WebRTC).
3. Sender accepts that connect only with the grant, and `DeviceFileService` / path RPCs deny paths outside the list.

The native Session listener remains ALPN-only and never parses HTTP/1.1. The sender therefore sends
`/api/share/heartbeat` to a dedicated TLS HTTP approval listener whose port is advertised separately from the
Session port through LAN beacons and Session Identify. The default ports are `12042` for approval and `12040` for
Session. The approval listener only exposes the heartbeat route and uses the same device certificate, so the
client keeps certificate pinning without reintroducing HTTP on the Session socket.

This protocol is still in development, so endpoint discovery is intentionally version-strict: the approval port is
required metadata, must be valid, and must differ from the Session port for Session peers. Missing or invalid data is
rejected instead of falling back to the Session port. Both peers must run the updated protocol.

Alternative considered: mount a temporary Share disk that internally proxies Session. Rejected — still two clients and two progress models.

### 3. Scope is a path allow-list, not a new protocol

The grant stores normalized absolute paths from the share list. Directory entries allow that directory tree. File entries allow that file only. Listing a parent returns only granted children. Symbolic links stay rejected, same as device copy.

### 4. View is a scoped device desk; Save disconnects when done

View keeps the Session/WebRTC connection and switches the current desk to that device, rooted at the shared selection. Save / Auto-Save run one copy task then drop the grant and disconnect, matching today's auto-receive cleanup.

### 5. Do not invent a third transport picker

Use `DeviceState.connect` / `transportType` as-is. LAN beacon + Session is the native default; WebRTC is the fallback when Session cannot be established (WAN, browser-adjacent, relay).

## Risks / Trade-offs

- **[Risk] Receiver gains a device session to the sender** → Mitigation: grant is allow-listed, short-lived, and dropped after Save; View stays until the user leaves or the sender cancels.
- **[Risk] Shared files that live on another device behind the sender** → Mitigation: sender-side copy still uses existing DeviceRoute when the shared item's protocol is Device; the grant is on the sender's reachable paths, not a new relay protocol.
- **[Risk] Approval HTTP and data Session are different sockets** → Mitigation: advertise both ports, pin both sockets to the same device certificate, and bind the grant to device id + TLS fingerprint + nonce already used by `DeviceShareConnectionGrant`.
- **[Trade-off] Link-share HTTP remains a second stack** → Acceptable; browsers cannot speak Session.

## Migration Plan

- No backward-compatibility requirement exists for native App device share: switch Save/View and endpoint discovery
  atomically, and reject peers that do not advertise the dedicated approval port.
- Heartbeat and notifications stay within the new protocol; no compatibility path is retained for older beacons or
  endpoint records.
- If device connect fails after approval, surface the existing share ERROR notification; do not silently fall back to `/api/share/read-bytes` (that would keep the two-stack problem).

## Open Questions

None that block specs or tasks. Batch size / compression for tiny files is a later archive change on the shared device copy pipeline.
