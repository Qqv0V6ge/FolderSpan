## Purpose

Define reusable display and selection constraints so file-selection experiences can expose only relevant entries while independently controlling which visible entries users may select.

## ADDED Requirements

### Requirement: Independent display and selection constraints
The system SHALL allow a file selector caller to provide an optional display constraint and an optional selection constraint, and SHALL allow both constraints to be active at the same time.

#### Scenario: Display constraint only
- **WHEN** a caller supplies only a display constraint
- **THEN** entries that satisfy the display constraint are shown
- **AND** every shown entry remains subject to the selector's existing selection behavior

#### Scenario: Selection constraint only
- **WHEN** a caller supplies only a selection constraint
- **THEN** entries remain visible according to the selector's existing display behavior
- **AND** only entries that satisfy the selection constraint can be selected

#### Scenario: Both constraints
- **WHEN** a caller supplies both constraints
- **THEN** the display constraint is evaluated first
- **AND** the selection constraint is evaluated only for entries that remain visible

### Requirement: File type and size rules
Each constraint SHALL support file-versus-directory kind rules, a case-insensitive allowlist of normalized file extensions, and optional minimum and maximum file sizes in bytes.

#### Scenario: Extension matches regardless of case
- **WHEN** a constraint allows `jpg` and an entry has the extension `.JPG`
- **THEN** the extension rule is satisfied

#### Scenario: File exceeds maximum size
- **WHEN** a visible file is larger than the selection constraint's maximum size
- **THEN** the file cannot be selected
- **AND** the rejection identifies the configured maximum size

#### Scenario: File size is unavailable
- **WHEN** a size rule is configured and a file's size cannot be determined
- **THEN** the size rule is not satisfied
- **AND** a selection rejection identifies that the file size is unavailable

#### Scenario: Multiple rules are configured
- **WHEN** a constraint contains kind, extension, and size rules
- **THEN** an entry satisfies the constraint only when it satisfies every configured rule

### Requirement: Display constraints control visibility
The selector SHALL exclude file entries that fail the active display constraint without treating the excluded entries as selection errors.

#### Scenario: Unsupported type is hidden
- **WHEN** the display constraint allows a set of file extensions
- **AND** a file has an extension outside that set
- **THEN** the file is not displayed

#### Scenario: Display constraint changes
- **WHEN** the caller changes the active display constraint
- **THEN** the visible entries are recomputed from the current directory contents
- **AND** entries newly excluded by the constraint are no longer shown

### Requirement: Selection constraints explain rejection
The selector SHALL keep entries that fail only the selection constraint visible, SHALL prevent them from entering the selection, and SHALL provide a localized reason when the user attempts to select them.

#### Scenario: Oversized visible file is rejected
- **WHEN** a visible file exceeds the selection constraint's maximum size
- **AND** the user attempts to select it
- **THEN** the file remains unselected
- **AND** the selector presents a message containing the file's size and the maximum allowed size

#### Scenario: Unsupported visible file is rejected
- **WHEN** a visible file has an extension outside the selection constraint's allowlist
- **AND** the user attempts to select it
- **THEN** the file remains unselected
- **AND** the selector presents the supported extension list

### Requirement: Directory navigation remains available
File-only extension and size constraints SHALL NOT prevent users from seeing and opening directories needed to navigate to eligible files unless the caller explicitly applies a kind rule that excludes directories from display.

#### Scenario: Navigate through a directory under file-only constraints
- **WHEN** file-only display or selection constraints are active
- **AND** the current directory contains a subdirectory
- **THEN** the subdirectory remains visible and can be opened
- **AND** opening it does not select it as a file

### Requirement: Existing selector behavior remains compatible
When the new constraints are omitted, the selector SHALL preserve its existing display, selection, single-selection, and category-filter behavior. When existing category filters and new constraints are both supplied, an entry SHALL satisfy both applicable mechanisms.

#### Scenario: New constraints are omitted
- **WHEN** an existing caller does not supply either new constraint
- **THEN** the selector displays and selects entries as it did before this change

#### Scenario: Existing and new filters coexist
- **WHEN** an existing category filter and a new constraint apply to the same stage
- **THEN** an entry passes that stage only if it satisfies both the category filter and the new constraint
