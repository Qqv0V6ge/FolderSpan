# manage-file-operation-tasks Specification

## Purpose
TBD - created by archiving change update-task-failed-item-retry. Update Purpose after archive.
## Requirements
### Requirement: Failed File Operation Items Can Be Retried
The system SHALL persist structured retry metadata for failed copy, delete, and move task items so the original task can retry only the failed items.

#### Scenario: Retry failed copy items from the original task
- **GIVEN** a copy task finishes with one or more failed file or directory items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL reuse the original task
- **AND** only the failed copy items SHALL be executed again
- **AND** items that already succeeded SHALL NOT be re-executed

#### Scenario: Retry failed delete items from the original task
- **GIVEN** a delete task finishes with failed file or directory items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL retry only the failed delete items
- **AND** the task SHALL be removed after all failed items succeed

#### Scenario: Retry move task delete-source stage
- **GIVEN** a move task copied data successfully but failed while deleting source items
- **WHEN** the user triggers `重试失败项`
- **THEN** the system SHALL retry only the failed source deletion items
- **AND** the system SHALL NOT copy the already copied target items again

### Requirement: Failed File Operation Tasks Expose Retry Entry Points
The system SHALL expose retry entry points for failed file operation tasks in both the task dialog and the task result screen.

#### Scenario: Retry action is visible only when retryable failures exist
- **GIVEN** a failed file operation task has structured failed retry items
- **WHEN** the user opens the task dialog or task result screen
- **THEN** the UI SHALL show a `重试失败项` action

#### Scenario: Retry action is hidden when no retryable failures remain
- **GIVEN** a failed task has no failed retry items remaining
- **WHEN** the user opens the task dialog or task result screen
- **THEN** the UI SHALL NOT show a `重试失败项` action

### Requirement: File task pause and cancel SHALL control WebRTC stream transfers
The system SHALL propagate file task pause, resume, and cancel actions to active WebRTC payload-channel file transfers started by file operations.

#### Scenario: Pause and resume a WebRTC-backed task
- **GIVEN** a copy or download task is transferring a large file through a WebRTC payload-channel stream
- **WHEN** the user pauses the task and then resumes it
- **THEN** the underlying WebRTC transfer SHALL enter a paused state instead of continuing silently in the background
- **AND** the same transfer SHALL continue after resume without restarting from zero

#### Scenario: Cancel a WebRTC-backed task
- **GIVEN** a copy or download task is transferring a large file through a WebRTC payload-channel stream
- **WHEN** the user cancels the task from the task UI
- **THEN** the underlying WebRTC transfer SHALL be canceled
- **AND** the task SHALL finish with a cancellation reason instead of hanging until transfer completion or disconnect

### Requirement: File operation tasks SHALL persist execution manifests and checkpoints
The system SHALL persist a structured manifest before executing copy, move, and delete tasks, and SHALL persist checkpoints while consuming that manifest. Directory traversal used by copy, move, delete, property summary, and device traverse operations SHALL use endpoint-adaptive, bounded parallel traversal across local, device, share, and network sources, SHALL adjust traversal concurrency during scanning based on observed list request performance, and SHALL preserve deterministic persisted entry ordering where manifests are persisted. Copy-stage manifests SHALL execute directory creation entries first, including empty directories, then 0-byte file creation entries, then non-empty file copy entries. Batch directory-create workers, batch empty-file-create workers, batch file copy workers, and resumable chunk transfer workers SHALL also use endpoint-adaptive, bounded operation concurrency while preserving the existing manifest execution order, retry semantics, and delete ordering semantics.

#### Scenario: Persist copy task manifest before execution
- **GIVEN** the user starts a copy task
- **WHEN** the task is accepted for execution
- **THEN** the system SHALL write a manifest containing the ordered copy entries before the first entry is executed
- **AND** the task SHALL resume from that persisted manifest instead of re-traversing when the user later chooses `继续任务`

