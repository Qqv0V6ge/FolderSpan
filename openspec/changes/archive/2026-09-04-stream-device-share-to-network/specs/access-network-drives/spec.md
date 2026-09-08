## ADDED Requirements

### Requirement: Paste from device or remote share SHALL stream to FTP/SFTP/SMB
When a user pastes copied or moved items from a connected device or a remote Share desk into an FTP, SFTP, or SMB directory, the system SHALL upload each file by streaming source bytes to the protocol client. The system SHALL NOT require a complete local staging file for those uploads.

#### Scenario: Paste a device file to SFTP
- **WHEN** the user pastes a copied file from a connected device into an SFTP directory
- **THEN** the file is uploaded to that directory
- **AND** the upload does not wait for a complete local staging copy of the source file

#### Scenario: Paste a remote share file to FTP
- **WHEN** the user pastes a copied file from a remote Share desk into an FTP directory
- **THEN** the file is uploaded to that directory
- **AND** the share is not registered as a Device

#### Scenario: Paste move from device to SMB
- **WHEN** the user pastes a moved file from a connected device into an SMB directory
- **THEN** the file is streamed to that directory
- **AND** the source entry is removed only after a successful upload
