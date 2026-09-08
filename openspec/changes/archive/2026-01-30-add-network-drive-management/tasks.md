## 1. Implementation
- [x] 1.1 Add SQLDelight schema and queries for persisted network drives (name, protocol, host, username, password, pathSeparator).
- [x] 1.2 Extend NetworkState with persistence helpers and load persisted entries on app start.
- [x] 1.3 Update AppDrawerNetwork to always render with expand/collapse and a single Add action that opens the protocol selection screen.
- [x] 1.4 Implement a protocol selection screen (FTP, SFTP, WebDav) and dedicated add screens per protocol with Network fields, editable pathSeparator default "/", and a save-to-database toggle default enabled.
- [x] 1.5 Add network entry metadata with persistence toggling and deletion support in NetworkState and SQLDelight queries.
- [x] 1.6 Add a network management screen accessible from the drawer with list fields, persistence indicator/toggle, and delete confirmation; update existing network usage to rely on the new entry model.
- [x] 1.7 Add edit flows for network entries, including updating persisted records and refreshing the active desk when needed.
- [x] 1.8 Add a LinkShare option to the protocol selection screen and a dedicated add screen that parses share links.

## 2. Tests
- [x] 2.1 Add tests covering NetworkState persistence load and save-toggle behavior.
- [x] 2.2 Add tests covering persistence toggle updates and deletions.
- [x] 2.3 Add tests covering entry updates for persisted and session-only items.

## 3. Validation
- [x] 3.1 Run `./gradlew :shared:jvmTest`.
- [x] 3.2 Run `./gradlew :composeApp:compileKotlinJvm`.
