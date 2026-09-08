## Purpose

When diagnostic capture is on, tells the user that an error was recorded and lets them open feedback with the log snapshot attached.

## ADDED Requirements

### Requirement: Capture-on errors post a single updatable error notification

While Auto capture logs is on, the system SHALL post an in-app bell notification of error type when the shared logger records an error or an unhandled crash is captured. The same notification SHALL also be requested as a system notification. Repeated errors within five minutes SHALL update that notification instead of posting a new one. While Auto capture logs is off, these error notifications SHALL NOT be posted.

#### Scenario: First error while capture is on

- **WHEN** capture is on and an error is logged
- **THEN** the user receives one error notification in the in-app bell
- **AND** a matching system notification is requested

#### Scenario: Burst of errors updates the same notification

- **WHEN** capture is on and another error is logged within five minutes of the previous error notification
- **THEN** the existing error notification is updated
- **AND** a second distinct error-log notification is not created

#### Scenario: Capture off does not notify for ordinary errors

- **WHEN** capture is off and an error is logged
- **THEN** no error-log-capture notification is posted
- **AND** the existing crash screen behavior is unchanged

### Requirement: Notification body or Feedback action opens feedback with logs

Tapping the error-log notification body, or tapping its Feedback action, SHALL open the in-app feedback form. The form SHALL be prefilled with an error summary. After the user submits the ticket, the system SHALL upload the current log snapshot as a `.log` / `text/plain` attachment, no larger than the existing 10 MiB feedback attachment limit. If the user is signed out, the existing sign-in gate SHALL run before the form. Web system notifications have no inline action button; the in-app notification and the notification-body tap SHALL still open feedback.

#### Scenario: Body tap opens feedback

- **WHEN** the user taps the body of the error-log notification
- **THEN** the feedback form opens
- **AND** the form content includes an error summary from that snapshot

#### Scenario: Feedback action opens the same form

- **WHEN** the user taps the Feedback action on an Android, iOS, or Desktop error-log notification
- **THEN** the feedback form opens with the same prefilled summary as a body tap

#### Scenario: Submitted ticket receives the log file

- **WHEN** the user submits that prefilled feedback ticket
- **THEN** the ticket has a `.log` attachment containing the captured snapshot
- **AND** the attachment is no larger than 10 MiB

#### Scenario: Signed-out user is asked to sign in

- **WHEN** a signed-out user activates the error-log notification
- **THEN** the existing sign-in gate is shown before the feedback form

### Requirement: Crash screen offers Feedback while capture is on

When the crash screen is shown and Auto capture logs is on, the crash screen SHALL offer a Feedback action in addition to restart, copy, and exit. That action SHALL open the same feedback flow as the error-log notification, using the crash report plus the captured log snapshot. When capture is off, the crash screen SHALL NOT show that Feedback action.

#### Scenario: Capture on shows Feedback on the crash screen

- **WHEN** the crash screen is displayed and Auto capture logs is on
- **THEN** a Feedback action is available
- **AND** activating it opens the feedback form with the crash summary

#### Scenario: Capture off keeps the original crash actions

- **WHEN** the crash screen is displayed and Auto capture logs is off
- **THEN** restart, copy, and exit remain available
- **AND** no Feedback action is shown
