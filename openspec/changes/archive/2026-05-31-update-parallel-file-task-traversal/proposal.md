# Change: Parallelize file task traversal

## Why
Copy, move, and delete tasks currently build their execution manifests by recursively traversing one directory at a time. Large directory trees and remote endpoints spend too long in the scanning phase before useful work can start.

## What Changes
- Generate file operation manifests with endpoint-adaptive, bounded parallel directory traversal.
- Cover local, device, share, and network sources for copy, move, and delete scans.
- Keep task execution order, runtime manifest persistence, retry, pause, cancel, and continue-task behavior unchanged.

## Impact
- Affected specs: `manage-file-operation-tasks`
- Affected code: file task manifest scanning in `FileState`, internal traversal helpers, file task tests
