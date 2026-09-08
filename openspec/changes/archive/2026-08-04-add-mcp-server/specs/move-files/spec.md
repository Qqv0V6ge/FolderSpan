## MODIFIED Requirements

### Requirement: Move files and folders
The system SHALL move selected files and folders to a destination path by copying each non-ignored item to its resolved destination and deleting the corresponding source item only after that item copies successfully, using the same conflict-resolution options as copy. Source entries matched by enabled source-side ignore rules SHALL be left in place and SHALL NOT be deleted as part of the move. This behavior SHALL apply to every supported directed combination of Local, Share, Device, and Network endpoints, including MCP-initiated moves. Programmatic callers SHALL select `error`, `skip`, `overwrite`, or `rename`; when omitted the policy SHALL be `error`. A source deletion failure after successful copy SHALL be persisted as a delete-source retry entry and SHALL NOT cause the item to be copied again.

#### Scenario: Move with conflict resolution
- **WHEN** the user initiates Move and selects a destination path
- **THEN** the system prompts for Replace/Jump/Reserve decisions when name conflicts exist
- **AND** each non-ignored item is copied to its resolved destination path
- **AND** the original source item is deleted only after its copy succeeds
- **AND** the move task reports success or failure for each executable item

#### Scenario: Programmatic move uses explicit policy
- **WHEN** an MCP caller starts a move with `error`, `skip`, `overwrite`, or `rename`
- **THEN** every conflict is resolved using that policy without opening UI
- **AND** omitting the policy uses `error`

#### Scenario: Cross-endpoint copy fails
- **WHEN** an item cannot be copied between any supported Local, Share, Device, or Network endpoints
- **THEN** the corresponding source item is not deleted
- **AND** sibling items may continue and report individual results

#### Scenario: Source deletion fails after copy
- **WHEN** an item reaches the target successfully but its source deletion fails
- **THEN** both copies remain present and the task reports a delete-source failure
- **AND** retry resumes at delete-source without repeating the copy

#### Scenario: Move leaves ignored descendants in source
- **WHEN** the user moves a directory that contains entries matched by enabled source-side ignore rules
- **THEN** matching ignored entries SHALL NOT be copied to the destination
- **AND** matching ignored entries SHALL remain at the source
- **AND** source directories containing remaining ignored entries SHALL NOT be deleted
