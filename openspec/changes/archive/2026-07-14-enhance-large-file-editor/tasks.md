## 1. Editor Contracts and Safety Tests

- [x] 1.1 Add common test fixtures for virtual multi-gigabyte content sources, bounded read recording, source revisions, range writes, streamed replacements and injected failures.
- [x] 1.2 Add source-contract tests for capability enforcement, snapshot comparison, cancellation, atomic replacement and save-as behavior.
- [x] 1.3 Add editor-state tests proving every writable source opens read-only, unlock requires confirmation, and unlock state is not persisted.
- [x] 1.4 Add oversized-file tests for the exact 1 GiB threshold and bounded page-level text editing.
- [x] 1.5 Extend `FileEditorContentSource` with `FileEditorSourceCapabilities`, `FileEditorSourceSnapshot`, version-checked range write, streamed replacement and save-as operations.
- [x] 1.6 Adapt Local, Device, Share and Network editor sources to the new contract, keeping Share read-only and reporting unsupported capabilities explicitly.
- [x] 1.7 Add a per-session background task coordinator that prioritizes interactive reads and cancels prefetch, search, index and analysis jobs on close.

## 2. Phase One Text Codec and Presentation

- [x] 2.1 Add codec conformance tests for ASCII, UTF-8, UTF-16 LE/BE, GBK and ISO-8859-1 decode/encode, BOM handling and invalid input on every supported target.
- [x] 2.2 Add compressed GBK mapping resources and a deterministic commonMain `EditorTextCodec` implementation for all supported encodings.
- [x] 2.3 Implement bounded encoding detection with BOM, ASCII, strict UTF-8, UTF-16 and GBK heuristics plus ISO-8859-1 fallback, confidence and BOM state.
- [x] 2.4 Replace UTF-8-specific page boundary handling with encoding-aware incremental decoding that preserves characters split across pages.
- [x] 2.5 Implement cross-chunk CRLF/LF/CR/mixed newline detection and preserve original newline bytes unless conversion is explicitly selected.
- [x] 2.6 Add encoding and newline selectors to the text editor, including low-confidence feedback and deferred conversion state.
- [x] 2.7 Add line-number gutter, automatic-wrap toggle and non-mutating space, Tab and newline visualization.
- [x] 2.8 Keep one text presentation and map caret and selection through absolute byte offsets.
- [x] 2.9 Persist line-number visibility and automatic-wrap preferences locally and restore them for later editor sessions.

## 3. Phase One Paging, Line Index and Navigation

- [x] 3.1 Add tests proving a virtual 10 GiB file initially reads one 32 KiB page, retains at most the configured cache pages and never aggregates the full file.
- [x] 3.2 Refactor page loading into an editor paging service with bounded LRU caching and cancellable previous/next page prefetch.
- [x] 3.3 Define the application-private sparse line-index format keyed by source fingerprint, with checkpoints every 4096 lines.
- [x] 3.4 Implement background incremental line indexing with progress, cancellation, stale-index rejection and private-cache cleanup.
- [x] 3.5 Implement line-to-byte and byte-to-line lookup that can prioritize indexing toward a requested line without blocking current-page display.
- [x] 3.6 Add jump controls for line number, absolute byte offset and percentage with validation and containing-page loading.
- [x] 3.7 Add UI state and accessibility feedback for provisional line numbers, indexing progress and navigation completion/failure.

## 4. Phase One Search and Result Navigation

- [x] 4.1 Add search-engine tests for text and regex matches within and across chunks, including UTF-16/GBK byte-offset mapping.
- [x] 4.2 Add tests for case sensitivity, whole words, forward/backward direction, page/selection/file ranges and multiple overlapping keywords.
- [x] 4.3 Implement `LargeFileSearchEngine` with bounded range reads, cross-block exact matching, cancellable progress and batched result delivery.
- [x] 4.4 Implement bounded-window regex search with duplicate suppression, a 1 MiB individual-match limit and explicit limit errors.
- [x] 4.5 Implement multi-keyword Any matching with per-result keyword tags and shared scanning where query modes permit it.
- [x] 4.6 Add a bounded search result store that retains at most 10,000 detailed results while continuing total counts and progress.
- [x] 4.7 Replace the current-page search bar with advanced search controls and a result panel supporting previous/next and direct result jumps.
- [x] 4.8 Add an independent editor-search history store with duplicate removal, a 50-entry limit and a new synchronized snapshot key/category.

## 5. Phase One Text-Only Cleanup

- [x] 5.1 Remove the raw-byte editor renderer and its responsive layout branches.
- [x] 5.2 Remove byte grouping, endianness and selection interpretation state.
- [x] 5.3 Remove raw-byte replacement, insertion and deletion commands.
- [x] 5.4 Remove raw-byte search parsing and result matching.
- [x] 5.5 Remove raw-byte editor models, temporary segment storage and their tests.
- [x] 5.6 Keep text selection, search highlighting and page navigation independent of focus changes.

