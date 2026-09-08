## 1. Implementation
- [x] 1.1 Add a session-scoped per-disk path cache in FileState keyed by DiskBase
- [x] 1.2 Record the current path for the active disk when updatePath runs
- [x] 1.3 Restore cached path on updateDesk when available (pathOverride remains highest priority)
- [x] 1.4 Add unit coverage for disk key mapping and restore selection behavior (shared/jvm)
- [x] 1.5 Run `./gradlew :shared:jvmTest`
