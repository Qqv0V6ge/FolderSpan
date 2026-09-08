## ADDED Requirements
### Requirement: Browser device connect uses WebRTC
The system SHALL use the existing device scan/connect flow for WASM/JS clients and route browser-device connections through WebRTC over HTTP signaling.

#### Scenario: Native app hosts browser WebRTC automatically
- **WHEN** the native app starts the device HTTP service
- **THEN** the app joins the HTTP signaling hub as host
- **AND** no new drawer widget is required

#### Scenario: WASM or JS connects from discovered device list
- **WHEN** the WASM or JS user scans or manually adds an app HTTP device and presses connect
- **THEN** the client joins the HTTP signaling hub as a browser peer
- **AND** sends the WebRTC connection request to the app host

#### Scenario: Existing room UI remains unchanged
- **WHEN** the user opens the existing WebRTC room screens
- **THEN** those screens continue to use WebSocket room configuration and Room ID validation
