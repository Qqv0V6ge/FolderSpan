## ADDED Requirements

### Requirement: Error-log notifications use a Feedback action

An error-log-capture notification SHALL carry a stable request id so that reuse updates the existing notification. On Android, iOS, and Desktop it SHALL show a Feedback action button whose label follows the app language. Tapping Feedback SHALL deliver `notification_action_id` for that action through the existing unified click stream. Tapping the notification body SHALL deliver `notification_action_id` = `default`. Web notifications SHALL remain attention signals without an inline Feedback button; the body tap and the in-app bell row SHALL still open the same feedback flow.

#### Scenario: Native Feedback action

- **WHEN** an error-log-capture notification is posted on Android, iOS, or Desktop
- **THEN** the notification shows a Feedback action
- **AND** tapping it dispatches that action id exactly once

#### Scenario: Body tap uses default action id

- **WHEN** the user taps the body of an error-log-capture notification
- **THEN** listeners receive the payload with `notification_action_id` = `default`

#### Scenario: Web has no inline Feedback button

- **WHEN** an error-log-capture notification is posted on Web JS or Wasm
- **THEN** the system notification has no inline action buttons
- **AND** tapping the notification body still delivers the default action id
