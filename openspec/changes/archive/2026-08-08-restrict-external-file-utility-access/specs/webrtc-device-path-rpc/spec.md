## MODIFIED Requirements

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
