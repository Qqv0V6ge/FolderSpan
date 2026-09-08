# local-system-notifications Specification

## Purpose

Delivers local system notifications on every supported target (Android, iOS, desktop JVM, Web JS/Wasm) through one shared facade, and routes body taps and action-button taps back into the app as a single payload-based event stream.

## Requirements

### Requirement: Shared notification facade on all targets

The system SHALL post, update, and remove local system notifications through a single shared facade available to common code on all targets (Android, iOS, desktop JVM, Web JS/Wasm). A notification carries an integer id, title, body, and a string-map payload. Reusing an id updates the existing notification.

#### Scenario: Post from common code

- **WHEN** common code requests a system notification with an id, title, body, and payload
- **THEN** the OS notification is shown on every supported target without platform-specific code at the call site

#### Scenario: Device connection request remains a system alert

- **WHEN** a DeviceConnect request is configured for system delivery while the app is either foreground or background
- **THEN** the system attempts to post the OS notification even when a foreground in-app banner is also visible

#### Scenario: Update by id reuse

- **WHEN** a notification is posted with an id that is already shown
- **THEN** the existing notification is replaced rather than duplicated

#### Scenario: Remove by id

- **WHEN** common code removes a notification by id
- **THEN** the notification is dismissed on targets where it was shown, and removal of an unknown id is a no-op

### Requirement: Post result reflects actual availability

The facade's post call SHALL report whether the notification could be delivered in the current environment. When delivery is impossible (unsupported environment, missing OS capability), the call reports failure so the caller can apply fallback handling, and no substitute dialog or notification-only tray icon is left behind.

#### Scenario: Delivery unavailable

- **WHEN** a notification is posted in an environment without a working notification mechanism (for example a desktop session without its native notification service)
- **THEN** the post reports failure and no modal dialog or other substitute UI is shown

#### Scenario: Delivery available

- **WHEN** a notification is posted in a supported environment
- **THEN** the post reports success and the notification is visible in the OS notification surface

### Requirement: Unified click and action event stream

Body taps and action-button taps SHALL be delivered to registered common-code listeners as payload events. A body tap delivers the notification's payload with `notification_action_id` set to `default`; an action-button tap delivers the payload with `notification_action_id` set to the tapped action's id. Exactly one event is delivered per user interaction. Events that arrive before any listener is registered are buffered and replayed to the first registered listener.

#### Scenario: Body tap

- **WHEN** the user taps the body of a system notification
- **THEN** registered listeners receive the notification's payload with `notification_action_id` = `default`

#### Scenario: Action tap

- **WHEN** the user taps an action button on a system notification
- **THEN** registered listeners receive the notification's payload with `notification_action_id` set to that action's id, and no additional body-tap event is emitted for the same interaction

#### Scenario: Listener registered late

- **WHEN** a notification interaction occurs before any listener is registered
- **THEN** the event is buffered and delivered once a listener registers

### Requirement: Action buttons on Android, iOS, and Desktop

On Android, iOS, and Desktop, a notification SHALL be able to carry up to three action buttons whose labels follow the app language at the time the notification is posted. Tapping a button delivers the corresponding action id through the unified event stream. Desktop button rendering MAY depend on the native notification service's capabilities.

#### Scenario: Request notification with actions

- **WHEN** a device-request notification carrying a request kind is posted on Android, iOS, or Desktop
- **THEN** the notification shows the action buttons defined for that kind and tapping one dispatches its action id

#### Scenario: Language change between posts

- **WHEN** the app language changes after the app has started
- **THEN** notifications posted afterwards show action labels in the new language without an app restart

### Requirement: Desktop actions and Web degradation model

Desktop JVM notifications SHALL expose mapped request actions through native notification buttons when the host notification service supports them. Tapping either the body or an action button SHALL bring the app to the foreground and deliver the corresponding event. Web notifications SHALL remain attention signals without inline action buttons; Web actions are completed in the in-app notification detail page.

#### Scenario: Desktop body tap

- **WHEN** the user clicks a desktop system notification
- **THEN** the app window is brought to the foreground and the click event is dispatched to listeners

#### Scenario: Desktop action completion

- **WHEN** a desktop notification represents an actionable request
- **THEN** the native notification receives the mapped action buttons, and tapping one foregrounds the app and dispatches that action id exactly once

### Requirement: Desktop resource hygiene

Each desktop system notification SHALL release its tracked OS handle automatically when its body or an action button is clicked, it is dismissed, it fails asynchronously, it is replaced by the same app id, or it is explicitly removed. Explicit removal SHALL dismiss the corresponding native notification when it is still visible.

#### Scenario: Notification ignored

- **WHEN** a desktop notification is shown and the user never interacts with it
- **THEN** the operating system owns its display lifetime, and the app discards its handle and stored payload when a native dismissal/failure callback arrives

### Requirement: Platform-native desktop delivery

Desktop notifications SHALL use the operating system's native notification surface: freedesktop D-Bus on Linux, UserNotifications on macOS, and Toast on Windows. The implementation SHALL NOT create a tray balloon or modal dialog as a notification fallback. Native x64 and ARM64 packages SHALL be supported for each desktop target.

#### Scenario: Linux ARM64 session

- **WHEN** the app runs on a Linux aarch64 desktop with a freedesktop notification service
- **THEN** system notifications are posted and clickable the same as on x64

#### Scenario: Unpackaged macOS run

- **WHEN** the app runs on macOS outside a .app bundle (for example from the IDE)
- **THEN** notification delivery reports unavailable without crashing and without creating a tray notification

#### Scenario: Packaged macOS run

- **WHEN** the app runs from its packaged `.app` and notification authorization is granted
- **THEN** the notification is posted through macOS UserNotifications and is removable through its native handle

### Requirement: Initialization per platform

Notification initialization SHALL happen once per process at application start, with per-platform configuration supplied by the platform entry point. On iOS, initialization MUST be installed early enough to capture a cold-start tap (a tap on a notification that launched the app).

#### Scenario: Cold-start tap on iOS

- **WHEN** the iOS app is launched by tapping a system notification
- **THEN** the tap event is delivered to the shared event stream after startup completes

#### Scenario: Foreground presentation on iOS

- **WHEN** a notification arrives while the iOS app is in the foreground
- **THEN** it is presented as a banner with sound, consistent with prior behavior
