## Context
Network entries are currently stored only in memory. Users need a protocol selection screen and per-protocol add screens, plus optional persistence so network drives are available on app start.

## Goals / Non-Goals
- Goals:
  - Always expose the drawer "Network" entry with expand/collapse and an Add action.
  - Provide protocol selection for FTP, SFTP, WebDav, and LinkShare (no SMB).
  - Provide per-protocol add screens with Network fields and a default pathSeparator of "/" for FTP/SFTP/WebDav, plus a LinkShare add screen that captures the share link and optional password.
  - Allow optional persistence via a "save to database" toggle (default enabled).
  - Auto-load persisted network entries into NetworkState on startup.
  - Provide a management screen to list all network entries, show persistence status, toggle persistence, edit entry details, and delete entries safely.
- Non-Goals:
  - Implement FTP/SFTP/WebDav browsing or transfer logic.
  - Add SMB protocol support.
  - Provide bulk import or sync features.

## Decisions
- Data model: add a SQLDelight table (e.g., `NetworkDrive`) with fields: id (autoincrement), name, protocol, host, username, password, pathSeparator.
- Persistence: when the save toggle is on, insert into the table; when off, only add to the in-memory NetworkState list.
- Startup loading: add a NetworkState load method that reads all saved entries and populates `NetworkState.networks`; call it from drawer composition so it runs when the app UI loads.
- Navigation: use `MainState.pushScreen(...)` for protocol selection and per-protocol add screens.
- Protocol screens: implement separate screen classes for FTP, SFTP, and WebDav add flows; add a LinkShare add screen that parses the share link into a ShareNetwork entry.
- Management UI: add a drawer action to open a management page showing all entries with persistence toggle, edit action, and delete confirmation.

## Risks / Trade-offs
- Stored credentials are saved in the local database in plain text, matching current storage patterns.
- Editing persisted entries updates the database; failures may fall back to in-memory state only.

## Migration Plan
- Add the SQLDelight table and queries, update generated database, and load persisted entries on app start.
