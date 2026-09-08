# manage-sync-tasks Specification Delta

## ADDED Requirements

### Requirement: Sync task Pro synchronization

Sync task definitions SHALL be included in Pro configuration synchronization when the user is signed in.
Sync run history, per-item results, runtime queues, checkpoints, and logs SHALL remain local-only.

#### Scenario: Sync task definition is uploaded
- **WHEN** a signed-in user creates, edits, deletes, pauses, or resumes a sync task
- **THEN** the sync task definition snapshot is saved through the Pro settings API

#### Scenario: Sync task definition is restored
- **WHEN** a signed-in user pulls a valid sync task snapshot
- **THEN** sync task definitions are restored into the local sync task store

#### Scenario: Sync run history is not restored
- **WHEN** a sync task snapshot is applied
- **THEN** existing remote run history is not applied because it is not part of the snapshot
