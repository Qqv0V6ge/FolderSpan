## 1. Implementation
- [x] 1.1 Add a platform-neutral widget device snapshot model and JSON encoding/decoding
- [x] 1.2 Persist snapshot and request widget refresh when device state changes
- [x] 1.3 Add a shared widget-action handler that maps commands to tray-equivalent flows
- [x] 1.4 Android: implement a Glance device widget UI (grid + scan + connected/unconnected) and intent handling
- [x] 1.5 iOS: add a WidgetKit extension (grid + scan + connected/unconnected) and open-URL handling
- [x] 1.6 Persist app color schemes for widget theme sync and refresh widgets on changes
- [x] 1.7 Android: apply stored app theme colors in Glance widget rendering
- [x] 1.8 iOS: apply stored app theme colors in WidgetKit rendering
- [x] 1.9 Persist app typography sizes for widget theme sync
- [x] 1.10 Android: apply stored typography sizes in Glance widget rendering
- [x] 1.11 iOS: apply stored typography sizes in WidgetKit rendering
- [x] 1.12 Android: fix widget tab switching and refresh
- [x] 1.13 Android: refresh header layout and status-based card colors
- [x] 1.14 iOS: refresh header layout and status-based card colors
- [x] 1.15 Refactor widget theme/snapshot types into Theme.kt with WidgetTheme/WidgetSnapshot naming
- [ ] 1.16 Manual validation on Android (add widget → scan → connect/disconnect → verify widget updates)
- [ ] 1.17 Manual validation on iOS (add widget → tap actions → verify app executes flow → verify widget updates)

## 2. Tooling / Checks
- [x] 2.1 Run `openspec validate add-device-widgets --strict`
