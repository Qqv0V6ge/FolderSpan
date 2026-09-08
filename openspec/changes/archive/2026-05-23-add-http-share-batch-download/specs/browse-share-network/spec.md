## ADDED Requirements
### Requirement: Link-share browser streamed batch download
The link-share browser page SHALL provide a batch download action that streams the current shared path into a ZIP archive in the browser, using `folder-zip-worker.js` with `fflate.min.js` and a streaming browser save path without buffering the full archive in memory.

#### Scenario: Stream current share path as ZIP
- **GIVEN** the browser has an authorized link-share session
- **AND** the current path contains files or directories
- **WHEN** the user starts streamed batch download
- **THEN** the page recursively requests directory listings using the existing link-share JSON listing contract
- **AND** streams each included file into the ZIP worker under its relative archive path
- **AND** writes ZIP chunks to the browser save stream as they are produced
- **AND** the resulting archive preserves nested directory structure.

#### Scenario: Streamed batch download unavailable
- **GIVEN** the browser cannot use the secure streamed save path, StreamSaver cannot initialize, or the ZIP worker cannot initialize before archive output starts
- **WHEN** the user starts streamed batch download
- **THEN** the page explains that streamed batch download is unavailable
- **AND** keeps the existing script download option available as a fallback.

#### Scenario: Cancel streamed batch download
- **GIVEN** a streamed batch download is running
- **WHEN** the user cancels the download
- **THEN** pending directory requests, file reads, ZIP worker tasks, and save-stream writes are stopped or abandoned
- **AND** the page returns to an idle state without starting another download automatically.

### Requirement: Link-share batch downloads preserve share boundaries
Streamed batch downloads SHALL enforce the same link-share authorization, hidden-file visibility, and Disk share permission boundaries as normal link-share browsing and direct file downloads.

#### Scenario: Hidden or unauthorized entries are excluded
- **GIVEN** the active link-share session is not allowed to view hidden files
- **WHEN** the user starts streamed batch download from a shared directory that contains hidden children
- **THEN** hidden children are not listed into the ZIP traversal
- **AND** direct reads for hidden paths are rejected if requested manually.

#### Scenario: Share permission changes during traversal
- **GIVEN** an entry was visible when traversal began
- **AND** the source Disk becomes unavailable or loses link-share permission before that file is read
- **WHEN** the batch download attempts to read the file
- **THEN** the server rejects the read using the existing link-share response semantics
- **AND** the page reports the batch download failure or partial failure without bypassing the permission check.
