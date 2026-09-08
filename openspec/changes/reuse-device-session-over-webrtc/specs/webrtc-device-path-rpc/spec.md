## MODIFIED Requirements

### Requirement: WebRTC-connected devices SHALL support remote path browsing
The system SHALL expose list, root-path discovery, existence probing, directory creation, and directory deletion through the common Device Session path contracts when Session is carried by WebRTC. After session-bound authentication and role/path authorization, behavior SHALL match the same operation over TLS Session. It MUST reject protected paths before local filesystem probing or mutation.

#### Scenario: List an authorized ordinary remote path
- **WHEN** an approved WebRTC-backed Device Session requests `list` for an ordinary path covered by its read permission
- **THEN** the service returns the direct entries
- **AND** protected child entries are labeled and cannot be opened through that label

#### Scenario: Return remote root paths
- **WHEN** an approved device has a configured read permission and requests `rootPaths`
- **THEN** the service returns the platform roots without granting access outside configured permission paths

#### Scenario: Mutate an authorized ordinary directory
- **WHEN** an approved device requests `create-directory` or `delete-directory` within its configured permission range
- **THEN** the mutation behavior matches the same request over TLS Session

#### Scenario: Reject protected directory access
- **WHEN** an approved device requests a protected path
- **THEN** the request returns an authority failure before protected-path probing or mutation

### Requirement: WebRTC path RPC SHALL reuse the existing permission model
The system SHALL validate WebRTC-backed Device Session path requests through the same session-bound identity, role-based device permission model and sensitive-path policy used by other device transports and HTTP device routes. Authentication or role permission success MUST NOT override a protected-path denial. Individual path calls SHALL NOT require a second WebRTC-specific token field.

#### Scenario: Approved peer establishes a scoped Session identity
- **WHEN** a target device approves a WebRTC connect request and the corresponding Device Session is established
- **THEN** that Session is bound to the approved peer identity and role scope
- **AND** later path RPC calls are authorized from the bound Session identity

#### Scenario: Session authentication is missing or invalid
- **WHEN** a WebRTC path request is received outside a valid approval-bound Device Session
- **THEN** the request is rejected
- **AND** no path operation is executed

#### Scenario: Valid role authorizes an ordinary path
- **WHEN** the Session identity is valid, its role grants access, and the target path is not protected
- **THEN** the path RPC may execute the requested operation
