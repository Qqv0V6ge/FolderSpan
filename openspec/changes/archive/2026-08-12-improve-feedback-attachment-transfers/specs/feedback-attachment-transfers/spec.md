## Purpose

Extend feedback tickets with constrained responsive attachment selection, destination-first downloads, safe file saving, and cancellable action-local byte progress.

## ADDED Requirements

### Requirement: Attachment picker applies display and selection constraints
The feedback attachment picker SHALL apply the reusable file selector's display and selection constraints simultaneously.

#### Scenario: Unsupported attachment type is browsed
- **WHEN** the picker encounters a file whose extension is not JPG, JPEG, PNG, WebP, PDF, TXT, or LOG
- **THEN** the display constraint excludes the file
- **AND** directories remain visible and openable for navigation

#### Scenario: Oversized supported attachment is browsed
- **WHEN** a supported attachment is larger than 10 MiB
- **THEN** the file remains visible but cannot enter the selection
- **AND** attempting to select it presents the actual size and maximum allowed size

#### Scenario: Valid attachment is selected
- **WHEN** a supported regular file is no larger than 10 MiB
- **THEN** the user can select and confirm it for upload

### Requirement: Attachment selectors are responsive and full-size
Attachment upload and download-destination selectors SHALL use the reusable full-size dialog shell and responsive List/Grid presentation.

#### Scenario: Selector opens on an expanded window
- **WHEN** an attachment selector opens with at least 600 dp of available width
- **THEN** the dialog uses all available width and height within safe drawing insets
- **AND** file entries use the adaptive Grid without card-style item containers

#### Scenario: Selector opens on a compact window
- **WHEN** an attachment selector opens with less than 600 dp of available width
- **THEN** it behaves as a full-screen single-column List selector
- **AND** title, navigation, filters, cancel, and confirm actions remain reachable while entries scroll

### Requirement: Attachment validation remains layered
The system SHALL revalidate declared size, actual byte length, supported extension, and upload limits after selector acceptance and before issuing the upload request.

#### Scenario: Metadata size differs from bytes read
- **WHEN** picker metadata reports an allowed size but reading produces more than 10 MiB
- **THEN** the upload is rejected before a network request is sent

#### Scenario: File type changes after selection
- **WHEN** the selected file no longer has a supported extension at upload time
- **THEN** the upload is rejected before a network request is sent

### Requirement: Upload progress is visible near attachments
During attachment upload, the detail page SHALL show the file name, current phase, transferred bytes, total bytes, percentage when calculable, and cancellation action inside the attachment section.

#### Scenario: Upload starts from the attachment section
- **WHEN** the user confirms a valid attachment
- **THEN** upload progress is visible without scrolling to the top of ticket detail
- **AND** progress updates as bytes are sent

#### Scenario: Upload completes
- **WHEN** all bytes are accepted and the ticket refresh succeeds
- **THEN** the transfer reports completion
- **AND** the uploaded attachment appears in the attachment list

#### Scenario: Upload is cancelled
- **WHEN** the user cancels an active upload
- **THEN** the network operation stops
- **AND** the attachment controls return to an actionable state without a false attachment entry

### Requirement: Download destination is selected before transfer
The system SHALL obtain a writable destination folder before issuing an attachment download request.

#### Scenario: Destination selection is cancelled
- **WHEN** the user cancels the destination selector
- **THEN** no attachment download request is issued
- **AND** the attachment row remains idle

#### Scenario: Destination is confirmed
- **WHEN** the user confirms a writable destination folder
- **THEN** the download request starts for that destination
- **AND** the final name uses a safe response filename when available or the sanitized attachment metadata name otherwise

### Requirement: Download progress is associated with its attachment
During attachment download and save, the affected attachment row SHALL show the current phase, transferred bytes, total bytes when known, percentage when calculable, and cancellation action.

#### Scenario: Download is transferring
- **WHEN** response bytes are being received
- **THEN** the corresponding attachment row replaces its idle download action with live progress
- **AND** unrelated attachment rows do not display that progress

#### Scenario: Download enters save phase
- **WHEN** all response bytes have been received
- **THEN** the same attachment row reports that the file is being saved
- **AND** progress remains visible until saving completes or fails

#### Scenario: Transfer total is unknown
- **WHEN** the transport cannot determine the total byte count
- **THEN** the row shows transferred bytes with an indeterminate indicator
- **AND** it does not fabricate a percentage

### Requirement: Download save does not expose incomplete files
The system SHALL expose the final destination file only after the complete downloaded payload has been written successfully.

#### Scenario: Download save succeeds
- **WHEN** all response bytes are received and temporary data is saved successfully
- **THEN** the system replaces the final destination file
- **AND** the transfer reports completion

#### Scenario: Download or save is cancelled or fails
- **WHEN** a download or save operation is cancelled or fails
- **THEN** temporary data is removed
- **AND** no incomplete file remains at the final destination

### Requirement: Active transfers remain discoverable while scrolled
An active attachment transfer SHALL remain discoverable when its primary inline progress is outside the viewport without using a card-style container.

#### Scenario: Inline transfer progress scrolls outside the viewport
- **WHEN** an active upload or download row is outside the viewport
- **THEN** a compact flat transfer strip remains visible at the bottom of the detail pane
- **AND** activating the strip returns focus to the affected attachment UI
