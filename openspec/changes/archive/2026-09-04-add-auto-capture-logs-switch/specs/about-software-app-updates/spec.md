## ADDED Requirements

### Requirement: About software hosts the auto-capture logs switch

The About software page SHALL include an Auto capture logs switch below the existing version, update, history, and external-link actions. The switch SHALL be available on every supported target, including Web JS and Wasm. Toggling it SHALL NOT request an application update and SHALL NOT post an app-update notification.

#### Scenario: Switch is visible with the rest of About software

- **WHEN** the user opens About software
- **THEN** the Auto capture logs switch is visible
- **AND** the running version, history, and existing About actions remain available

#### Scenario: Toggling capture does not check for updates

- **WHEN** the user turns Auto capture logs on or off
- **THEN** the page does not request the latest application update
- **AND** no app-update notification is posted from that toggle
