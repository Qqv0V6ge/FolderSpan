# manage-network-drives Specification Delta

## ADDED Requirements

### Requirement: Persisted network drive Pro synchronization

Persisted network drive entries SHALL be included in Pro configuration synchronization when the user is signed in.
Session-only network entries SHALL NOT be uploaded.
Sensitive network credentials SHALL be encrypted before remote storage.

#### Scenario: Persisted network drive is uploaded
- **WHEN** a signed-in user creates or edits a persisted network drive
- **THEN** the network drive snapshot is saved through the Pro settings API

#### Scenario: Session-only network drive is not uploaded
- **WHEN** a network entry is not saved locally
- **THEN** it is excluded from the network drive snapshot

#### Scenario: Network drive is restored
- **WHEN** a signed-in user pulls a valid network drive snapshot
- **THEN** persisted network drives are restored into local network storage
