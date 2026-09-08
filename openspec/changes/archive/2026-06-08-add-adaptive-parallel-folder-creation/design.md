## Context

File operation tasks already build persisted copy and move manifests with directory-create entries before file entries. File copies and resumable transfer chunks use `processItemsAdaptive`, but directory-create queue entries still run serially. Device `createFolders` requests also process paths one by one even though they return per-path batch results and can safely preserve result order after parallel execution.

## Goals / Non-Goals

**Goals:**
- Reuse existing operation adaptive parallelism for folder creation.
- Preserve persisted queue, retry, pause, cancel, and batch result semantics.
- Keep Device endpoint concurrency conservative by combining local runtime state with remote transfer status where available.

**Non-Goals:**
- Do not change HTTP/WebRTC request or response schemas.
- Do not parallelize delete stages.
- Do not add new runtime sampling APIs or UI controls.

## Decisions

- Use `processItemsAdaptive` for folder creation work. This keeps the same adaptive limit behavior as file copy operations and avoids another limiter implementation.
- In file task execution, add an adaptive branch for `TaskRuntimeStage.COPY` plus `TaskRuntimeQueueCategory.DIRECTORIES`. Each worker peeks, executes one `DIRECTORY_CREATE` entry, records success or failure, and acknowledges the queue entry under the existing queue mutex.
- Folder-create failures that are not task-level transport failures are recorded as retryable item failures and do not cancel the remaining directory workers. Task-level failures and cancellation still stop the task.
- In `DeviceFileService.createFolders`, precompute indexed path work and return a mutable result list in input order. Permission failures fill their own result slot before execution; authorized paths run through adaptive parallelism.
- Test-only injection may be added to `DeviceFileService` for folder creation and operation parallelism so concurrency behavior can be verified without relying on platform filesystem timing.

## Risks / Trade-offs

- Parallel creation of parent and child paths in the same batch can race if the child is dispatched first. The task manifest normally orders parents before children, but service-level arbitrary batches may not. Mitigation: preserve per-path failure results instead of reordering request semantics.
- Higher concurrency can increase filesystem or remote pressure. Mitigation: adaptive operation limits already shrink on slow/failing work and when runtime state is busy.
- File task queue acknowledgements are shared mutable state. Mitigation: keep queue mutation under the existing `stateMutex`.
