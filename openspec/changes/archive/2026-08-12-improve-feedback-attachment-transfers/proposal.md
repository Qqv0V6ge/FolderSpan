## Why

This change continues the feedback ticket feature archived as `2026-08-11-add-feedback-ticket`: attachment selection currently validates important rules too late, and upload/download progress is placed at the top of scrollable detail content where it is not visible when users act from the attachment section. Downloads also fetch bytes before destination selection, which wastes work when the user cancels saving.

## What Changes

- Adopt the `enhance-file-selector` display and selection constraints so unsupported attachment types are hidden while oversized supported files remain visible but cannot be selected.
- Use the full-size responsive selector shell supplied by `enhance-file-selector` for attachment upload and download destination selection.
- Retain declared-size, bounded-read, actual-byte-length, extension, repository, and server validation after selector acceptance.
- Track attachment transfer identity, phase, transferred bytes, total bytes when known, cancellation, completion, and failure.
- Show upload progress inside the attachment section and download/save progress in the affected attachment row rather than only at the top of the ticket detail.
- Keep an active off-screen transfer discoverable through a compact flat pane-bottom strip without using a card container.
- Select and confirm a writable destination folder before issuing the download request.
- Save downloads through temporary data and expose the final file only after the complete payload is written successfully.
- Preserve replayable signed multipart uploads while adding real byte-progress callbacks through the transfer layers.

## Capabilities

### New Capabilities

- `feedback-attachment-transfers`: Extends the archived feedback ticket capability with constrained responsive selection, destination-first downloads, safe saving, cancellable byte progress, and action-local transfer presentation.

### Modified Capabilities

None. The original `feedback-ticket-management` change is archived and has no registered main spec, so this follow-up declares a focused additive capability instead of rewriting its historical delta.

## Impact

- Depends on the active `enhance-file-selector` change for `displayConstraints`, `selectionConstraints`, responsive List/Grid presentation, and the full-size selector dialog shell.
- Feedback attachment picker and destination provider code in `app/shared`.
- Feedback detail state, ViewModel, UI, domain interfaces, repository, and Ktor API implementation in `proMain`.
- Cross-platform temporary save/replace behavior and cancellation cleanup.
- Localized strings plus selector, ViewModel, transport, saver, and Compose tests.
- No report-service route, server attachment limit, persisted ticket data, or authentication change.
