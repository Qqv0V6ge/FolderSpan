## Purpose

Define how a signed-in user adds, replaces, and removes their profile avatar, so that avatar changes take effect immediately and report clear progress, success, and failure without depending on how the picture was chosen or edited.

## ADDED Requirements

### Requirement: Avatar picture control in the profile editor
The profile editor SHALL present an avatar picture control that shows the user's current avatar and offers actions to choose a new picture and to remove the existing one. The profile editor SHALL NOT present a free-text avatar address field, and its save action SHALL NOT submit an avatar address.

#### Scenario: User already has an avatar
- **WHEN** the profile editor loads for a user whose profile carries an avatar address
- **THEN** the control displays that avatar
- **AND** both a replace action and a remove action are offered

#### Scenario: User has no avatar
- **WHEN** the profile editor loads for a user whose profile carries no avatar address
- **THEN** the control displays a placeholder
- **AND** an action to choose a picture is offered
- **AND** no remove action is offered

#### Scenario: Avatar was previously set as an external address
- **WHEN** a user's stored avatar address points at an externally hosted image
- **THEN** that image is still displayed
- **AND** it can be replaced or removed through the control

#### Scenario: Saving other profile fields
- **WHEN** the user edits other profile fields and saves the form
- **THEN** the stored avatar is unchanged by that save

### Requirement: Requesting a new avatar picture
The profile editor SHALL obtain a new avatar as already-encoded image bytes with a content type, and SHALL NOT perform image selection, editing, or encoding itself.

#### Scenario: Picture is delivered
- **WHEN** the user activates the choose or replace action and a picture is delivered
- **THEN** the profile editor begins uploading those bytes
- **AND** no further selection or editing step is presented by the profile editor

#### Scenario: User cancels
- **WHEN** the user abandons the picture request before a picture is delivered
- **THEN** the stored avatar is unchanged
- **AND** no error is reported

#### Scenario: Picture could not be produced
- **WHEN** the picture request reports a failure
- **THEN** the stored avatar is unchanged
- **AND** the reason is reported in wording that does not reference files, formats, or requests in technical terms
- **AND** the action can be attempted again

#### Scenario: No picture source is available
- **WHEN** the profile editor is shown in a host that provides no picture source
- **THEN** the choose and replace actions are presented as unavailable
- **AND** activating the control reports nothing and causes no failure

### Requirement: Avatar upload takes effect immediately
Uploading an avatar SHALL apply as soon as it succeeds, independently of the profile form's save action.

#### Scenario: Upload succeeds
- **WHEN** an avatar upload completes successfully
- **THEN** the stored avatar is updated without any further user action
- **AND** the displayed avatar is refreshed from the response
- **AND** a success confirmation distinct from the profile save confirmation is reported

#### Scenario: Upload is in progress
- **WHEN** an avatar upload is in flight
- **THEN** the control shows progress
- **AND** further choose, replace, and remove actions are rejected until it settles

#### Scenario: Upload fails
- **WHEN** an avatar upload fails
- **THEN** the previously displayed avatar remains displayed
- **AND** the failure is reported with a retry entry point

#### Scenario: Session is no longer valid
- **WHEN** an avatar upload fails because the user is no longer signed in
- **THEN** the existing unauthenticated handling for profile screens is applied

#### Scenario: Server rejects the picture
- **WHEN** the server rejects the uploaded picture as missing, malformed, or too large
- **THEN** the stored avatar is unchanged
- **AND** the rejection is reported in wording a non-technical user can act on

### Requirement: Avatar removal
Removing an avatar SHALL require a confirmation, SHALL take effect immediately, and SHALL remain successful when repeated.

#### Scenario: Removal is confirmed
- **WHEN** the user activates the remove action and confirms
- **THEN** the avatar is removed
- **AND** the control returns to its placeholder state
- **AND** a success confirmation is reported

#### Scenario: Removal is not confirmed
- **WHEN** the user activates the remove action and declines the confirmation
- **THEN** the stored avatar is unchanged

#### Scenario: Removal is repeated
- **WHEN** removal is requested for a user who already has no avatar
- **THEN** the request is treated as successful
- **AND** the control remains in its placeholder state

#### Scenario: Removal fails
- **WHEN** an avatar removal fails
- **THEN** the previously displayed avatar remains displayed
- **AND** the failure is reported with a retry entry point

### Requirement: Avatar transport
The system SHALL upload an avatar as a single-file multipart request to the avatar upload endpoint using the established authenticated request pattern, and SHALL remove an avatar through the avatar removal endpoint.

#### Scenario: Upload request shape
- **WHEN** an avatar upload is sent
- **THEN** the request carries the encoded bytes as the endpoint's single file field with their content type
- **AND** the request is authenticated with the current session

#### Scenario: Profile is refreshed from the response
- **WHEN** an avatar upload or removal succeeds
- **THEN** the updated profile carried in the response replaces the locally held profile

#### Scenario: Server errors are not surfaced verbatim
- **WHEN** an avatar request fails for any reason
- **THEN** the message presented to the user contains no raw server error text, token, endpoint, or identifier
