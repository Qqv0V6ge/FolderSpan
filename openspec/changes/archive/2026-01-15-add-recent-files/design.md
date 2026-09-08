## Context
The app currently provides Favorites and Bookmarks, but there is no persistent record of recently opened files or folders. The request is to add a Recent entry above Favorites in the drawer and persist recently clicked items from FileScreen, including non-local protocols.

## Goals / Non-Goals
- Goals:
  - Record every FileScreen click (file or folder) into a local recent table.
  - De-duplicate by path + protocol + protocolId and update lastAccessed.
  - Keep a bounded list of the most recent entries (300 max).
  - Provide a Recent screen that can open stored entries.
  - Allow multi-select removal of recent entries.
- Non-Goals:
  - Unlimited history browsing beyond the bounded recent list.
  - Syncing recent entries across devices.

## Decisions
- Data model:
  - Add a `FileRecent` SQLDelight table with fields needed for display/opening: name, path, isDirectory, mineType, size, createdDate, updatedDate, protocol, protocolId, and lastAccessed.
  - Add a unique index on (path, protocol, protocolId) to support de-duplication.
- Retention policy:
  - On each record, upsert by (path, protocol, protocolId), set lastAccessed to now, then delete all but the most recent 300 entries (order by lastAccessed DESC).
- Recording:
  - Invoke a recent-recording function from FileScreen when a FileCard is clicked, before navigation/opening.
- UI:
  - Add a Recent drawer item above Favorites that opens a new RecentScreen.
  - RecentScreen loads recent entries (if any) ordered by lastAccessed and allows opening them; for folders it updates path, for files it opens the file.
  - When the recent entry protocol differs from the current desk, resolve the target desk via protocol/protocolId (Local, Device, Share) before opening.
  - RecentScreen supports multi-select removal of recent entries and a clear-all action.

## Risks / Trade-offs
- If a remote device/share is unavailable, the recent entry cannot be opened; the UI should show an error or no-op.
- Storing a bounded list keeps storage predictable while providing more context than a single entry.

## Migration Plan
- Add SQLDelight schema and rely on normal migration generation.
- No data backfill needed; the table starts empty.

## Open Questions
- None.