#### Scenario: Persist move task stage checkpoint
- **GIVEN** a move task has finished copying some entries and is about to continue deleting source entries
- **WHEN** the task writes its current execution state
- **THEN** the checkpoint SHALL record whether it is in the copy stage or delete-source stage
- **AND** continuing the task SHALL resume from the recorded stage without re-copying already completed target entries

#### Scenario: Persist delete task traversal result
- **GIVEN** the user starts deleting a directory tree
- **WHEN** the task begins executing
- **THEN** the system SHALL persist the ordered delete entries
- **AND** a later `继续任务` action SHALL continue with the remaining delete entries instead of traversing the directory again

#### Scenario: Build manifest with parallel traversal
- **GIVEN** a copy, move, or delete task scans a directory tree with multiple child directories
- **WHEN** the task builds its execution manifest
- **THEN** the system SHALL list multiple directories concurrently up to the endpoint-adaptive limit
- **AND** duplicate directory paths SHALL NOT be scanned more than once
- **AND** the persisted copy and delete queues SHALL keep their existing deterministic order

#### Scenario: Copy manifest separates empty entries
- **GIVEN** a copy or move task scans a directory tree containing directories, empty directories, 0-byte files, and non-empty files
- **WHEN** the task builds and executes its copy-stage manifest
- **THEN** all directory entries SHALL be created before file entries, including empty directories
- **AND** all 0-byte file entries SHALL be created after directory creation and before non-empty file copies
- **AND** non-task-level empty-file creation failures SHALL be recorded as retryable item failures while other empty-file entries continue

#### Scenario: Traversal concurrency adapts to endpoint performance
- **GIVEN** a file operation task is scanning a directory tree
- **WHEN** directory list requests complete quickly and successfully
- **THEN** the system SHALL increase traversal concurrency up to the endpoint maximum
- **AND** slow or failing list requests SHALL reduce traversal concurrency before the task reports the failure
- **AND** local, device, share, and network traversal SHALL re-evaluate runtime limits during the scan instead of only calculating concurrency once at scan start
- **AND** local, share, and network traversal SHALL base runtime limits on the local device state and observed list performance
- **AND** device traversal SHALL base runtime limits on both the local device state and the remote device transfer status, using the more conservative limit

#### Scenario: Directory creation concurrency adapts during execution
- **GIVEN** a copy or move task is executing persisted directory-create entries
- **WHEN** folder creation requests complete quickly and successfully
- **THEN** the system SHALL create multiple folders concurrently up to the endpoint operation maximum
- **AND** slow, failing, low-memory, or busy runtime states SHALL reduce folder creation concurrency
- **AND** local, share, and network folder creation SHALL base operation runtime limits on the local device state and observed operation performance
- **AND** device folder creation SHALL base operation runtime limits on both the local device state and the remote device transfer status, using the more conservative limit
- **AND** non-task-level folder creation failures SHALL be recorded as retryable item failures while other folder creation entries continue

#### Scenario: Copy operation concurrency adapts during execution
- **GIVEN** a copy or move task is executing folder file copies or resumable file chunks
- **WHEN** copy requests complete quickly and successfully
- **THEN** the system SHALL increase operation concurrency up to the endpoint operation maximum
- **AND** slow, failing, low-memory, or busy runtime states SHALL reduce operation concurrency
- **AND** local, share, and network copy execution SHALL base operation runtime limits on the local device state and observed operation performance
- **AND** device copy execution SHALL base operation runtime limits on both the local device state and the remote device transfer status, using the more conservative limit
- **AND** delete task execution order SHALL remain deterministic and SHALL NOT be parallelized in a way that can delete parent directories before children

#### Scenario: Parallel traversal reports scan speed and concurrency
- **GIVEN** a copy, move, or delete task is scanning a directory tree
- **WHEN** the parallel traversal discovers entries
- **THEN** the task result text SHALL be updated with the discovered entry count
- **AND** the task runtime metrics SHALL include scan speed, elapsed scan time in the remaining-time field, and current traversal concurrency for the task metrics area
- **AND** starting concrete copy or delete execution SHALL reset the remaining-time field to zero before execution ETA updates take over

