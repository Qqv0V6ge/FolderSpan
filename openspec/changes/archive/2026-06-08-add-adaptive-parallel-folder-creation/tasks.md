## 1. Remote Batch Folder Creation

- [x] 1.1 Add JVM tests proving `DeviceFileService.createFolders` runs authorized folder creations concurrently, preserves result order, and continues after individual failures.
- [x] 1.2 Update `DeviceFileService.createFolders` to use adaptive operation parallelism for authorized paths while keeping permission failures in their original result slots.

## 2. File Task Directory Queue Execution

- [x] 2.1 Add focused tests proving `TaskRuntimeStage.COPY` directory queue entries are processed with adaptive parallelism and retryable folder failures do not stop remaining directory entries.
- [x] 2.2 Update `FileState` task runtime execution to process copy-stage directory queues through `processItemsAdaptive`, using existing endpoint and remote-status concurrency helpers.

## 3. Validation

- [x] 3.1 Run `openspec validate add-adaptive-parallel-folder-creation --strict` and fix proposal/spec/task issues.
- [x] 3.2 Run `./gradlew :shared:jvmTest` and fix regressions.
- [x] 3.3 Mark all OpenSpec tasks complete after implementation and verification.
