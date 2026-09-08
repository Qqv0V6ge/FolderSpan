## Purpose

Provide a cross-platform in-app channel for submitting feedback or suggestions and for authenticated users to follow and manage the resulting private support tickets.

## ADDED Requirements

### Requirement: Feedback feature entry and access modes
The system SHALL expose a Feedback & Suggestions entry from the app drawer overflow menu and SHALL keep the public submission flow available whether or not the user is signed in.

#### Scenario: Open feedback from the app drawer while signed out
- **WHEN** a signed-out user opens Feedback & Suggestions from the app drawer overflow menu
- **THEN** the system displays the public submission flow and a sign-in action for My Feedback Tickets

#### Scenario: Open feedback from the app drawer while signed in
- **WHEN** a signed-in user opens Feedback & Suggestions from the app drawer overflow menu
- **THEN** the system displays both the submission flow and access to My Feedback Tickets

### Requirement: Server-defined feedback form
The system SHALL load the available categories from `GET /feedbacks/categories` and SHALL collect a feedback type, optional category, content, optional contact information, application version, and platform compatible with the report service contract.

#### Scenario: Categories load successfully
- **WHEN** the feedback form receives a successful category response
- **THEN** the system displays each returned category using its server-provided key and label

#### Scenario: Categories cannot be loaded
- **WHEN** the category request fails
- **THEN** the system offers a retry and keeps category optional so the user can still submit valid content

#### Scenario: Required metadata is prepared
- **WHEN** the user prepares a submission
- **THEN** the system supplies the running app version and one of `ios`, `android`, `windows`, `macos`, `linux`, `js`, or `other` as the platform

#### Scenario: Required content is missing
- **WHEN** the user attempts to submit blank content
- **THEN** the system identifies the content field as required and does not send the request

### Requirement: Feedback and suggestion submission
The system SHALL submit `feedback` or `suggestion` content to `POST /feedbacks`, SHALL prevent concurrent duplicate submissions, and SHALL only clear the form after the server reports success.

#### Scenario: Anonymous submission succeeds
- **WHEN** a signed-out user submits a valid form and the server accepts it
- **THEN** the system sends the request without a Bearer token and shows a success confirmation without promising authenticated ticket tracking

#### Scenario: Signed-in submission succeeds
- **WHEN** a signed-in user submits a valid form and the server accepts it
- **THEN** the system includes the current Bearer token and offers access to My Feedback Tickets

#### Scenario: Signed-in authorization expires during submission
- **WHEN** a signed-in submission cannot obtain or refresh valid authorization
- **THEN** the system asks the user to sign in again and does not silently resubmit the feedback anonymously

#### Scenario: Submission fails
- **WHEN** the submission returns an HTTP, business, or network failure
- **THEN** the system preserves the entered form values, shows an understandable error, and allows retry

### Requirement: Authenticated ticket list
The system SHALL require an authenticated account to call `GET /feedbacks` and SHALL present the current user's feedback tickets with filtering and paginated loading.

#### Scenario: Signed-out user opens My Feedback Tickets
- **WHEN** a signed-out user requests My Feedback Tickets
- **THEN** the system does not call the protected endpoint and presents a sign-in action

#### Scenario: First ticket page loads
- **WHEN** a signed-in user opens My Feedback Tickets
- **THEN** the system requests page 1 with a page size no greater than 100 and displays the returned items and total count

#### Scenario: User filters tickets
- **WHEN** the user changes the type, platform, or creation-time filter
- **THEN** the system restarts at page 1 and requests the selected query values

#### Scenario: More tickets are available
- **WHEN** the loaded item count is less than the server total and the user requests more
- **THEN** the system loads the next page once and appends non-duplicate tickets

#### Scenario: Ticket list is empty
- **WHEN** the server returns no matching tickets
- **THEN** the system displays an empty state with an action to submit feedback

### Requirement: Ticket detail and read state
The system SHALL load `GET /feedbacks/{uuid}` for a ticket owned by the authenticated user and SHALL show its feedback fields, status, status note, priority, timestamps, public event timeline, allowed actions, and attachment metadata.

#### Scenario: Ticket detail loads
- **WHEN** an authenticated user selects a ticket
- **THEN** the system displays the detail using the response for that ticket UUID

#### Scenario: Unread ticket is opened
- **WHEN** a loaded ticket has unread events
- **THEN** the system calls `POST /feedbacks/{uuid}/read` and updates the local unread indicator only after that operation succeeds

