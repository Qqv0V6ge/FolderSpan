## REMOVED Requirements

### Requirement: Served ignore enforcement
**Reason**: Ignore files should remain a browsing/file-operation preference, not a remote-serving access-control mechanism. Device/share serving already has its own authorization rules.

**Migration**: Remove ignore-based filtering and direct-access denial from device/share serving paths. Remote clients should receive ignored entries and access ignored paths whenever normal device/share permissions allow them.

## ADDED Requirements

### Requirement: Ignore file operation traversal
The system SHALL apply enabled source-side ignore file rules when building copy and move operation traversal for `Local`, `Share`, `Device`, and `Network` source protocols.

#### Scenario: Copy skips ignored descendants
- **WHEN** a user copies a directory from a source path that has an enabled ignore configuration
- **THEN** source entries matching that ignore configuration SHALL NOT be copied
- **AND** ignored directories SHALL prevent their descendants from being copied

#### Scenario: Explicitly selected ignored source is skipped
- **WHEN** a user starts copy or move with a selected source entry that matches the enabled source-side ignore configuration
- **THEN** that selected source entry SHALL NOT be copied
- **AND** it SHALL NOT be treated as a failed operation item

#### Scenario: Enabled ignore file is skipped
- **WHEN** a user starts copy or move from a source path that has an enabled ignore file such as `.gitignore`
- **THEN** the enabled ignore file itself SHALL NOT be copied
- **AND** it SHALL NOT be treated as a failed operation item

#### Scenario: Destination ignore does not block writes
- **WHEN** the destination path has an enabled ignore configuration
- **AND** the source path has no matching enabled ignore configuration
- **THEN** copy and move operations SHALL still write the source entry to the destination when normal permissions allow it
