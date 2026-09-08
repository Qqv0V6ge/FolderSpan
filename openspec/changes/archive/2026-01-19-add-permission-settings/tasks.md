## 1. Implementation
- [x] 1.1 Audit platform permission usage (manifest, notifications, special settings) and define per-platform permission lists.
- [x] 1.2 Define permission models and expect permission provider APIs in commonMain (permissions list, status, request callback).
- [x] 1.3 Implement platform actuals for Android/iOS/JS/Wasm/JVM with required permissions and status/request logic.
- [x] 1.4 Add permissions entry to settings list; hide when platform exposes no permissions.
- [x] 1.5 Implement permissions settings screen UI with status display, descriptions, and request actions.
- [x] 1.6 Refresh permission status after requests and on app resume where needed.
- [x] 1.7 Add focused tests for permission list/status mapping or provider behavior where feasible.
- [x] 1.8 Run relevant Gradle checks (e.g., :shared:jvmTest, :composeApp:jvmTest) and capture results.
