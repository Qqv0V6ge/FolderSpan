## ADDED Requirements
### Requirement: Protocol availability
The system SHALL support FTP, SFTP, and SMB network entries on Android, iOS, and JVM desktop.
On JS/WASM platforms, FTP/SFTP/SMB operations SHALL return a not-supported error that can be surfaced by the UI.

#### Scenario: Web operation unsupported
- **WHEN** a JS/WASM user attempts to connect to an FTP entry
- **THEN** the operation fails with a not-supported error

### Requirement: Apply protocol settings
The system SHALL apply stored protocol settings for FTP (port, passive mode, FTPS), SFTP (port, private key, known_hosts), and SMB (port, share name, domain/workgroup) when establishing connections and requests.

#### Scenario: Use custom FTP port
- **WHEN** a user connects to an FTP entry with port 2121
- **THEN** the FTP client uses port 2121

### Requirement: List and read network files
When the current desk is an FTP/SFTP/SMB entry, the system SHALL list directory contents via the corresponding protocol client.
When a user opens or downloads a non-directory file from an FTP/SFTP/SMB entry, the system SHALL download the file bytes via that protocol client.

#### Scenario: List FTP root
- **WHEN** the user opens the root path of an FTP entry
- **THEN** the system requests the FTP directory listing for that path

#### Scenario: Download SMB file
- **WHEN** the user opens a file from an SMB entry
- **THEN** the file bytes are fetched from the SMB server

### Requirement: Write and move network files
When a user pastes copied items into an FTP/SFTP/SMB entry, the system SHALL upload the items to the target path.
When a user pastes moved items into an FTP/SFTP/SMB entry, the system SHALL upload the items and delete the source after a successful upload.

#### Scenario: Paste copy to SFTP
- **WHEN** the user pastes copied files into an SFTP directory
- **THEN** the files are uploaded to that directory

#### Scenario: Paste move to FTP
- **WHEN** the user pastes moved files into an FTP directory
- **THEN** the files are uploaded and the source entries are removed after success

### Requirement: Rename and delete network files
The system SHALL support renaming and deleting files/folders on FTP/SFTP/SMB entries using the protocol clients.

#### Scenario: Rename SMB folder
- **WHEN** the user renames a folder on an SMB entry
- **THEN** the SMB rename operation is executed

#### Scenario: Delete SFTP file
- **WHEN** the user deletes a file on an SFTP entry
- **THEN** the file is removed from the SFTP server
