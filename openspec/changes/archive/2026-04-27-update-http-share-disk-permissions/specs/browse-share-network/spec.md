## ADDED Requirements
### Requirement: Link-share server Disk permissions
The link-share HTTP server SHALL expose only shared files whose source Disk has `DiskMenuPermission.share == true`.

#### Scenario: Shareable Disk files are served
- **WHEN** an authorized link-share request targets a file from a Disk with `share=true`
- **THEN** the server resolves the file through that Disk protocol
- **AND** the server returns listing or download content using the existing link-share response format

#### Scenario: Non-shareable Disk files are rejected
- **WHEN** a link-share authorization snapshot contains a file from a Disk with `share=false`
- **THEN** the file is omitted from link-share listings
- **AND** direct route access to that file is rejected without reading its path as a local file
