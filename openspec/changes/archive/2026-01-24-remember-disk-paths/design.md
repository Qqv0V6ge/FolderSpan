# Design: Session-scoped per-disk path memory

## Scope
Remember and restore the last visited path per DiskBase instance within a single app session. No persistence across restarts.

## Key decisions
- Store paths in-memory inside FileState (session-scoped) using a stable, non-sensitive key derived from DiskBase.
- Path restoration occurs during updateDesk. `pathOverride` continues to take precedence.
- Do not validate or sanitize the stored path when restoring; use existing error handling if a path is invalid.

## Disk key strategy
Define a private helper in FileState to map DiskBase to a stable key:
- Local -> `local`
- Device -> `device:<id>`
- Share -> `share:<id>`
- Network -> `network:<protocol>:<host>:<username>` (exclude password)

## State flow
1. Whenever `updatePath` is called, store the current path under the key for the current desk.
2. When `updateDesk` is called:
   - If `pathOverride` is provided, use it.
   - Else, if a remembered path exists for the target desk, call `updatePath` with that path.
   - Else, fall back to existing root/home/share handling.

## Testing notes
Add unit coverage for the key mapping and restore-selection logic (prefer extracting minimal helpers to keep tests pure).
