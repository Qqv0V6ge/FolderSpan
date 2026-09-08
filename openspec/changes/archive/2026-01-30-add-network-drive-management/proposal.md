# Change: Add network drive add flow and persistence

## Why
Users need a guided flow to add network drives (FTP/SFTP/WebDav) and keep them across app launches. The current network drawer item only reflects in-memory entries and offers no protocol selection or add screens.

## What Changes
- Always show the network drawer entry with expand/collapse and an Add action that opens a protocol selection screen.
- Add a protocol selection page listing FTP, SFTP, WebDav, and LinkShare (exclude SMB).
- Add dedicated add screens per protocol to capture Network fields (name, host, username, password, pathSeparator) with default pathSeparator "/" for FTP/SFTP/WebDav, plus a LinkShare add screen that accepts a share link and optional password.
- Add a "save to database" toggle on each add screen (default enabled) to optionally persist entries.
- Load persisted network entries from the database on app start into NetworkState for disk switching.
- Add a network management screen accessible from the drawer that lists all network entries, shows persistence status, allows toggling persistence, supports editing entry details, and confirmed deletion (switching back to local if deleting the active network).

## Impact
- Affected specs: manage-network-drives (new)
- Affected code: AppDrawerNetwork UI, new network screens, NetworkState persistence, SQLDelight schema and queries
