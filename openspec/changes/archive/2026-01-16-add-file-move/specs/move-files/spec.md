## ADDED Requirements

### Requirement: Move files and folders
The system SHALL move selected files and folders to a destination path by copying each item to its resolved destination and deleting the source item only after the copy succeeds, using the same conflict-resolution options as copy.

#### Scenario: Move with conflict resolution
- **WHEN** the user initiates Move and selects a destination path
- **THEN** the system prompts for Replace/Jump/Reserve decisions when name conflicts exist
- **AND** each item is copied to its resolved destination path
- **AND** the original source item is deleted only after its copy succeeds
- **AND** the move task reports success or failure for each item
