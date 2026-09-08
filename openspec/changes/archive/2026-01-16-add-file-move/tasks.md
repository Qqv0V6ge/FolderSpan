## 1. Implementation
- [x] 1.1 Review FileState copy/paste flow and conflict handling to mirror for move.
- [x] 1.2 Implement FileState.pasteMoveFile to resolve conflicts, enqueue Move tasks, copy items, then delete sources on successful copy.
- [x] 1.3 Ensure task status and file list updates match copy/delete expectations after move.

## 2. Validation
- [x] 2.1 Add/adjust shared JVM tests covering move success and conflict handling.
- [x] 2.2 Run `./gradlew :shared:jvmTest`.
