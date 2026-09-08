# webrtc-device-bookmark-rpc Specification

## Purpose
TBD - created by archiving change add-webrtc-bookmark-rpc. Update Purpose after archive.
## Requirements
### Requirement: WebRTC-connected devices SHALL support remote bookmark management
The system SHALL allow an approved WebRTC-connected device to fetch and manage a remote device's bookmarks.

#### Scenario: Fetch remote bookmarks over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can request the remote bookmark list
- **AND** it receives the same bookmark payload semantics as the HTTP bookmark route client

#### Scenario: Create or update remote bookmark over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can create or update a remote bookmark
- **AND** the operation result matches the HTTP bookmark route behavior

#### Scenario: Delete remote bookmark over WebRTC
- **WHEN** a device has an approved WebRTC connection to another device
- **THEN** it can delete a remote bookmark
- **AND** the operation result matches the HTTP bookmark route behavior

### Requirement: WebRTC bookmark RPC SHALL reuse HTTP bookmark permissions
The system SHALL validate WebRTC bookmark RPC requests through the same role-based permission model used by HTTP bookmark routes.

#### Scenario: Missing or invalid token
- **WHEN** a WebRTC bookmark RPC request does not provide a valid approval-scoped token
- **THEN** the request is rejected
- **AND** no bookmark mutation is executed

#### Scenario: Permission denied for bookmark operation
- **WHEN** the peer token maps to a role that lacks bookmark permission for the requested action
- **THEN** the bookmark RPC request fails
- **AND** the returned error semantics match the HTTP route behavior