#### Scenario: Detail refresh fails after content was loaded
- **WHEN** a later detail refresh fails
- **THEN** the system retains the last successfully loaded content, identifies it as not refreshed, and offers retry

### Requirement: Editable ticket operations
The system SHALL allow an authenticated owner to update an editable feedback ticket through `PUT /feedbacks` and delete selected owned tickets through `DELETE /feedbacks`, subject to server-side workflow rules.

#### Scenario: Ticket update succeeds
- **WHEN** the user submits valid edited content and the server accepts the update
- **THEN** the system refreshes the affected ticket and list item with server-confirmed data

#### Scenario: Ticket deletion is requested
- **WHEN** the user chooses to delete one or more feedback tickets
- **THEN** the system requires confirmation before sending their UUIDs

#### Scenario: Ticket deletion succeeds
- **WHEN** the server confirms deletion
- **THEN** the system removes the deleted tickets from the current list and leaves the detail view if its ticket was deleted

#### Scenario: Workflow rejects an edit or deletion
- **WHEN** the server rejects an operation because the ticket is no longer editable or deletable
- **THEN** the system shows the server-derived user-facing failure and refreshes the ticket state

### Requirement: Supplement and withdrawal operations
The system SHALL expose supplement and withdrawal actions only as allowed by the detail response and SHALL enforce the request limits before calling the corresponding protected endpoints.

#### Scenario: Supplement is allowed
- **WHEN** `canSupplement` is true and the user submits non-blank content of at most 4000 characters
- **THEN** the system calls `POST /feedbacks/{uuid}/supplements` and refreshes the detail after success

#### Scenario: Supplement is not allowed
- **WHEN** `canSupplement` is false
- **THEN** the system does not offer an enabled supplement action

#### Scenario: Withdrawal is confirmed
- **WHEN** `canWithdraw` is true and the user confirms withdrawal with an optional reason of at most 1000 characters
- **THEN** the system calls `POST /feedbacks/{uuid}/withdraw` and refreshes the resulting status after success

#### Scenario: Withdrawal is not allowed
- **WHEN** `canWithdraw` is false
- **THEN** the system does not offer an enabled withdrawal action

### Requirement: Private ticket attachments
The system SHALL let an authenticated ticket owner upload and download private attachments using the feedback attachment endpoints while enforcing the advertised file constraints before upload.

#### Scenario: Supported attachment is selected
- **WHEN** the user selects a JPG, PNG, WebP, PDF, TXT, or LOG file no larger than 10 MiB
- **THEN** the system uploads it as the multipart field `file` to `POST /feedbacks/{uuid}/attachments` and refreshes the attachment list after success

#### Scenario: Unsupported attachment is selected
- **WHEN** the selected file type is unsupported or its size exceeds 10 MiB
- **THEN** the system explains the accepted formats or size limit and does not upload the file

#### Scenario: Attachment download succeeds
- **WHEN** the user downloads an attachment from `GET /feedbacks/{uuid}/attachments/{attachmentUuid}`
- **THEN** the system saves or opens the returned bytes using the response filename when available and the attachment metadata name otherwise

#### Scenario: Attachment transfer is cancelled or fails
- **WHEN** an upload or download is cancelled or fails
- **THEN** the system stops the in-progress state, preserves the ticket detail, and offers retry without adding a false attachment entry

### Requirement: Secure, resilient, and accessible presentation
The system SHALL use the existing account session for protected requests, SHALL treat non-zero response envelope codes as failures, and SHALL present localized, accessible states across supported window sizes and platforms.

#### Scenario: Protected request receives unauthorized response
- **WHEN** a protected request remains unauthorized after the supported session refresh attempt
- **THEN** the system clears invalid authorization, preserves non-sensitive screen context, and presents a sign-in action

#### Scenario: Request is in progress
- **WHEN** a form or ticket operation is running
- **THEN** the initiating action indicates progress and cannot be triggered again until the operation finishes

#### Scenario: Unknown server value is returned
- **WHEN** the server returns a new status, priority, event type, or category value
- **THEN** the system renders a safe fallback label without failing to display the rest of the ticket

#### Scenario: Screen size or input method changes
- **WHEN** the feedback experience is used on compact or expanded layouts with touch, keyboard, or assistive technology
- **THEN** controls remain reachable, focusable, labeled, and usable without obscuring validation or operation status
