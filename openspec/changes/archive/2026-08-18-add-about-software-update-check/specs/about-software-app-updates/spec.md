## Purpose

Lets users see the running app version in Settings and, on native targets, learn about a newer published build through an in-page check and a single device notification whose action button opens the download link.

## ADDED Requirements

### Requirement: Settings exposes an About software page with the running version

The system SHALL provide an About software entry in Settings that opens a page showing the running application version on every supported target.

#### Scenario: Open About software from Settings

- **WHEN** the user selects the About software entry in Settings
- **THEN** the About software page opens
- **AND** the page displays the running application version

#### Scenario: Web About software has no update check

- **WHEN** the user opens About software on Web JS or Wasm
- **THEN** the running version is shown
- **AND** no Check for updates action is offered
- **AND** no update channel control is offered

### Requirement: Native About software can check for a newer published version

On Android, iOS, and Desktop, About software SHALL offer a Check for updates action that requests the public latest application update for the current client platform and the user-selected update channel without requiring sign-in. Opening About software SHALL NOT request the latest application update. The selected channel SHALL default to `release`. The page SHALL distinguish up to date, a newer version, and a retryable failure. A newer version SHALL present the published title or changelog and SHALL offer opening the update link when that link is HTTP or HTTPS. Checking from the page SHALL NOT post a device notification. A newer-version dialog SHALL open after a check that finds a newer build, or when the user opens the update notes; opening the page SHALL NOT show that dialog by itself.

#### Scenario: Opening About software does not check

- **WHEN** the user opens About software
- **THEN** the page does not request the latest application update
- **AND** a newer-version dialog is not shown until the user checks or opens the update notes

#### Scenario: Already on the latest version

- **WHEN** the user checks for updates and the latest published version is missing, unparseable, or not newer than the running version
- **THEN** the page reports that the app is up to date
- **AND** no device notification is posted from that check

#### Scenario: Newer version is available

- **WHEN** the user checks for updates and the latest published version is newer than the running version
- **THEN** the page shows that a newer version is available
- **AND** a dialog presents the published update text
- **AND** if the update link is HTTP or HTTPS, the user can open that URL outside the app

#### Scenario: Latest-update request fails

- **WHEN** the user checks for updates and the request fails for a reason other than an empty catalog
- **THEN** the page shows a retryable error
- **AND** that failure is not presented as up to date

#### Scenario: Check does not require an account

- **WHEN** a signed-out user checks for updates on a native target
- **THEN** the check runs without prompting for sign-in

### Requirement: Native About software can switch the update channel

On Android, iOS, and Desktop, About software SHALL let the user choose the `release` or `beta` update channel. The choice SHALL persist on the current device, SHALL default to `release` when unset or unrecognized, and SHALL apply to both manual and automatic latest-update requests. Changing the channel SHALL NOT request the latest application update. The next Check for updates action or automatic check SHALL use the newly selected channel. Changing the channel SHALL NOT post a device notification.

#### Scenario: Default channel is release

- **WHEN** the user has never chosen an update channel
- **THEN** latest-update requests use `channel=release`

#### Scenario: Switching to beta persists without requesting

- **WHEN** the user selects the beta channel on About software
- **THEN** the choice is stored on the current device
- **AND** selecting the channel does not itself request the latest application update
- **AND** a later Check for updates or automatic check uses `channel=beta`
- **AND** no device notification is posted from that switch

### Requirement: Native clients request latest on process start and every five hours

On Android, iOS, and Desktop, the system SHALL request the public latest application update for the current client platform when the application process starts, and SHALL request it again every five hours while that process remains alive. The system SHALL NOT perform this request solely because the application returned from the background. Web JS and Wasm SHALL NOT perform these requests.

#### Scenario: Process start checks once

- **WHEN** the application process starts on Android, iOS, or Desktop
- **THEN** the system requests the latest application update for the current platform

#### Scenario: Recheck after five hours in the same process

- **WHEN** five hours have elapsed since the previous automatic request in the same process
- **THEN** the system requests the latest application update again

#### Scenario: Returning from background does not check

- **WHEN** the user returns the native app from the background without a new process start
- **THEN** the system does not request the latest application update for that resume

#### Scenario: Web does not poll

- **WHEN** the application runs on Web JS or Wasm
- **THEN** the system does not request the latest application update automatically

### Requirement: A newer published version produces one device notification

