## Context
The current serial-transfer refactor still creates a fresh payload data channel for each file transfer. Logs and code tracing show the sender finishes payload emission, then waits for a `TransferResult` control message. Large consecutive files can fail at that stage because the previous payload channel is only logically closed in the wrapper while the underlying SCTP stream may still be draining or tearing down.

This is most visible in device-copy scenarios where users transfer several large files in sequence. The first file often succeeds, while the second file stalls and the sender times out waiting for receiver confirmation.

## Goals
- Reuse a dedicated payload data channel for sequential file transfers within one peer session.
- Prevent per-file channel teardown races from delaying or dropping receiver completion for the next file.
- Keep transfer framing, receiver confirmation, retransmit, and progress semantics unchanged.
- Keep production and test controllers behaviorally aligned.

## Non-Goals
- Do not redesign signaling, approval, or device RPC.
- Do not support concurrent payload transfers on the same peer session.
- Do not replace the existing transfer frame format or retransmit protocol.
- Do not introduce a user-facing transfer-mode switch.

## Decisions
- Each connected peer session owns one reusable payload data channel in addition to the control channel.
- File transfers on a peer session are serialized across that payload channel; the next file does not start payload emission until the previous file has completed receiver confirmation.
- Transfer metadata and completion results continue to travel on the control channel.
- If the persistent payload channel closes or cannot be reopened, the current transfer fails and the session must recreate the payload channel before later transfers.
- The JVM wrapper may need stricter close-state handling so application state does not report `Closed` before the native data channel has actually transitioned.

## Risks / Trade-offs
- A single payload channel means one bad transfer blocks the next file on the same peer until failure or recovery.
- Reusing the channel reduces SCTP stream churn but requires clearer session-level channel ownership and reconnection logic.
- Test coverage must prove consecutive files reuse the same payload channel and that failures recreate it cleanly.

## Migration Plan
1. Introduce session-level payload channel ownership and open/recovery logic.
2. Route sequential file transfers through the reusable payload channel.
3. Remove per-file payload channel creation and teardown from send paths.
4. Extend controller tests for consecutive large files and payload channel recovery.

## Open Questions
- Whether the payload channel should be opened eagerly with the control channel or lazily on first file transfer.
- Whether the JVM wrapper can preserve native close observation without reintroducing stale callback issues.
