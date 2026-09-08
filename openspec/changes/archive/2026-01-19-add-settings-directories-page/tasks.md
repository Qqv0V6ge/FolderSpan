## 1. Implementation
- [x] 1.1 Add a common directory item model (title, description, path) and an `expect` directory provider API in common code.
- [x] 1.2 Implement platform `actual` providers that return all retrievable directories on each platform (e.g. home, cache, data), omitting unavailable entries.
- [x] 1.3 Add a new Settings subpage screen under `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/settings` that renders the directory list.
- [x] 1.4 Add a Settings entry that navigates to the new directory display screen.
- [x] 1.5 Handle empty-state rendering when no directories are available for a platform.

## 2. Validation
- [x] 2.1 Manual: open Settings → Directories and verify each item shows title, description, and an absolute path string when available.
- [x] 2.2 Manual: verify platform differences (Android/iOS/Desktop/Web) only show directories that can be retrieved.
