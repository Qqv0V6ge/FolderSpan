# file-analysis Specification

## Purpose
TBD - created by archiving change enhance-large-file-editor. Update Purpose after archive.
## Requirements
### Requirement: File information panel
The system SHALL provide a file information panel containing protocol-qualified path, size, creation and modification times, detected encoding, newline form, current byte offset, current line number and current selection size.

#### Scenario: Open information panel
- **WHEN** the user opens file information
- **THEN** available metadata and live editor position values are displayed without scanning the complete file

#### Scenario: Metadata is unavailable
- **WHEN** a content source cannot provide creation time or another optional field
- **THEN** that field is marked unavailable and the remaining information stays usable

### Requirement: Streaming file hashes
The system SHALL calculate MD5, SHA1, SHA256 and CRC32 on demand using bounded chunks and SHALL expose progress and cancellation.

#### Scenario: Calculate all hashes
- **WHEN** the user requests all supported hashes
- **THEN** one bounded scan updates every selected digest and publishes byte progress

#### Scenario: Cancel hash calculation
- **WHEN** the user cancels before completion
- **THEN** further reads stop and incomplete digest values are not presented as final

#### Scenario: File changes during hashing
- **WHEN** the source snapshot differs before hash completion
- **THEN** calculated values are discarded or marked stale and are not associated with the new source version

### Requirement: Streaming statistics
The system SHALL calculate line count, blank-line count, character frequencies, byte distribution, selected keyword counts and 0x00 byte count in cancellable bounded background tasks.

#### Scenario: Text statistics
- **WHEN** a text file statistics task completes
- **THEN** line, blank-line and decoded character counts correspond to the active encoding and newline interpretation

#### Scenario: Byte statistics
- **WHEN** byte statistics completes
- **THEN** the result contains counts for every encountered byte value including the total number of 0x00 bytes

#### Scenario: Keyword statistics
- **WHEN** the user provides one or more keywords
- **THEN** occurrence totals use the selected search options and are reported per keyword
