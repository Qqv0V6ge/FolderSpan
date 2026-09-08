## 1. FileSelector Dependency and Picker Integration

- [x] 1.1 Complete or verify the `enhance-file-selector` constraints, responsive List/Grid, and full-size dialog shell before integrating this change.
- [x] 1.2 Configure feedback upload `displayConstraints` with supported extensions and `selectionConstraints` with regular-file kind, supported extensions, and the 10 MiB maximum.
- [x] 1.3 Migrate feedback upload and download-destination selectors to the full-size selector shell.
- [x] 1.4 Add Compose tests for hidden unsupported types, visible rejected oversized files, directory navigation, compact List, expanded flat Grid, and persistent dialog actions.

## 2. Layered Upload Validation

- [x] 2.1 Retain and test the selected file's declared-size check before reading.
- [x] 2.2 Retain bounded reading and recheck actual byte length after reading.
- [x] 2.3 Retain normalized extension and actual-size checks in upload validation and the repository before request construction.
- [x] 2.4 Add regression tests proving selector acceptance cannot bypass changed type, changed size, or oversized actual bytes.

## 3. Attachment Transfer State

- [x] 3.1 Add transfer kind, attachment identity, filename, phase, byte counts, destination, cancellation, completion, and failure data to detail presentation state.
- [x] 3.2 Refactor the ViewModel to own one attachment transfer job and prevent conflicting attachment mutations.
- [x] 3.3 Publish rate-limited monotonic intermediate progress while always delivering initial, phase-change, cancellation, failure, and completion states.
- [x] 3.4 Add ViewModel tests for every phase, concurrency guard, cancellation, failure, completion, and post-upload refresh.

## 4. Destination-First Download and Safe Save

- [x] 4.1 Refactor the download action to open destination selection before issuing the network request.
- [x] 4.2 Verify cancelling or dismissing destination selection performs no download request and restores idle state.
- [x] 4.3 Carry the confirmed destination through the download transfer state and apply safe response-name or metadata-name fallback.
- [x] 4.4 Save through temporary sibling data, publish the saving phase, replace the final file only after success, and clean temporary data on cancellation or failure.
- [x] 4.5 Add cross-platform saver tests for filename fallback, successful replacement, cancellation, failure, and temporary cleanup.

## 5. Transport Progress and Cancellation

- [x] 5.1 Thread optional progress callbacks through feedback session-service, repository, and API upload/download contracts without changing server routes.
- [x] 5.2 Instrument replayable multipart uploads with cumulative byte progress while preserving request signing and retry replayability.
- [x] 5.3 Replace all-at-once download collection with cancellable bounded chunk reads that report cumulative bytes and enforce the attachment limit.
- [x] 5.4 Report known totals from `Content-Length`, preserve unknown totals, and emit terminal progress consistently.
- [x] 5.5 Add transport tests for signed replayable uploads, known/unknown totals, monotonic updates, bounded failure, cancellation, and terminal events.

## 6. Action-Local Transfer UI

- [x] 6.1 Render upload filename, phase, bytes, percentage or indeterminate state, and cancel action inside the attachment section.
- [x] 6.2 Render download and save progress in the affected attachment row while unrelated rows remain idle.
- [x] 6.3 Add a compact flat pane-bottom transfer strip when primary inline progress is outside the viewport and bring the affected UI into view when activated.
- [x] 6.4 Remove attachment upload/download reliance on the first `LazyColumn` busy item while preserving appropriate non-transfer operation feedback.
- [x] 6.5 Add Compose tests for inline placement, row association, off-screen fallback, cancellation, completion/failure messages, keyboard behavior, and screen-reader labels.

## 7. Verification

- [x] 7.1 Run shared and Pro JVM unit/Compose tests for picker, ViewModel, repository, API, saver, and detail UI behavior.
- [x] 7.2 Compile affected common, Desktop/JVM, Android, JS/Wasm, and iOS source sets to catch cross-platform API drift.
  - Verified Desktop/JVM, Android, JS, and Wasm compilation.
  - Accepted limitation: common metadata compilation is blocked by the pre-existing `DefaultAppLanguagePlatform.preferredLanguageTags()` implementation, while iOS cinterop compilation requires unavailable macOS native headers and toolchains.
- [x] 7.3 Manually verify supported/unsupported/oversized selection, responsive full-size selectors, upload progress, destination cancellation, download/save progress, cancellation, cleanup, and final file integrity.
