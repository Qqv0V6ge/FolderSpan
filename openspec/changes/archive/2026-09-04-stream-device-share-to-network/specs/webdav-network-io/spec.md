## ADDED Requirements

### Requirement: WebDav upload SHALL accept a streaming byte source
The system SHALL support uploading a WebDav file from a sequential byte source as well as from a local path. When the source is a connected device or remote Share, the WebDav PUT SHALL consume bytes as they arrive and SHALL NOT require the complete file to exist locally first.

#### Scenario: Upload a streamed device file over WebDav
- **WHEN** the user copies a file from a connected device to a WebDav directory
- **THEN** the client issues a WebDav PUT
- **AND** the PUT body is written from the incoming source chunks
- **AND** success or failure is logged via LogKit

#### Scenario: Upload a streamed remote share file over WebDav
- **WHEN** the user copies a file from a remote Share desk to a WebDav directory
- **THEN** the client issues a WebDav PUT from the share read stream
- **AND** the complete source file is not written to local sync-stage before the PUT starts

#### Scenario: Local-path WebDav upload still works
- **WHEN** the user copies a local file to a WebDav directory
- **THEN** the client still uploads from the local path
- **AND** success or failure is logged via LogKit