## 6. Phase One File Information and Acceptance

- [x] 6.1 Add a file information model and panel for protocol-qualified path, size, timestamps, encoding, newline, offset, line and selection size.
- [x] 6.2 Implement cancellable single-pass MD5, SHA1, SHA256 and CRC32 calculation with snapshot validation and progress.
- [x] 6.3 Add common tests for hash correctness, cancellation, bounded reads and rejection of results after source changes.
- [x] 6.4 Run core/shared tests and compile Android, Desktop, iOS, JS and Wasm targets; fix all Phase One regressions before beginning Phase Two.

## 7. Phase Two Safe Editing and Recovery

- [x] 7.1 Add command-log tests for modification listing, undo, redo, reverting to clean state and bounded storage across pages.
- [x] 7.2 Implement bounded edit commands and session dirty-state tracking for text replacements.
- [x] 7.3 Add save-preview models and UI listing changed ranges, size delta, encoding/newline conversion, backup behavior and replacement-character samples.
- [x] 7.4 Implement strict pre-save source snapshot validation and conflict actions for reload, save-as and explicitly confirmed forced overwrite.
- [x] 7.5 Implement equal-length range save with original-byte rollback logging when the source advertises range-write capability.
- [x] 7.6 Implement temporary-target streamed save, verification and atomic replacement/upload for all other modifications.
- [x] 7.7 Implement complete backups below 1 GiB, modified-range backups at or above 1 GiB, retention cleanup and estimated-space feedback.
- [x] 7.8 Implement protocol-aware save-as that streams the edited document to a selected supported destination without replacing the source.
- [x] 7.9 Add an application-private crash-recovery journal, startup restore/discard flow and changed-source safeguards.
- [x] 7.10 Add failure-injection tests proving interrupted save, verification, replacement, upload and rollback preserve the original and pending edits.

## 9. Phase Two Statistics and Acceptance

- [x] 9.1 Implement cancellable streaming line count, blank-line count and decoded character-frequency statistics using the active encoding/newline interpretation.
- [x] 9.2 Implement cancellable byte-distribution and 0x00 count in the same bounded byte scan.
- [x] 9.3 Reuse search semantics to calculate per-keyword occurrence totals and expose progress and stale-snapshot handling.
- [x] 9.4 Add statistics UI with start, cancel, progress, partial-state and completed-result presentation.
- [x] 9.5 Run core/shared tests, SQLDelight migration verification and all platform compiles; fix all Phase Two regressions.

## 10. Adjacent Page Prefetch

- [x] 10.1 Start cancellable previous/next page prefetch after a page becomes visible.
- [x] 10.2 Reuse prefetched pages during vertical page navigation instead of reading the same page again.
- [x] 10.3 Keep current, previous and next pages inside the existing bounded LRU cache.
- [x] 10.4 Cancel lower-priority prefetch when an interactive read or session close takes priority.
- [x] 10.5 Add paging-service and document-level tests for bounded adjacent-page prefetch.
- [x] 10.6 Add top and bottom pull indicators whose progress follows boundary drag or desktop wheel input, shows release-to-switch guidance at the threshold, triggers navigation only on release or wheel idle, resets below the threshold, and keeps loading visible until the actual page request completes or fails.
- [x] 10.7 Add tests for pull progress, threshold guidance, release-triggered navigation, wheel boundaries, below-threshold reset, real loading completion/failure and unavailable boundaries, plus a Compose preview.

## 11. Removed Scope Cleanup

- [x] 11.1 Remove the editor comparison UI, comparison engines, split-view state and related tests.
- [x] 11.2 Remove the editor location bookmark UI, domain, SQLDelight table, synchronization key and related tests without changing existing file bookmarks.
- [x] 11.3 Remove stale document state, constructor dependencies, task kinds and navigation references left by both removed features.

## 12. Final Verification and Cleanup

- [x] 12.1 Add cache lifecycle tests and cleanup policies for stale line indexes, recovery journals, backups and remote editor caches.
- [x] 12.2 Run `./gradlew :core:jvmTest` and `./gradlew :app:shared:jvmTest` with large virtual-source and failure-injection suites enabled.
- [x] 12.3 Compile Android, Desktop, iOS, JS and Wasm targets and smoke-test editor startup, search cancellation and save recovery where runnable.
- [x] 12.4 Verify source scans contain no raw-byte editor, editor comparison or editor location bookmark implementation while existing file bookmarks remain unchanged.
- [x] 12.5 Run `openspec validate enhance-large-file-editor --strict` and update `md_descriptions_paths.md` for Markdown documentation changed during implementation.
