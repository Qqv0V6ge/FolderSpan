# large-file-editor-safety Specification

## Purpose
TBD - created by archiving change enhance-large-file-editor. Update Purpose after archive.
## Requirements
### Requirement: Default read-only access
The system SHALL open every writable file editor session in read-only mode and SHALL require an explicit confirmation before enabling editing.

#### Scenario: Writable file opens safely
- **WHEN** a writable file is opened in the editor
- **THEN** the file is displayed in read-only mode and no editing control can mutate the document

#### Scenario: User unlocks editing
- **WHEN** the user requests edit mode and confirms the modification warning
- **THEN** the session enables only the edit operations allowed by the source and file-size policy

#### Scenario: Unlock is not persisted
- **WHEN** an unlocked editor session is closed and the same file is opened again
- **THEN** the new session starts in read-only mode

### Requirement: Oversized file protection
The system SHALL classify files whose size is at least 1,073,741,824 bytes as oversized and SHALL restrict an unlocked oversized file to bounded page-level text modifications.

#### Scenario: Oversized file accepts bounded text edit
- **WHEN** an oversized writable file is unlocked and the user modifies the loaded text page within its configured bound
- **THEN** the change is recorded in the bounded edit log without loading the complete file

#### Scenario: Oversized text edit exceeds page bound
- **WHEN** a text replacement would exceed the configured bounded-page edit limit
- **THEN** the operation is rejected and the original page remains unchanged

### Requirement: Bounded editor memory
The system SHALL use range reads, bounded page caches and streaming transformations so opening, searching, indexing, hashing, analyzing or saving a file does not require loading the complete file into memory.

#### Scenario: Huge file initial display
- **WHEN** a virtual 10 GiB file is opened
- **THEN** the editor reads only the first bounded page needed for display and keeps the page cache within its configured limit

#### Scenario: Background operation remains bounded
- **WHEN** a whole-file operation processes a file larger than available memory
- **THEN** the operation consumes bounded chunks and releases processed chunks before continuing

### Requirement: Source capability enforcement
The system SHALL expose content-source capabilities for range read, equal-length range write, streamed replacement, save-as and atomic replacement, and SHALL show or execute only operations supported by the active source.

#### Scenario: Read-only share source
- **WHEN** a Share content source reports no write capability
- **THEN** editing, saving and save-over controls remain disabled while viewing and analysis remain available

#### Scenario: Source lacks range write
- **WHEN** an equal-length text change is saved to a source without range-write capability
- **THEN** the editor uses a supported streamed replacement or requires save-as instead of attempting an unsupported write

### Requirement: Save preview
The system SHALL present a save preview before destructive persistence, including changed ranges, byte-count change, selected encoding and newline conversion, backup behavior, and any characters that will be replaced during encoding.

#### Scenario: Encoding replacement is disclosed
- **WHEN** edited text contains characters unavailable in the selected target encoding
- **THEN** the preview shows the replacement count and a bounded sample of affected positions before allowing save

#### Scenario: User cancels preview
- **WHEN** the user dismisses the save preview
- **THEN** no source bytes are written and all pending edits remain available

### Requirement: External modification conflict
The system SHALL compare the current source snapshot with the snapshot captured for the editing session immediately before writing and SHALL abort the save when they differ.

#### Scenario: Source changed externally
- **WHEN** file size, revision, modification metadata or sampled content fingerprint differs at save time
- **THEN** saving stops before overwrite and the user can reload, save as, or explicitly confirm a forced overwrite

#### Scenario: User reloads after conflict
- **WHEN** the user chooses reload after a conflict
- **THEN** the editor discards pending changes only after confirmation and loads a new source snapshot

### Requirement: Recoverable persistence
The system SHALL preserve the original file or sufficient rollback data until a save has completed and been verified, and SHALL retain pending edits after any failed save.

#### Scenario: Streamed replacement succeeds
- **WHEN** a size-changing edit is saved
- **THEN** content is streamed to a temporary target, verified, and atomically replaces or uploads over the source only after successful completion

#### Scenario: Save fails midway
- **WHEN** writing, verification, replacement or upload fails
- **THEN** the original source remains available, rollback data is retained, and the edit session stays dirty for retry or save-as

#### Scenario: Equal-length range write fails
- **WHEN** an in-place text range write fails after modifying any range
- **THEN** the system restores the recorded original bytes or reports a recoverable rollback task before allowing further edits

### Requirement: Backup and save-as
The system SHALL support save-as for writable destinations and SHALL create the configured backup before overwriting the original source.

#### Scenario: Normal file backup
- **WHEN** backup is enabled for a file smaller than 1 GiB and the user confirms overwrite
- **THEN** a complete backup is retained until the save is verified

#### Scenario: Oversized file rollback backup
- **WHEN** an oversized file receives equal-length in-place modifications
- **THEN** the system stores the original bytes for every modified range instead of copying the entire file

#### Scenario: Save as new destination
- **WHEN** the user chooses save-as and selects a supported destination
- **THEN** the edited content is streamed to the destination without replacing the source

### Requirement: Edit history and crash recovery
The system SHALL maintain a bounded modification list with undo and redo and SHALL persist a local recovery journal for dirty sessions.

#### Scenario: Undo and redo
- **WHEN** the user undoes and then redoes an edit
- **THEN** document bytes, dirty state and modification list reflect the corresponding command state

#### Scenario: Application restarts with recovery data
- **WHEN** the application starts after a dirty editor session ended unexpectedly
- **THEN** it offers to restore or discard the recovered edits after validating the source snapshot

#### Scenario: Recovery source no longer matches
- **WHEN** recovery data references a source whose snapshot has changed
- **THEN** automatic overwrite is forbidden and the recovered content can only be reviewed, relocated where safe, or saved as a new file
