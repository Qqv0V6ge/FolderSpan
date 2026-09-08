# adaptive-file-selector-layout Specification

## Purpose

Use the available window area efficiently by adapting file browsing between compact lists and multi-column grids while keeping selector dialogs usable at full size.

## Requirements

### Requirement: File selector adapts its item layout to available width
The file selector SHALL render entries as a single-column list in compact containers and as an adaptive multi-column grid when the container has sufficient horizontal space.

#### Scenario: Compact selector uses a list
- **WHEN** the file selector's available width is less than 600 dp
- **THEN** entries are rendered in a single-column list
- **AND** each entry retains its name, metadata, kind, and selection state

#### Scenario: Wider selector uses a grid
- **WHEN** the file selector's available width is at least 600 dp
- **THEN** entries are rendered in a grid with as many columns as fit without making an entry narrower than the configured minimum item width
- **AND** additional horizontal space is used for additional columns rather than an empty margin

#### Scenario: Window width changes
- **WHEN** the available width crosses the list/grid threshold while the selector is open
- **THEN** the selector changes layout without changing the current path, active constraints, or selected entries

### Requirement: Constraints behave consistently in list and grid layouts
Display constraints, selection constraints, category filters, directory navigation, sorting, hidden-file controls, and rejection feedback SHALL have the same behavior in both layouts.

#### Scenario: Grid contains a rejected selection
- **WHEN** a file is visible but rejected by the active selection constraint in grid layout
- **THEN** the grid item remains visible with an unavailable selection affordance
- **AND** attempting to select it presents the same localized reason as list layout

#### Scenario: Directory is opened from the grid
- **WHEN** the user activates a directory item in grid layout
- **THEN** the selector navigates into that directory
- **AND** the responsive layout is recalculated for the unchanged container width

### Requirement: Grid items use a flat visual treatment
The adaptive grid SHALL present compact, readable file tiles without wrapping every entry in a card-style container.

#### Scenario: Grid is rendered
- **WHEN** the selector uses grid layout
- **THEN** each entry uses a flat tile with clear icon, name, metadata, focus, hover, and selected states
- **AND** selection remains distinguishable without a persistent card background

#### Scenario: Expanded grid remains readable on a wide window
- **WHEN** the selector uses grid layout with abundant horizontal space
- **THEN** adaptive entries remain at least 280 dp wide and 168 dp high
- **AND** each entry presents a prominent centered file icon instead of collapsing into a miniature list row

### Requirement: Full-size file-selector dialogs use all available space
The reusable full-size file-selector dialog shell SHALL use the available window width and height, subject only to safe drawing insets, instead of applying a fixed maximum content width.

#### Scenario: Full-size selector on an expanded window
- **WHEN** a caller opens the full-size selector dialog on an expanded window
- **THEN** the dialog surface fills the available width and height
- **AND** the file selector uses the resulting space for its adaptive grid

#### Scenario: Selector on a compact window
- **WHEN** a caller opens the full-size selector dialog on a compact window
- **THEN** the dialog behaves as a full-screen selector
- **AND** content remains within safe drawing insets

### Requirement: Dialog navigation and actions remain visible
The full-size selector dialog SHALL keep its title, navigation and filter controls, and cancel/confirm actions available while only the file-entry region scrolls.

#### Scenario: Directory contains many entries
- **WHEN** the current directory has more entries than fit vertically
- **THEN** the entry region scrolls independently
- **AND** dialog actions remain reachable without scrolling to the end of the entries