#### Scenario: Parallel traversal respects pause and cancel
- **GIVEN** a file operation task is scanning a directory tree
- **WHEN** the task is paused or canceled during traversal
- **THEN** the scan SHALL stop dispatching new directory list requests
- **AND** active remote list requests SHALL be canceled rather than waiting for heartbeat retry or request timeout loops
- **AND** the task SHALL preserve existing pause, cancel, and continue-task behavior

#### Scenario: Device traversal preserves control-route responsiveness
- **GIVEN** a file operation task is scanning a remote device directory tree
- **WHEN** the scan dispatches multiple device path list requests
- **THEN** device heartbeat/control requests SHALL use an isolated control client
- **AND** device/share/link-share path listing services SHALL limit concurrent directory enumeration
- **AND** those app-controlled listing services SHALL dynamically raise or lower their active listing limit based on observed listing performance and runtime memory pressure
- **AND** device/share traversal concurrency SHALL be capped below the service-side directory-list capacity rather than reusing file-transfer chunk concurrency
- **AND** network traversal concurrency SHALL dynamically adapt on the client side while remaining independently capped because the remote directory-list service is not controlled by this app
- **AND** heartbeat, keep-alive, pause, cancel, and disconnect monitors SHALL NOT run on the same bulk worker dispatcher used by traversal and transfer workers
- **AND** traversal pressure SHALL NOT intentionally suppress heartbeat retry diagnostics

### Requirement: Large local and device file copies SHALL persist transfer checkpoints
The system SHALL persist per-file transfer checkpoints for local/device file copy entries whose size is greater than or equal to `MAX_LENGTH * 30`.

#### Scenario: Continue large file copy from completed chunks
- **GIVEN** a local or device file copy entry is at least `MAX_LENGTH * 30` bytes
- **AND** some chunks have already been written successfully
- **WHEN** the task fails and the user later chooses `继续任务`
- **THEN** the system SHALL continue copying from the first unfinished chunk
- **AND** the file SHALL NOT be copied from byte offset `0`

#### Scenario: Small file copy keeps file-level retry behavior
- **GIVEN** a file copy entry is smaller than `MAX_LENGTH * 30` bytes
- **WHEN** the task fails and the user later chooses `继续任务`
- **THEN** the system SHALL retry that file entry from the beginning
- **AND** it SHALL NOT create a per-file transfer checkpoint

#### Scenario: Invalid transfer checkpoint resets current file
- **GIVEN** a large file copy has a persisted transfer checkpoint
- **AND** the target file is missing or incompatible with that checkpoint
- **WHEN** the user chooses `继续任务`
- **THEN** the system SHALL discard the invalid transfer checkpoint
- **AND** the current file entry SHALL restart from byte offset `0`

### Requirement: Failed or interrupted file operation tasks SHALL expose continue entry points
The system SHALL expose a `继续任务` action when a task still has persisted manifest/checkpoint state indicating unfinished entries.

#### Scenario: Continue task after restart
- **GIVEN** the application exits while a file task still has unfinished manifest entries
- **WHEN** the task list is restored after restart
- **THEN** the interrupted task SHALL remain visible
- **AND** the task SHALL be marked as manually recoverable instead of auto-running
- **AND** the UI SHALL expose `继续任务`

#### Scenario: Cancel task removes runtime recovery files
- **GIVEN** a file task has persisted manifest/checkpoint files
- **WHEN** the user actively cancels the task
- **THEN** the system SHALL delete the persisted manifest/checkpoint files
- **AND** the same task SHALL NOT expose `继续任务`

### Requirement: File operation tasks SHALL expose current runtime metrics
The system SHALL display current speed, estimated remaining time, and active parallel item count for running or paused file operation tasks. Copy and move copy phases SHALL use byte-based speed and remaining-time estimates when byte totals are available. Delete tasks and move delete-source phases SHALL use item-based speed and remaining-time estimates. Missing metric data SHALL be displayed as an empty or placeholder value.

#### Scenario: Copy task shows byte metrics
- **GIVEN** a copy task is transferring file bytes
- **WHEN** progress is reported
- **THEN** the task UI shows byte speed and estimated remaining time
- **AND** the active parallel count reflects currently executing item results