When an automatic check finds a latest published version newer than the running version, the system SHALL upsert a single device-local notification in the notification center Local tab and SHALL post or replace one system notification using a stable identity that does not change across checks for the same or a later update. The system SHALL NOT stack additional app-update notifications from later automatic checks. Automatic checks that find no newer version SHALL NOT post an app-update notification. The application SHALL NOT block use of the app because an update exists.

#### Scenario: First automatic find upserts one local notification

- **WHEN** an automatic check finds a newer published version
- **THEN** one unread app-update notification appears in the Local tab

#### Scenario: Later automatic checks replace the same notification

- **WHEN** a later automatic check in the same process again finds a newer published version
- **THEN** the Local tab still contains a single app-update notification
- **AND** the system notification for that update is replaced rather than duplicated

#### Scenario: No newer version stays silent

- **WHEN** an automatic check finds no latest record or a version that is not newer
- **THEN** no new app-update notification is posted

#### Scenario: Update does not block the app

- **WHEN** a newer version is available
- **THEN** the user can continue using the app without being forced to update

### Requirement: An action button opens the update link

The Local-tab app-update notification, its detail, and the matching system notification SHALL offer an Open download page action. Activating that action SHALL open the update link outside the app when that link is HTTP or HTTPS. Activating the Local-tab row or the body of the system notification SHALL open the notification detail and SHALL NOT open the update link. When the link is missing or not HTTP or HTTPS, the system notification SHALL omit the action, and activating Open download page SHALL NOT open a URL and SHALL NOT crash.

#### Scenario: Action button opens HTTPS link

- **WHEN** the user activates the Open download page action on the Local-tab app-update notification, its detail, or the matching system notification whose link uses `https`
- **THEN** the system opens that URL outside the app

#### Scenario: Notification body opens detail

- **WHEN** the user activates the Local-tab row or the body of the system notification for the app update
- **THEN** a notification detail page is opened
- **AND** the update link is not opened from that activation

#### Scenario: Invalid link is a no-op

- **WHEN** the Open download page action is activated and the link is empty or uses a scheme other than `http` or `https`
- **THEN** no URL is opened
- **AND** the app does not crash

### Requirement: Latest-update requests are public, platform-scoped, and not mixed with announcements

Automatic and manual latest-update requests SHALL call the public application-update latest endpoint, SHALL include the current native client platform, SHALL include the selected update channel (`release` or `beta`), SHALL NOT require an access token, and SHALL follow the client's existing request language header. Empty catalogs SHALL be treated as no update. The announcement catalog SHALL remain a separate source and SHALL NOT be used as the app-update feed.

#### Scenario: Platform-scoped latest record

- **WHEN** a native client requests the latest application update
- **THEN** the request includes that client's platform
- **AND** the request includes the selected update channel
- **AND** a record targeted only at other platforms is not treated as this client's latest update

#### Scenario: Empty catalog is not a hard failure

- **WHEN** the latest-update endpoint returns no record for the current platform
- **THEN** the client treats that as no update rather than as a request failure

#### Scenario: Announcements stay on the announcement feed

- **WHEN** the app loads announcements or checks for application updates
- **THEN** announcement list requests are not used to decide whether an application update exists
- **AND** latest-update requests are not used to populate the Announcements tab

### Requirement: About software can open published version history

About software SHALL offer a Version history item on every supported target. Opening it SHALL request the public application-update list for the current client platform and the selected update channel without requiring sign-in. The list SHALL NOT be requested solely because the process started. Selecting a published version SHALL show its notes and SHALL offer opening the update link when that link is HTTP or HTTPS. The application SHALL NOT open the update link from selecting the list row alone.

#### Scenario: Version history is available on Web

- **WHEN** the user opens About software on Web JS or Wasm
- **THEN** the Version history item is shown
- **AND** Check for updates remains hidden

#### Scenario: History lists the current channel

- **WHEN** the user opens Version history
- **THEN** the client requests `GET /api/v1/updates` with the current platform and selected channel
- **AND** the request does not include an access token

#### Scenario: Selecting a version opens notes, not the download

- **WHEN** the user selects a published version whose link uses `https`
- **THEN** the published notes are shown
- **AND** an Open download page action can open that URL outside the app
- **AND** selecting the row does not itself open the URL

#### Scenario: Empty history is not a hard failure

- **WHEN** Version history returns no records for the current platform and channel
- **THEN** the page explains that no published versions are available
- **AND** that empty list is not presented as a request failure
