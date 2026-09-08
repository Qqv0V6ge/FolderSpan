# notification-announcement-tab Specification

## Purpose

Lets signed-out and signed-in users browse the public announcement catalog in the notification center, with device-local read state and no dependency on the account inbox.

## Requirements

### Requirement: Announcement tab is available without signing in

The notification center SHALL present an Announcements tab to signed-out users alongside the Local tab. The Account tab SHALL remain hidden until the user is signed in. Signed-in users SHALL see Local, Account, and Announcements.

#### Scenario: Signed-out notification center shows announcements

- **WHEN** a signed-out user opens the notification center
- **THEN** the Local and Announcements tabs are visible
- **AND** the Account tab is not visible

#### Scenario: Signed-in notification center shows three tabs

- **WHEN** a signed-in user opens the notification center
- **THEN** the Local, Account, and Announcements tabs are visible

#### Scenario: Signing out while on Account returns to a public tab

- **WHEN** the user signs out while the Account tab is selected
- **THEN** the notification center shows a remaining public tab instead of an empty Account tab

### Requirement: Announcement catalog loads public announcements for the current platform

The Announcements tab SHALL list published announcements from the public message-center announcement list. The request SHALL ask only for announcement items, SHALL include the current client platform, and SHALL NOT require an access token. Title and body language SHALL follow the client's existing request language header.

#### Scenario: Platform-filtered list

- **WHEN** the user opens the Announcements tab on a given platform
- **THEN** the list contains announcements applicable to that platform or to all platforms
- **AND** announcements targeted only at other platforms are not shown

#### Scenario: Signed-out user can load the catalog

- **WHEN** a signed-out user opens the Announcements tab
- **THEN** the catalog loads without prompting for sign-in

#### Scenario: App updates are not mixed into the catalog

- **WHEN** the Announcements tab loads its list
- **THEN** application-update records are not included

### Requirement: Empty and failed catalog loads are distinguishable

When the announcement list is successfully empty, the Announcements tab SHALL show an empty catalog. When the list cannot be loaded, the tab SHALL show a retryable error and SHALL NOT present that failure as an empty catalog.

#### Scenario: No announcements for this platform

- **WHEN** the announcement list returns no items for the current platform
- **THEN** the Announcements tab shows an empty catalog rather than an error

#### Scenario: Catalog load fails

- **WHEN** the announcement list request fails for a reason other than an empty catalog
- **THEN** the Announcements tab shows an error with a retry action

### Requirement: Announcement catalog can load additional pages

The Announcements tab SHALL load further pages while more announcements remain, keeping already loaded items.

#### Scenario: Load more announcements

- **WHEN** the user reaches the end of the loaded announcement list and more pages exist
- **THEN** the next page is appended without replacing earlier items

### Requirement: Read state uses a device-local published-at watermark

Announcement read state SHALL be stored on the device as a single published-at cutoff. An announcement SHALL be unread when its published-at timestamp is after the cutoff, and read when it is on or before the cutoff. When no cutoff has been stored yet, the system SHALL persist the current time as the cutoff so already published announcements start as read.

#### Scenario: First use treats existing announcements as read

- **WHEN** the device has never stored an announcement cutoff
- **THEN** the current time is stored as the cutoff
- **AND** announcements published at or before that time appear as read

#### Scenario: Later publication is unread

- **WHEN** an announcement is published after the stored cutoff
- **THEN** that announcement appears as unread on this device

#### Scenario: Cutoff does not follow the signed-in account

- **WHEN** the user signs in or out
- **THEN** the announcement cutoff on that device is unchanged

### Requirement: Announcement list can be filtered by local read state

The Announcements tab SHALL reuse the notification center All / Unread / Read filters against the local watermark. Changing those filters SHALL NOT call a server read-status API.

#### Scenario: Unread filter shows only newer announcements

- **WHEN** the user selects the Unread filter on the Announcements tab
- **THEN** only announcements published after the cutoff are listed

#### Scenario: Read filter shows only caught-up announcements

- **WHEN** the user selects the Read filter on the Announcements tab
- **THEN** only announcements published at or before the cutoff are listed

### Requirement: Mark all announcements read advances the watermark

The Announcements tab SHALL provide a mark-all-read action that advances the cutoff so every currently loaded announcement becomes read. Opening an announcement detail SHALL NOT change the cutoff. The tab SHALL NOT offer per-item mark-read, mark-unread, or delete actions.

#### Scenario: Mark all loaded announcements read

- **WHEN** the user marks all announcements read
- **THEN** the cutoff advances to cover the loaded announcements
- **AND** those announcements appear as read

#### Scenario: Opening detail does not catch up the catalog

- **WHEN** the user opens an unread announcement's detail
- **THEN** the cutoff is unchanged
- **AND** other unread announcements remain unread

#### Scenario: No announcement delete action

- **WHEN** the user views an announcement in the list or detail
- **THEN** no delete action is offered for that announcement

### Requirement: Announcement detail reuses notification Markdown and a single link action

Announcement titles SHALL remain plain text. Announcement list rows SHALL use the existing notification plain-text preview. Announcement detail SHALL render the existing notification Markdown subset. When an announcement includes a non-empty HTTP or HTTPS link, detail SHALL offer a single action that opens that URL outside the app. Any other link scheme SHALL be presented as unavailable and SHALL NOT be opened.

#### Scenario: Detail renders Markdown body

- **WHEN** the user opens an announcement whose content contains supported Markdown
- **THEN** the detail view renders that content with the existing notification Markdown behavior

#### Scenario: List row stays a plain preview

- **WHEN** an announcement with Markdown links is shown in the list
- **THEN** the row shows the plain-text preview and the row click still opens detail

#### Scenario: HTTPS link opens externally

- **WHEN** the user activates an announcement link whose URL uses `https`
- **THEN** the system opens that URL outside the app

#### Scenario: Non-HTTP link is unavailable

- **WHEN** an announcement link uses a scheme other than `http` or `https`
- **THEN** the action is presented as unavailable and activating it opens nothing

#### Scenario: Missing link hides the action

- **WHEN** an announcement has an empty or absent link
- **THEN** no link action is shown for that announcement

### Requirement: Home unread badge ignores announcements

The home and drawer notification unread badge SHALL continue to count only local unread notifications and, when signed in, account unread notifications. Announcement unread counts SHALL NOT be added to that badge.

#### Scenario: Unread announcements do not badge the bell

- **WHEN** the device has unread announcements and no local or account unread notifications
- **THEN** the home notification badge does not show an unread count from those announcements

### Requirement: Announcement catalog is not merged with the account inbox

The Announcements tab SHALL list catalog items independently from account notifications. The system SHALL NOT hide, merge, or replace an announcement because a similar account notification exists.

#### Scenario: Same subject can appear in both tabs

- **WHEN** a signed-in user has an account notification that duplicates an announcement's subject
- **THEN** the announcement remains in the Announcements tab
- **AND** the account notification remains in the Account tab
