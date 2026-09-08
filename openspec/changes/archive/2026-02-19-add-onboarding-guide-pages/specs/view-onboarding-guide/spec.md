## ADDED Requirements
### Requirement: First-launch onboarding auto-open
The system SHALL auto-open the onboarding guide once on the first app launch and SHALL NOT auto-open it again after it has been shown.

#### Scenario: Auto-open on first launch
- **WHEN** the app is opened and onboarding has not been shown before
- **THEN** the onboarding guide screen is opened automatically as the initial screen
- **AND** `HomeScreen` is not shown before onboarding

#### Scenario: Do not auto-open after first time
- **WHEN** onboarding has already been shown in a previous session
- **THEN** the app opens normally without auto-opening the onboarding guide

### Requirement: Two-page onboarding structure
The onboarding guide screen SHALL contain exactly two pages and SHALL support explicit page navigation with page-state feedback.

#### Scenario: Navigate between pages
- **WHEN** the user uses next/back controls (or equivalent page navigation)
- **THEN** the screen switches between page 1 and page 2
- **AND** the current page state is visible to the user

### Requirement: Capability overview page
Page 1 SHALL introduce app purpose and file/folder transfer-management capabilities.
Page 1 SHALL explain `Local`, `Device`, `Share`, and `Network` in a way consistent with current data/domain models and device connection flow (`Disk.kt`, `Share.kt`, and `Network.kt`).

#### Scenario: Read disk model overview
- **WHEN** the user opens page 1
- **THEN** the user can identify the role of `Local`, `Device`, `Share`, and `Network`
- **AND** the content reflects current model behavior (including operation/permission characteristics)

### Requirement: File share route explanation and action
Page 1 SHALL explain how `FileShareScreen` is reached from existing routes and SHALL provide a direct action that opens `FileShareScreen`.

#### Scenario: Open file share from guide
- **WHEN** the user taps the file-share route action on page 1
- **THEN** the system navigates to `FileShareScreen`

### Requirement: Usage overview page
Page 2 SHALL provide a practical usage guide that covers `HomeScreen`, `AppDrawer`, and `FileScreen` responsibilities and a typical user path.

#### Scenario: Read daily usage flow
- **WHEN** the user opens page 2
- **THEN** the user can follow a clear sequence for drawer navigation, file browsing/filtering, and file actions

### Requirement: Material theme introduction
The onboarding guide SHALL include a theme-introduction section based on Material 3 color roles provided by `Theme.kt`/`MaterialTheme.colorScheme`.

#### Scenario: View theme role colors
- **WHEN** the user views the theme section in either light or dark mode
- **THEN** the guide shows role-based colors consistent with the active Material theme

### Requirement: Responsive onboarding layout
The onboarding guide SHALL adapt its content width and action layout across all window classes (`Compact`, `Medium`, `Expanded`, `Large`, `ExtraLarge`) to keep content readable and actionable.
Different window classes SHALL use distinct composition patterns instead of only spacing tweaks.

#### Scenario: Compact layout
- **WHEN** the guide is opened on a compact screen
- **THEN** content uses compact spacing and full-width actions suitable for narrow viewports

#### Scenario: Medium layout
- **WHEN** the guide is opened on a medium screen
- **THEN** content uses a two-column-oriented composition with balanced card widths
- **AND** actions remain visible without overlaying core content

#### Scenario: Expanded layout
- **WHEN** the guide is opened on an expanded screen
- **THEN** content uses a split composition (guide rail + content area) and keeps clear reading hierarchy

#### Scenario: Large and extra-large layouts
- **WHEN** the guide is opened on large or extra-large screens
- **THEN** content expands into wider multi-column compositions with non-stretched cards
- **AND** navigation actions stay reachable without excessive pointer travel
