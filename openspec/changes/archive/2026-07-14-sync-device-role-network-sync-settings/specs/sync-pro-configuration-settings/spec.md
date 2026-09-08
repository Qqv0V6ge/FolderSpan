# sync-pro-configuration-settings Specification

## Purpose

Define Pro configuration synchronization through the existing settings API.

## ADDED Requirements

### Requirement: Pro configuration snapshots use SettingApiService

The system SHALL synchronize device, role, network, and sync task configuration through `SettingApiService`.
The system SHALL NOT require new remote API endpoints for these configuration families.

#### Scenario: Upload configuration snapshot
- **WHEN** a supported local configuration family changes while the user is signed in
- **THEN** the system saves the corresponding remote setting entry through `SettingApiService`

#### Scenario: Pull configuration snapshots
- **WHEN** Pro settings synchronization runs for a signed-in user
- **THEN** the system lists remote setting entries through `SettingApiService`
- **AND** applies recognized configuration snapshots locally

### Requirement: Sensitive credentials use local encrypted storage values remotely

The system SHALL serialize locally encrypted sensitive credential fields into remote setting values without decrypting and re-encrypting them for sync.
Remote setting values SHALL NOT contain plaintext passwords, private keys, session tokens, secret access keys, or temporary device tokens.

#### Scenario: Network drive with password is uploaded
- **WHEN** a persisted network drive contains a locally encrypted password
- **THEN** the remote snapshot contains the stored encrypted credential value
- **AND** the plaintext password is absent from the serialized remote setting value

#### Scenario: Network drive snapshot is applied
- **WHEN** a pulled snapshot item contains encrypted credential fields
- **THEN** the system stores those encrypted credential fields locally without decrypting them in the sync layer

### Requirement: Runtime-only data remains local

The system SHALL exclude active socket state, temporary tokens, sync run history, failed sync items, and logs from remote configuration snapshots.

#### Scenario: Sync task snapshot is uploaded
- **WHEN** sync task definitions are uploaded
- **THEN** sync run history and per-item failure records are not included

#### Scenario: Device configuration snapshot is uploaded
- **WHEN** device configuration is uploaded
- **THEN** temporary connection tokens are not included

### Requirement: Snapshot application refreshes local state

The system SHALL refresh affected in-memory state after applying a remote configuration snapshot.

#### Scenario: Network snapshot is applied
- **WHEN** remote persisted network drives are applied locally
- **THEN** the network management state reflects the applied drives

#### Scenario: Role snapshot is applied
- **WHEN** remote roles and permissions are applied locally
- **THEN** role and permission screens reflect the applied data
