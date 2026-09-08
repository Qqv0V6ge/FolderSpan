## ADDED Requirements

### Requirement: Link-share per-device upload authorization
The link-share server SHALL enforce upload permission per authorized device/session, independently from browse and download authorization.

#### Scenario: Authorized device without upload permission checks upload
- **GIVEN** a link-share device has valid browse/download authorization
- **AND** that device does not have upload permission
- **WHEN** the device calls upload-check for a shared path
- **THEN** the server rejects the upload capability check without changing browse/download access

#### Scenario: Authorized device with upload permission uploads
- **GIVEN** a link-share device has valid browse/download authorization
- **AND** that device has upload permission
- **WHEN** the device calls upload-check or upload routes for an allowed shared path
- **THEN** the server accepts the request using the existing upload route contract

#### Scenario: Host revokes one device upload permission
- **GIVEN** two link-share devices are authorized to browse the same share
- **AND** both devices initially have upload permission
- **WHEN** the host revokes upload permission for one device
- **THEN** upload routes for that device are rejected
- **AND** upload routes for the other device continue to use its own upload permission

### Requirement: Link-share upload permission requests
The link-share server SHALL allow an already authorized browse/download device to request upload permission, and the request SHALL be reviewable by the host.

#### Scenario: Device requests upload permission
- **GIVEN** a link-share device has valid browse/download authorization
- **AND** the device does not have upload permission
- **WHEN** the device sends an upload permission request
- **THEN** the server records a pending upload request for that device
- **AND** the host can see the device as requesting upload permission

#### Scenario: Duplicate upload permission request
- **GIVEN** a link-share device already has a pending upload permission request
- **WHEN** the same device sends another upload permission request
- **THEN** the server keeps one pending request for that device
- **AND** the response indicates the request is already pending

#### Scenario: Device requests after upload rejection
- **GIVEN** a link-share device has valid browse/download authorization
- **AND** the host has rejected upload permission for that device
- **WHEN** the device sends an upload permission request
- **THEN** the server does not create a pending upload request
- **AND** the response indicates upload permission was rejected

#### Scenario: Host approves upload request
- **GIVEN** a link-share device has a pending upload permission request
- **WHEN** the host approves the request
- **THEN** the device gains upload permission
- **AND** the pending upload request is cleared

#### Scenario: Host rejects upload request
- **GIVEN** a link-share device has a pending upload permission request
- **WHEN** the host rejects the request
- **THEN** the device remains authorized for browse/download
- **AND** the device does not have upload permission
- **AND** the pending upload request is cleared