#### Scenario: Delete task shows item metrics
- **GIVEN** a delete task is processing items
- **WHEN** item progress advances
- **THEN** the task UI shows item speed and estimated remaining time
- **AND** it does not require byte totals

#### Scenario: Missing runtime metrics
- **GIVEN** a running or restored task lacks the new runtime metric values
- **WHEN** the task UI is displayed
- **THEN** the UI shows placeholders or omits the unavailable metric values

### Requirement: Running task results SHALL show the latest active item
The system SHALL show at most the latest currently executing item result for running or paused file operation tasks. Queued runtime entries SHALL NOT be included in task dialog or task result screen result lists.

#### Scenario: Running task has active and queued items
- **GIVEN** a running task has active item messages and pending queue entries
- **WHEN** the task result display is loaded
- **THEN** the latest active item message is returned
- **AND** queued items are not displayed as result rows

#### Scenario: Failed task result details remain available
- **GIVEN** a task has failed item results
- **WHEN** the task result display is loaded after failure
- **THEN** failed item details remain visible

### Requirement: Copy and move manifests exclude ignored source entries
The system SHALL exclude source entries matched by enabled source-side ignore rules from persisted copy and move execution manifests.

#### Scenario: Copy manifest omits ignored entries
- **GIVEN** a copy task source tree contains entries matched by enabled source-side ignore rules
- **WHEN** the task manifest is persisted
- **THEN** ignored source entries SHALL NOT appear in the persisted copy queue
- **AND** ignored source entries SHALL NOT contribute to byte totals, item totals, retry metadata, or continue-task pending entries

#### Scenario: Move manifest omits ignored entries from copy and delete-source stages
- **GIVEN** a move task source tree contains entries matched by enabled source-side ignore rules
- **WHEN** the task manifest is persisted
- **THEN** ignored source entries SHALL NOT appear in the copy queue
- **AND** ignored source entries SHALL NOT appear in the delete-source queue

#### Scenario: Manifest omits enabled ignore files
- **GIVEN** a copy or move task source tree contains an enabled ignore file such as `.gitignore`
- **WHEN** the task manifest is persisted
- **THEN** that enabled ignore file SHALL NOT appear in the copy queue
- **AND** that enabled ignore file SHALL NOT appear in the delete-source queue for move tasks

#### Scenario: All selected entries are skipped
- **GIVEN** every selected source entry matches enabled source-side ignore rules
- **WHEN** the copy or move task runs
- **THEN** the task SHALL complete without copying, moving, or deleting those entries
- **AND** the task SHALL NOT expose skipped entries through `重试失败项`

### Requirement: File operations SHALL preserve task semantics with adaptive HTTP transfers
File operation tasks SHALL use adaptive HTTP transfer plans without changing existing pause, cancel, retry, checkpoint, result reporting, or manifest ordering semantics.

#### Scenario: Pause and cancel still control adaptive transfer
- **GIVEN** a copy or move task is transferring a device-direct file through adaptive HTTP ranges
- **WHEN** the user pauses or cancels the task
- **THEN** the task SHALL stop scheduling new ranges
- **AND** active tracked HTTP requests SHALL be canceled or allowed to finish according to existing request cancellation behavior
- **AND** the task SHALL report the same pause or cancellation state used by non-adaptive transfers.

#### Scenario: Checkpoints remain range-aware
- **GIVEN** a large file copy uses adaptive stream or byte ranges
- **WHEN** part of the file has been written successfully and the task is interrupted
- **THEN** the task checkpoint SHALL record completed progress using the existing transfer checkpoint model
- **AND** continuing the task SHALL resume from unfinished ranges rather than restarting completed data.

#### Scenario: Retry metadata remains item-based
- **GIVEN** an adaptive HTTP transfer fails for one file entry
- **WHEN** the task records failure metadata
- **THEN** retry information SHALL remain associated with the failed file operation item
- **AND** successful sibling entries SHALL NOT be retried.

