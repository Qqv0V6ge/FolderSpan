## Context
`MultiPeerWebRtcController` already supports multiple peers inside one signaling room, but it still maintains a single signaling connection and a single active room configuration. The requested UX is to make several saved host/room targets selectable from the drawer without requiring inline room editing.

## Goals / Non-Goals
- Goals:
- let settings store multiple saved signaling hosts or room IDs without a storage migration
- show one drawer entry per resolved host/room target
- allow the drawer to switch the active room entry directly
- keep validation clear when users configure invalid or ambiguous multi-value inputs
- Non-Goals:
- concurrent connections to multiple signaling rooms
- per-entry ICE/TURN credentials
- changing the signaling server protocol or backend room semantics

## Decisions
1. Reuse the existing string settings keys and parse comma, semicolon, or newline separated values.
2. If only one side contains multiple values, pair every value with the single value on the other side.
3. If both sides contain multiple values, require matching counts and pair them by index order.
4. Expose the active `WebRtcConfig` as read-only UI state so the drawer can highlight and switch entries safely.

## Tradeoffs
- Delimited strings are less structured than a dedicated JSON schema, but they keep migration risk low and preserve backward compatibility.
- Because the controller remains single-session, switching entries tears down the existing room before joining the next one.
