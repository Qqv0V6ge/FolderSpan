# app-navigation Specification

## Purpose
TBD - created by archiving change migrate-to-androidx-navigation3. Update Purpose after archive.
## Requirements
### Requirement: Navigation 3 route stack
The system SHALL render app screens from an app-owned AndroidX Navigation 3 back stack whose entries are project route keys rather than Voyager `Screen` values.

#### Scenario: Initial route renders home
- **WHEN** the main app navigation host is created
- **THEN** the back stack contains the Home route as its root entry

#### Scenario: Route renders destination content
- **WHEN** a supported route is the top entry in the back stack
- **THEN** the navigation host renders the matching screen content for that route

### Requirement: App navigation requests
The system SHALL expose app navigation operations through a project-owned navigator boundary that can push, pop, pop to root, and report the current route without exposing Voyager or mutable back-stack internals to callers.

#### Scenario: Push route
- **WHEN** app code requests navigation to a non-current route
- **THEN** the route is added to the top of the Navigation 3 back stack

#### Scenario: Suppress duplicate current route
- **WHEN** app code requests navigation to the same route that is already current
- **THEN** the back stack is not extended with a duplicate entry

#### Scenario: Request before host is ready
- **WHEN** app code requests navigation before the navigation host has registered its navigator
- **THEN** the route is saved as pending and pushed after the navigator becomes available

### Requirement: Back navigation behavior
The system SHALL use NavigationEvent-aware back handling so back actions pop app routes before platform-level exit behavior runs.

#### Scenario: Back from non-root route
- **WHEN** a back action occurs and the back stack contains more than the root route
- **THEN** the current route is removed and the previous route becomes visible

#### Scenario: Back from root route
- **WHEN** a back action occurs and the back stack contains only the root route
- **THEN** the app does not pop the root route and platform root-back behavior remains available

### Requirement: External entry navigation
The system SHALL route external screen-opening requests from notifications, quick actions, tray actions, drawer actions, and app scaffolds through the same project navigation boundary.

#### Scenario: External request opens target route
- **WHEN** an external entry point requests a supported target screen
- **THEN** the app opens the matching route in the active Navigation 3 stack

#### Scenario: Compact navigation closes drawer
- **WHEN** a route is pushed while the window size is Compact
- **THEN** the drawer expanded state is set to false before the route is displayed

### Requirement: Voyager removal
The system SHALL remove Voyager from app navigation after all routes and call sites have been migrated.

#### Scenario: No Voyager navigation imports remain
- **WHEN** the migration is complete
- **THEN** app source and shared source no longer import Voyager `Screen`, `Navigator`, `LocalNavigator`, or `CurrentScreen`

#### Scenario: Gradle no longer depends on Voyager navigator
- **WHEN** the migration is complete
- **THEN** the app and shared Gradle source sets no longer declare the Voyager navigator dependency
