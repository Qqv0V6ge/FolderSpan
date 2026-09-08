## Context

See `proposal.md` for motivation and `specs/feedback-attachment-transfers/spec.md` for observable behavior. This is an additive follow-up to `openspec/changes/archive/2026-08-11-add-feedback-ticket` rather than a rewrite of that archived change.

The existing feedback feature already owns authenticated attachment endpoints, repository/session layers, detail ViewModel state, attachment UI, an app-owned upload selector bridge, and cross-platform saving. Upload selection currently passes only a broad `File` category and performs exact type/size checks after confirmation. The detail screen represents upload/download with a generic operation enum and renders one indeterminate busy item at the beginning of its `LazyColumn`, outside the viewport when users act from the lower attachment section.

Uploads use bounded replayable multipart byte content because request signing and retry cannot accept one-shot request bodies. Downloads read a bounded response into memory before invoking destination selection. The separate `enhance-file-selector` change supplies exact display/selection constraints, responsive List/Grid behavior, and a reusable full-size selector shell.

## Goals / Non-Goals

**Goals:**

- Configure the generic selector capabilities specifically for supported feedback types and the 10 MiB limit while keeping authoritative later validation.
- Select a destination before network download and keep incomplete data away from the final path.
- Carry real, cancellable transfer progress from transport through state to action-local UI.
- Preserve request signing, authenticated routes, cross-platform behavior, and the archived ticket feature's existing user flows.

**Non-Goals:**

- Change the report-service routes, attachment formats, 10 MiB limit, authentication, or ticket workflow.
- Reimplement generic `FileSelector` constraints or responsive layout in this change.
- Stream unbounded attachments or support background transfers.
- Support multiple simultaneous attachment transfers in the first iteration.
- Add content sniffing, malware scanning, or server changes.

## Decisions

### 1. Depend on `enhance-file-selector` and configure both constraint stages

Implementation order requires `enhance-file-selector` first. The upload picker supplies:

- `displayConstraints` with JPG, JPEG, PNG, WebP, PDF, TXT, and LOG extensions so unrelated files are hidden while directories remain navigable;
- `selectionConstraints` with regular-file kind, the same extensions, and `maxSizeBytes = 10 MiB` so oversized supported files remain visible with a typed rejection.

Upload and destination selection use the generic full-size dialog shell. Compact widths keep List; widths at least 600 dp use the flat adaptive Grid.

Duplicating separate feedback-only filtering logic inside the picker was rejected because it would drift from the shared selector's display-before-selection semantics.

### 2. Keep selector constraints outside the trust boundary

Filesystem metadata can be stale or incomplete. The app-owned reader retains the declared-size check, bounded read, and actual-byte-length check. `validateFeedbackUpload` and the repository retain normalized extension and actual-size checks immediately before multipart construction. The server remains authoritative.

This deliberate layering lets the selector provide early guidance without weakening race and provider defenses.

### 3. Model attachment transfer state separately from generic detail operations

Add one optional transfer state to the detail UI state with:

- kind (`Upload` or `Download`);
- attachment UUID where applicable and display filename;
- phase (`SelectingDestination`, `Preparing`, `Transferring`, `Saving`, `Completed`, or `Failed`);
- transferred bytes and optional total bytes;
- destination metadata;
- cancellation availability and localized result/failure information.

The ViewModel owns the active transfer job. A derived busy policy prevents conflicting attachment mutations while preserving attachment identity for row-level rendering. Intermediate progress is rate-limited before entering Compose state; first, final, phase-change, cancellation, and failure updates are always emitted.

Extending the generic operation enum with byte fields was rejected because non-transfer mutations do not have attachment identity, phases, or byte totals.

### 4. Preserve replayable upload content while reporting bytes

Feedback session-service, repository, and API upload contracts accept an optional progress callback. Multipart content remains a bounded replayable byte body so signing and retries can reproduce it. The HTTP send path reports cumulative written bytes and known content length without replacing it with a one-shot stream.

Download reads the response channel in bounded chunks, obtains the total from `Content-Length` when present, rejects data beyond the existing attachment maximum during the read, and reports cumulative payload bytes. Unknown totals remain indeterminate.

### 5. Select the destination before downloading and save through temporary data

Clicking download first opens the full-size folder selector using attachment metadata. Cancelling returns to idle without calling the API. Once a writable folder is confirmed, the ViewModel starts the request with the destination attached to transfer state.

The response filename is sanitized and falls back to the attachment metadata name. Saving writes to a temporary sibling path, reports the `Saving` phase, and replaces the final path only after success. Cancellation or failure removes temporary data.

The former download-then-select order was rejected because it transfers private bytes that the user may decline to save and splits one operation across disconnected states.

### 6. Render progress where the action occurs with a flat fallback

Upload progress appears in the attachment section below its heading/support text. Download and save progress replace the idle download action and add progress text in the affected attachment row. Known totals show bytes and percentage; unknown totals show bytes and an indeterminate indicator. Cancellation stays adjacent to progress.

A compact flat strip overlays the pane bottom only when primary progress is outside the viewport. Activating it brings the attachment section or affected row into view. The strip does not use Card, and attachment transfers no longer rely on the busy item at the beginning of the detail `LazyColumn`. Non-transfer operations may retain an appropriate pane-level busy treatment.

### 7. Test the cross-layer transfer state machine

Selector tests cover supported display filtering, visible oversized rejection, directory traversal, full-size compact/expanded layout, and later validation. ViewModel tests cover destination cancellation before network activity, all phases, monotonic progress, concurrency guards, cancellation, failure, and refresh behavior.

Transport tests cover replayable signed upload content, known and unknown totals, bounded chunk downloads, cancellation, and terminal updates. Saver tests cover filename fallback, temporary cleanup, and final replacement. Compose tests cover inline placement, row association, the off-screen fallback, result messages, and accessibility labels.

## Risks / Trade-offs

- [Selector and feedback changes can be applied out of order] → Declare `enhance-file-selector` as an implementation dependency and complete it before this change's selector integration tasks.
- [Progress instrumentation can make signed multipart content one-shot] → Keep replayable bounded content and add signing/retry regression tests.
- [Frequent byte callbacks can cause excessive recomposition] → Rate-limit intermediate state updates and always deliver phase/terminal events.
- [Some responses omit `Content-Length`] → Show transferred bytes and an indeterminate indicator without fabricating percentage.
- [Download failure can expose corrupt final data] → Write temporary sibling data and replace the final path only after complete success.
- [Inline progress can scroll out of view] → Keep action-local primary progress plus a temporary flat pane-bottom fallback.
- [Cross-platform replace semantics differ] → Encapsulate temporary save/replace behavior behind platform providers and test each supported adapter.

## Migration Plan

1. Complete `enhance-file-selector` so both constraints, responsive layout, and the full-size dialog shell are available.
2. Migrate feedback upload and destination selectors to those shared capabilities while retaining all later validation.
3. Introduce transfer state and destination-first download orchestration without changing server routes.
4. Thread progress and cancellation callbacks through session, repository, API, and save boundaries while preserving replayability and size bounds.
5. Replace attachment transfer reliance on the top busy item with inline UI and the flat fallback strip.
6. Run shared/Pro tests and cross-platform compilation, then smoke-test upload, destination cancellation, download, save, cancellation, and cleanup.

There is no persisted-data or server migration. Rollback restores the existing selector configuration and transfer UI/order while leaving archived feedback ticket behavior, server attachments, and account sessions intact.
