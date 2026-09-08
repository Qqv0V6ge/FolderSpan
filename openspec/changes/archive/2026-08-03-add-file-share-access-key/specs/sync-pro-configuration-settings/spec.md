## MODIFIED Requirements

### Requirement: Sensitive credentials use local encrypted storage values remotely

The system SHALL serialize locally encrypted sensitive credential fields into remote setting values without decrypting and re-encrypting them for sync.
Remote setting values SHALL NOT contain plaintext passwords, private keys, session tokens, or temporary device tokens.
As an explicit user-selected exception, the file-share access-key configuration SHALL be synchronized atomically through its whitelisted ordinary-setting entry so devices owned by the same user can use one LAN access key.

#### Scenario: Network drive with password is uploaded
- **WHEN** a persisted network drive contains a locally encrypted password
- **THEN** the remote snapshot contains the stored encrypted credential value
- **AND** the plaintext password is absent from the serialized remote setting value

#### Scenario: Network drive snapshot is applied
- **WHEN** a pulled snapshot item contains encrypted credential fields
- **THEN** the system stores those encrypted credential fields locally without decrypting them in the sync layer

#### Scenario: File-share access-key configuration is uploaded
- **WHEN** the user changes the file-share access-key enable state or value while settings sync is active
- **THEN** the system uploads one whitelisted setting containing both fields
- **AND** it does not upload a partial enable state or partial key value

#### Scenario: Newer remote access-key configuration is applied
- **WHEN** settings sync receives a newer valid ordinary-setting entry for the file-share access-key configuration
- **THEN** the system replaces the local atomic configuration with the remote value
- **AND** refreshes the in-memory file-sharing settings state
