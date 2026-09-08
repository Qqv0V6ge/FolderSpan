# open-clipboard-items Specification

## Purpose
TBD - created by archiving change add-open-from-clipboard. Update Purpose after archive.
## Requirements
### Requirement: Open-from-clipboard action
The app SHALL expose an "Open from Clipboard" action in the AppDrawer more options menu on every supported platform.

#### Scenario: Open action is available
- **WHEN** the AppDrawer more options menu is opened
- **THEN** an "Open from Clipboard" entry is shown

### Requirement: Clipboard content parsing
The system SHALL read the user's clipboard and parse candidate entries as file paths, directory paths, and URLs (including file URLs).

#### Scenario: Parse mixed clipboard items
- **WHEN** the clipboard contains multiple entries (file list and/or text lines)
- **THEN** each entry is normalized into a typed candidate item

### Requirement: Open parent directory and highlight target
The system SHALL open the parent directory of a resolved path entry and highlight the target item; non-existent paths are skipped.

#### Scenario: Single path opens parent and highlights
- **WHEN** the clipboard resolves to exactly one existing path entry
- **THEN** the app navigates to the parent directory of the entry
- **AND** the entry is highlighted using the selection style
- **AND** the list scrolls to the highlighted entry
- **AND** missing paths are ignored

#### Scenario: Highlight persists until user action
- **WHEN** a clipboard entry is highlighted
- **THEN** the highlight remains until the user performs an action in the file list

### Requirement: Multi-item selection dialog
The system SHALL prompt the user to select which path to open when multiple valid path entries are found.

#### Scenario: Multiple paths require selection
- **WHEN** two or more existing path entries are parsed
- **THEN** a selection dialog is shown
- **AND** selecting an entry opens its parent directory and highlights the entry

#### Scenario: Multiple paths share the same parent
- **WHEN** two or more existing path entries are parsed
- **AND** all entries share the same parent directory
- **THEN** the app opens the parent directory without showing a selection dialog
- **AND** all parsed entries are highlighted

### Requirement: Clipboard content logging
The system SHALL log clipboard text and file entries via LogKit.

#### Scenario: Clipboard entries logged
- **WHEN** clipboard content is read
- **THEN** each text and file entry is logged via LogKit

### Requirement: URL logging
The system SHALL log clipboard URL entries via LogKit.

#### Scenario: URL logged
- **WHEN** the clipboard contains a URL entry
- **THEN** the URL is logged with LogKit