### Requirement: File operation tasks SHALL preserve item semantics during archive transfer
File operation tasks SHALL treat archive streams as an internal transfer optimization and SHALL preserve item-level progress, pause/cancel, retry, and fallback behavior.

#### Scenario: Archive batch advances item progress
- **GIVEN** a copy task is transferring an archive batch
- **WHEN** a file or directory entry is successfully extracted or written
- **THEN** task item progress SHALL advance for that entry
- **AND** byte metrics SHALL advance by the file payload bytes written.

#### Scenario: Pause and cancel stop archive scheduling
- **GIVEN** a copy task is transferring or about to transfer an archive batch
- **WHEN** the user pauses or cancels the task
- **THEN** the task SHALL stop scheduling new archive batches
- **AND** active archive requests SHALL be canceled or allowed to finish according to existing request cancellation behavior
- **AND** the task SHALL report the same pause or cancellation state used by non-archive transfers.

#### Scenario: Failed archive entries remain retryable
- **GIVEN** an archive batch fails after some entries completed
- **WHEN** the task records failure metadata
- **THEN** entries confirmed complete SHALL remain successful
- **AND** entries not confirmed complete SHALL keep item-level retry metadata
- **AND** retrying failed items SHALL NOT require replaying successful sibling entries.

#### Scenario: Device-to-device remains on existing pipeline
- **GIVEN** a Device-to-Device folder copy contains many small files
- **WHEN** archive relay is not implemented for that copy direction
- **THEN** the task SHALL continue using the existing bounded device transfer pipeline
- **AND** existing pause, cancel, retry, checkpoint, and progress behavior SHALL remain unchanged.

### Requirement: 文件任务必须保持源根的符号链接边界

属性统计、复制、移动和删除任务在生成清单、检查点及执行实际 IO 时 MUST 使用同一套不跟随符号链接语义。任务不得通过源根内的链接访问根外目标，删除任务不得删除链接目标内容。

#### Scenario: 属性统计遇到外部目录链接

- **WHEN** 属性任务统计的目录包含指向源根外大型目录的链接
- **THEN** 清单和汇总不包含该目标的后代或大小
- **AND** 任务仍能正常完成

#### Scenario: 复制或移动任务遇到目录链接

- **WHEN** 复制或移动任务的源树包含目录符号链接
- **THEN** 任务不递归复制或移动链接目标内容
- **AND** 清单、检查点和实际执行对该条目的处理保持一致

#### Scenario: 删除任务遇到目录链接

- **WHEN** 删除任务处理一个链接本身或包含链接的目录树
- **THEN** 任务只删除链接目录项而不进入目标
- **AND** 重试或从检查点恢复时仍保持相同语义

#### Scenario: 远端不声明链接安全能力

- **WHEN** 文件任务需要递归远端目录，而对端未声明支持符号链接元数据或安全服务端遍历
- **THEN** 任务在进入无法确认的子目录前失败并给出可诊断错误
- **AND** 不以缺省的“非链接”值继续递归

### Requirement: MCP SHALL control existing file operation tasks
The system SHALL expose existing copy, move, and delete tasks through authenticated MCP list, detail, pause, resume, cancel, and delete tools. MCP operations SHALL invoke the same `TaskState` transitions, scheduler signals, recovery data, and validation rules as the application UI and SHALL NOT create a parallel task state machine.

#### Scenario: MCP lists tasks
- **WHEN** a Token with `tasks.read` lists file operation tasks
- **THEN** the response is derived from the same task snapshots shown by the application
- **AND** includes status, progress, runtime metrics, failures, and currently valid control actions

#### Scenario: MCP cancels a task
- **WHEN** a Token with `tasks.control` cancels a running or paused task
- **THEN** the existing scheduler cancellation signal is used
- **AND** runtime cleanup and terminal status follow the same behavior as UI cancellation

#### Scenario: MCP attempts an invalid transition
- **WHEN** an MCP caller pauses a terminal task or resumes a task that is not paused or recoverable
- **THEN** the tool returns a structured invalid-state error
- **AND** the stored task is unchanged

