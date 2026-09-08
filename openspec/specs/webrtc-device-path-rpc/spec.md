# webrtc-device-path-rpc Specification

## Purpose
TBD - created by archiving change add-webrtc-path-rpc. Update Purpose after archive.
## Requirements
### Requirement: WebRTC-connected devices SHALL support remote path browsing
The system SHALL retain the WebRTC path RPC method and response contracts. After token and role/path authorization, the receiving service SHALL support list, root-path discovery, existence probing, directory creation, and directory deletion for ordinary paths. It MUST reject protected paths before local filesystem probing or mutation.

#### Scenario: List an authorized ordinary remote path
- **WHEN** an approved device requests `list` for an ordinary path covered by its read permission
- **THEN** the service returns the direct entries
- **AND** protected child entries are labeled and cannot be opened through that label

#### Scenario: Return remote root paths
- **WHEN** an approved device has a configured read permission and requests `rootPaths`
- **THEN** the service returns the platform roots without granting access outside configured permission paths

#### Scenario: Mutate an authorized ordinary directory
- **WHEN** an approved device requests `create-directory` or `delete-directory` within its configured permission range
- **THEN** the existing mutation behavior is preserved

#### Scenario: Reject protected directory access
- **WHEN** an approved device requests a protected path
- **THEN** the request returns an authority failure before protected-path probing or mutation

### Requirement: WebRTC path RPC SHALL reuse the existing permission model
The system SHALL validate WebRTC path RPC requests through the same authentication and role-based device permission model used by HTTP device routes and SHALL additionally enforce the sensitive-path policy. Authentication or role permission success MUST NOT override a protected-path denial.

#### Scenario: Approved peer receives scoped access token
- **WHEN** a target device approves a WebRTC connect request
- **THEN** it issues a scoped access token for that peer session
- **AND** later WebRTC path RPC calls must present that token

#### Scenario: Missing or invalid token
- **WHEN** a WebRTC path RPC request does not provide a valid approval-scoped token
- **THEN** the request is rejected
- **AND** no path operation is executed

#### Scenario: Valid role authorizes an ordinary path
- **WHEN** the peer token is valid, its role grants access, and the target path is not protected
- **THEN** the path RPC may execute the requested operation

### Requirement: 设备路径 RPC 必须在解析链接后执行角色权限

HTTP 与 WebRTC 设备路径操作 MUST 在实际 IO 前使用相同的规范路径和符号链接边界规则执行角色权限校验。权限判断不得仅比较未经解析的路径字符串，也不得允许授权根内的链接把读写范围扩展到根外。

#### Scenario: 通过授权根内链接列举根外目录

- **WHEN** 角色只获授权访问一个目录，且该目录内的链接指向根外
- **THEN** HTTP 和 WebRTC 的列举请求均在读取根外目录前被拒绝

#### Scenario: 通过链接读取或写入根外文件

- **WHEN** 设备路径请求尝试经由授权根内链接读取、上传或创建根外文件
- **THEN** HTTP 和 WebRTC 均返回权限失败
- **AND** 根外文件不被读取、创建或修改

#### Scenario: 通过链接删除或重命名根外目标

- **WHEN** 删除、移动或重命名请求的源或目标路径经过符号链接离开授权根
- **THEN** 请求在任何修改发生前失败

#### Scenario: 两种传输使用一致策略

- **WHEN** 相同角色对相同设备路径分别发起 HTTP 与 WebRTC 请求
- **THEN** 两种传输对符号链接、路径组件边界和权限结果保持一致
