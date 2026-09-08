## MODIFIED Requirements

### Requirement: Move files and folders
The system SHALL move selected files and folders to a destination path by copying each non-ignored item to its resolved destination and deleting the corresponding source item only after that item copies successfully, using the same conflict-resolution options as copy. Source entries matched by enabled source-side ignore rules SHALL be left in place and SHALL NOT be deleted as part of the move.

#### Scenario: Move with conflict resolution
- **WHEN** the user initiates Move and selects a destination path
- **THEN** the system prompts for Replace/Jump/Reserve decisions when name conflicts exist
- **AND** each non-ignored item is copied to its resolved destination path
- **AND** the original source item is deleted only after its copy succeeds
- **AND** the move task reports success or failure for each executable item

#### Scenario: Move leaves ignored descendants in source
- **WHEN** the user moves a directory that contains entries matched by enabled source-side ignore rules
- **THEN** matching ignored entries SHALL NOT be copied to the destination
- **AND** matching ignored entries SHALL remain at the source
- **AND** source directories containing remaining ignored entries SHALL NOT be deleted
