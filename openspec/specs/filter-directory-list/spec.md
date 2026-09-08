# filter-directory-list Specification

## Purpose
TBD - created by archiving change add-ignore-file-filtering. Update Purpose after archive.
## Requirements
### Requirement: Ignore file preferences
The system SHALL store enabled ignore file names per `protocol`, `protocolId`, and path preference.

#### Scenario: Toggle ignore file
- **WHEN** a user enables or disables an ignore file for the current path
- **THEN** the preference persists the updated ignore file list
- **AND** existing sort and hidden-file settings remain unchanged

### Requirement: Nearest ignore configuration wins
The system SHALL resolve ignore behavior by checking the current path and each parent path until it finds the nearest preference with a non-empty ignore file list.

#### Scenario: Parent configuration is shadowed
- **WHEN** both `/a` and `/a/b` have ignore file lists
- **AND** the user browses `/a/b/c`
- **THEN** only `/a/b` ignore files are used

#### Scenario: Empty configuration disables ignore filtering
- **WHEN** no current or parent preference has a non-empty ignore file list
- **THEN** ignore filtering does not apply

### Requirement: Ignore file matching
The system SHALL parse common ignore file rules including blank lines, comments, escaped characters, negation, anchored paths, directory rules, `*`, `?`, and `**`.

#### Scenario: Match relative path
- **WHEN** an enabled ignore file contains rules for paths under the configured directory
- **THEN** files are matched using paths relative to that configured directory
- **AND** later negation rules can re-include earlier ignored paths

### Requirement: Local ignore display
The system SHALL keep ignored entries visible in local UI lists and mark them with lower opacity than hidden files.

#### Scenario: Ignored entry remains visible
- **WHEN** a path matches the current ignore configuration
- **THEN** it remains in the list
- **AND** it is visually marked as ignored regardless of the hidden-file visibility setting

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

