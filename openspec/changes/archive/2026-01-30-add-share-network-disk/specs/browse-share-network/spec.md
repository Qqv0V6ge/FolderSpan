## ADDED Requirements

### Requirement: ShareNetwork disk entry
The system SHALL expose a `ShareNetwork` DiskBase entry that represents a link-share HTTP endpoint and can be selected from the network disk switch list.

#### Scenario: Select ShareNetwork from disk switch
- **WHEN** a ShareNetwork entry is present in `NetworkState` and the user selects it
- **THEN** the current desk is set to that ShareNetwork entry

### Requirement: ShareNetwork list requests
When the current desk is ShareNetwork, the system SHALL request directory listings from the link-share Route server using `X-API-Request: true`, a `pwd` query parameter sourced from `ShareNetwork.password`, and a User-Agent derived from `getSocketDevice()`.
The system SHALL tag returned entries with `FileProtocol.Network` and a stable `protocolId` for the ShareNetwork instance.

#### Scenario: List root directory with credentials
- **WHEN** the user opens the ShareNetwork root path
- **THEN** the client issues a Route-mode HTTP GET with `X-API-Request: true`
- **AND** the request includes `pwd` when `ShareNetwork.password` is not blank
- **AND** the User-Agent is derived from `getSocketDevice()`
- **AND** the returned file entries are labeled as `FileProtocol.Network`

### Requirement: ShareNetwork file download
When a user downloads or opens a file from ShareNetwork, the system SHALL download the file bytes from the link-share Route server using `X-API-Request: true`, optional `pwd`, and User-Agent derived from `getSocketDevice()`.

#### Scenario: Download a ShareNetwork file
- **WHEN** the user triggers a download/open for a ShareNetwork file
- **THEN** the client downloads the file via a Route-mode HTTP GET with `X-API-Request: true`
- **AND** the request includes `pwd` when `ShareNetwork.password` is not blank
