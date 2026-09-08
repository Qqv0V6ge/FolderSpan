## ADDED Requirements
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

### Requirement: Served ignore enforcement
The system SHALL filter ignored entries from device/share serving list responses and reject direct access to ignored paths.

#### Scenario: Ignored child is filtered
- **WHEN** a remote client lists a directory served by this device
- **THEN** ignored children are omitted from the response

#### Scenario: Ignored path direct access is denied
- **WHEN** a remote client reads or looks up a path matched by this device's ignore configuration
- **THEN** the request is rejected with an authorization failure
