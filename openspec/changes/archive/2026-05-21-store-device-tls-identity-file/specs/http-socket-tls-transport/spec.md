## ADDED Requirements

### Requirement: File-backed TLS identity storage
The system SHALL persist the local device HTTPS certificate/private-key identity in a dedicated app-private directory instead of app settings.

#### Scenario: Load existing file-backed identity
- **GIVEN** a valid file-backed TLS identity exists in the dedicated identity directory
- **WHEN** the device HTTPS server starts
- **THEN** the system loads the certificate/private-key identity from that file
- **AND** the advertised certificate SHA-256 fingerprint is derived from the loaded certificate

#### Scenario: Create file-backed identity
- **GIVEN** no valid file-backed TLS identity exists
- **WHEN** the device HTTPS server starts
- **THEN** the system generates a new self-signed device certificate/private-key identity
- **AND** stores it in the dedicated identity directory
- **AND** does not read legacy settings-backed TLS identity values

### Requirement: Obfuscated segmented TLS identity file
The system SHALL store the local TLS identity file with an encrypted or obfuscated filesystem-safe file name and SHALL encode the file content as three independently encrypted segments.

#### Scenario: Write segmented identity file
- **WHEN** the system persists a generated TLS identity
- **THEN** the clear text identity file name is not present on disk
- **AND** the file contains three encrypted segments
- **AND** exactly one encrypted segment contains the TLS identity payload
- **AND** the other two encrypted segments contain random filler payloads
- **AND** each segment is encrypted separately

#### Scenario: Randomized layout
- **WHEN** the system writes the TLS identity file
- **THEN** the identity segment position is selected randomly
- **AND** filler payload sizes vary so the total file size is not fixed
