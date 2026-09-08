## ADDED Requirements

### Requirement: Native discovery remains continuous across resume

On desktop, Android, and iOS, native discovery SHALL continuously listen for signed LAN beacons while the application runtime is active. Startup and resume SHALL NOT launch a separate device scan. Web JS and Wasm startup and resume scans SHALL continue to use the existing WebRTC discovery path.

#### Scenario: Native resume keeps continuous discovery

- **WHEN** a native app starts or becomes active again
- **THEN** the existing LAN beacon listener remains the only automatic discovery mechanism
- **AND** the app does not start a scanner job or send HTTPS ping requests
- **AND** it does not present a scan or pause-scan action

#### Scenario: Web resume scan stays on WebRTC discovery

- **WHEN** a JS or Wasm tab becomes visible and starts a device scan
- **THEN** discovery continues to use the existing WebRTC path
- **AND** the client does not listen for native UDP beacons as a device session server
