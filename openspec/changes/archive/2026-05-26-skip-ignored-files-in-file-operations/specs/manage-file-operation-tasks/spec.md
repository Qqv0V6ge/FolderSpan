## ADDED Requirements

### Requirement: Copy and move manifests exclude ignored source entries
The system SHALL exclude source entries matched by enabled source-side ignore rules from persisted copy and move execution manifests.

#### Scenario: Copy manifest omits ignored entries
- **GIVEN** a copy task source tree contains entries matched by enabled source-side ignore rules
- **WHEN** the task manifest is persisted
- **THEN** ignored source entries SHALL NOT appear in the persisted copy queue
- **AND** ignored source entries SHALL NOT contribute to byte totals, item totals, retry metadata, or continue-task pending entries

#### Scenario: Move manifest omits ignored entries from copy and delete-source stages
- **GIVEN** a move task source tree contains entries matched by enabled source-side ignore rules
- **WHEN** the task manifest is persisted
- **THEN** ignored source entries SHALL NOT appear in the copy queue
- **AND** ignored source entries SHALL NOT appear in the delete-source queue

#### Scenario: Manifest omits enabled ignore files
- **GIVEN** a copy or move task source tree contains an enabled ignore file such as `.gitignore`
- **WHEN** the task manifest is persisted
- **THEN** that enabled ignore file SHALL NOT appear in the copy queue
- **AND** that enabled ignore file SHALL NOT appear in the delete-source queue for move tasks

#### Scenario: All selected entries are skipped
- **GIVEN** every selected source entry matches enabled source-side ignore rules
- **WHEN** the copy or move task runs
- **THEN** the task SHALL complete without copying, moving, or deleting those entries
- **AND** the task SHALL NOT expose skipped entries through `重试失败项`
