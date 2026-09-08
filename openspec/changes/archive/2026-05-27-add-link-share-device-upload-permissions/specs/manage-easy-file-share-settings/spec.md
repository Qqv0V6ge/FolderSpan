## MODIFIED Requirements

### Requirement: Default link-share options affect only new connections
The system SHALL apply changes to 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、and 默认允许上传 only to newly created link-share sessions or newly authorized connections.

#### Scenario: User changes defaults while clients are already connected
- **WHEN** one or more link-share clients are already authorized or connected
- **AND** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、or 默认允许上传
- **THEN** existing authorized clients keep their current access state, selected files, hidden-file visibility, and upload permission
- **AND** future link-share sessions or new client authorizations use the updated defaults

#### Scenario: User starts a new link-share session after changing defaults
- **WHEN** the user changes 默认分享路径、默认自动允许、默认同设备自动允许、默认密码访问、or 默认允许上传
- **AND** a new link-share session or new client authorization is created after the change
- **THEN** the new session or authorization uses the latest persisted defaults

## ADDED Requirements

### Requirement: Easy Share default upload permission
The system SHALL expose the Easy Share upload setting as the default upload permission for newly authorized link-share devices.

#### Scenario: Default upload enabled for new device
- **WHEN** the default upload setting is enabled
- **AND** a new link-share device is authorized through manual approval, auto-approval, password access, or ticket access
- **THEN** the new device authorization includes upload permission

#### Scenario: Default upload disabled for new device
- **WHEN** the default upload setting is disabled
- **AND** a new link-share device is authorized through manual approval, auto-approval, password access, or ticket access
- **THEN** the new device authorization does not include upload permission
- **AND** the device can request upload permission after it is authorized to browse
