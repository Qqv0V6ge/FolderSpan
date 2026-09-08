## Purpose

App 之间「分享到其他设备」在对方批准后建立设备连接，并用与设备复制到本地相同的传输管线搬运文件，而不是走分享 HTTP 字节接口。

## Requirements

### Requirement: Approved device share SHALL upgrade to a device connection
After a native App device-share request is approved, the two devices SHALL establish or reuse a device connection using Session on LAN and WebRTC when Session is unavailable, instead of keeping an HTTP share file session as the byte channel.

#### Scenario: LAN share save uses Session
- **WHEN** the receiver approves Save or Auto-Save
- **AND** a Session connection to the sender can be established
- **THEN** the subsequent file copy uses that Session connection

#### Scenario: WAN share save uses WebRTC
- **WHEN** the receiver approves Save or Auto-Save
- **AND** Session is not available
- **AND** a WebRTC device connection to the sender can be established
- **THEN** the subsequent file copy uses that WebRTC connection

#### Scenario: Share HTTP is not the file byte channel
- **WHEN** an approved native App device-share transfer copies files
- **THEN** the client SHALL NOT read file bytes through `/api/share/read-bytes`, `/api/share/stream-file`, or `/api/share/archive-download`

### Requirement: Device-share Save SHALL copy through the device-to-local pipeline
Save and Auto-Save SHALL copy the shared file list from the sender device to the receiver's save path using the same copy pipeline as copying from a connected device to local storage.

#### Scenario: Save a shared file
- **WHEN** the receiver saves a shared file
- **THEN** the file is copied from the sender device path to the local save path through the device copy pipeline
- **AND** progress, pause, cancel, and failure reporting match device-to-local copies

#### Scenario: Save a shared directory
- **WHEN** the receiver saves a shared directory
- **THEN** the directory tree is copied through the device copy pipeline
- **AND** the operation uses the same task runtime queue as other device directory copies

### Requirement: Device-share View SHALL open the sender as a device desk
View SHALL present the sender as a connected device whose visible files are limited to the shared list, not as an HTTP share disk.

#### Scenario: View approved
- **WHEN** the receiver selects View
- **AND** the device connection succeeds
- **THEN** the app opens the sender device desk
- **AND** listing and later copies use the device file client

#### Scenario: View cannot browse unshared paths
- **WHEN** View is connected
- **AND** the receiver requests a path outside the shared list
- **THEN** the sender SHALL deny the request

### Requirement: Device-share access SHALL be limited to the shared list
A device connection created for a device-share SHALL only authorize the files and directories in that share list.

#### Scenario: Shared local file is readable
- **WHEN** the sender shared a local file
- **THEN** the receiver's device client can read that file

#### Scenario: Unshared path is rejected
- **WHEN** the receiver's device client requests a path that is not in the share list
- **THEN** the sender returns a forbidden or not-found result
- **AND** the copy does not proceed

### Requirement: Link share and system share remain HTTP
Browser link-share pages and platform system share SHALL keep their existing HTTP or OS share channels.

#### Scenario: Browser downloads a link-share folder
- **WHEN** a browser client downloads from a link-share page
- **THEN** it continues to use the HTTP share server or browser ZIP path
- **AND** it does not require a native device Session

### Requirement: Native App share approval SHALL use its advertised TLS HTTP endpoint
The native App SHALL send `/api/share/heartbeat` to the peer's separately advertised approval endpoint and SHALL
NOT send HTTP/1.1 to the ALPN-only device Session port.

#### Scenario: Session and approval ports differ
- **WHEN** a discovered native peer advertises a Session port and a share approval port
- **THEN** approval short polling uses certificate-pinned HTTPS on the share approval port
- **AND** the later approved file connection uses ALPN `folderspan/1` on the Session port

#### Scenario: Approval endpoint metadata is missing or invalid
- **WHEN** a Session peer omits its approval port, advertises an invalid port, or advertises the Session port as its approval port
- **THEN** discovery or approval endpoint resolution SHALL reject that peer endpoint
- **AND** the client SHALL NOT fall back to sending HTTP/1.1 to the Session port
