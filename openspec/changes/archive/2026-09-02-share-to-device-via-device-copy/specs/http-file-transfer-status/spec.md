## ADDED Requirements

### Requirement: Native App device-share Save SHALL tune from the device transport
Save and Auto-Save of a native App device share SHALL take transfer chunk size and parallelism from the device Session credit plan or WebRTC transfer plan. They SHALL NOT require `/api/share/read-bytes` transfer-status headers for that copy.

#### Scenario: Session share save uses session windows
- **WHEN** a native App device-share Save runs over Session
- **THEN** frame size and in-flight bytes follow the session receive window
- **AND** distinct files MAY use concurrent streams within that window

#### Scenario: WebRTC share save uses WebRTC transfer plan
- **WHEN** a native App device-share Save runs over WebRTC
- **THEN** chunk size and concurrency follow the negotiated WebRTC transfer plan

#### Scenario: Link-share HTTP still exposes share transfer status
- **WHEN** a browser or HTTP share client reads `/api/share/read-bytes`
- **THEN** the response still includes `X-FolderSpan-Transfer-*` headers
