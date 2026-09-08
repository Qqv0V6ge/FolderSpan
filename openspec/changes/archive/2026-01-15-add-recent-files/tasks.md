## 1. Implementation
- [x] 1.1 Add `FileRecent` SQLDelight table and queries (upsert, select latest, delete extras).
- [x] 1.2 Add recent state/model in shared layer to record and load recent entries.
- [x] 1.3 Record recent entries on FileScreen clicks for files and folders (local + non-local).
- [x] 1.4 Add Recent screen UI that lists recent entries and opens the selected item.
- [x] 1.5 Add drawer entry above Favorites to open the Recent screen.
- [x] 1.6 Wire DI/navigation for the new recent state and screen.
- [x] 1.7 Add tests or lightweight validation for de-duplication and retention behavior.
- [x] 1.8 Add delete support for the stored recent entry (query/state + Recent screen action).
- [x] 1.9 Expand recent retention to a fixed max count and add bulk-delete queries.
- [x] 1.10 Update recent state to load the full list and delete selected entries.
- [x] 1.11 Update Recent screen to show the recent list with multi-select and bulk delete.
- [x] 1.12 Update recent tests to cover retention cap and bulk delete.

## 2. Validation
- [x] 2.1 Manual smoke test: click multiple items, confirm the recent list shows them in order and opens correctly.
- [x] 2.2 Manual: open Recent screen, multi-select entries, delete them, confirm they are removed.
- [x] 2.3 Manual: clear all recent entries and confirm the list is empty.
