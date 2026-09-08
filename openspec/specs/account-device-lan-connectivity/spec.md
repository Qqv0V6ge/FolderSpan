# account-device-lan-connectivity Specification
## Purpose

让已登录用户在开启一个简单设置后，能够通过现有局域网扫描自动连接并自动同意当前账号下的可信设备，同时在身份不可验证、会话失效或设备被移除时安全回退到原有人工流程。

## Requirements

### Requirement: Account device trust comes from a fresh signed-in device list
The system SHALL build account-device trust only while the feature is enabled and a valid signed-in session exists.
The trusted snapshot SHALL contain only devices currently registered to that account, SHALL exclude the local device, SHALL be scoped to the current session, and SHALL include the verifiable device identity material supplied by the account-device contract.
The system MUST NOT use a long-lived profile-page cache as an authorization source.

#### Scenario: Feature is enabled while signed in
- **WHEN** the user enables the feature while a valid signed-in session exists
- **THEN** the system obtains a fresh account-device list for the current session
- **AND** builds a session-scoped trusted snapshot excluding the local device

#### Scenario: User signs in while the feature is enabled
- **WHEN** a valid account session becomes available while the feature is enabled
- **THEN** the system obtains a fresh account-device list before granting account-based automatic trust

#### Scenario: Trusted snapshot expires
- **WHEN** the trusted snapshot exceeds its allowed freshness window and cannot be refreshed
- **THEN** the system stops granting new account-based automatic trust
- **AND** leaves the existing manual connection and approval flows available

#### Scenario: Account session changes
- **WHEN** the user signs out, the session becomes invalid, or another account replaces the active session
- **THEN** the system immediately clears the prior session's trusted account-device snapshot
- **AND** does not reuse it for the new or missing session

### Requirement: LAN discovery drives account-device automatic connection
The system SHALL evaluate each device returned by native LAN beacon discovery against the current trusted account-device snapshot.
It SHALL automatically start the existing direct device session connection flow only when the discovered device is a remote device registered to the current account and its discovery identity proof is fresh and valid.
It SHALL NOT create or modify a permanent per-device auto-connect preference as a result.

#### Scenario: Trusted account device is discovered on the LAN
- **WHEN** LAN beacon discovery receives a device whose registered identity and fresh discovery proof match the trusted account-device snapshot
- **AND** the device is not connected or being connected
- **THEN** the system starts one automatic direct session connection attempt

#### Scenario: Discovered device is the local device
- **WHEN** LAN discovery receives the local device identity
- **THEN** the system does not attempt to connect to it

#### Scenario: Device is permanently rejected
- **WHEN** a discovered account device has a local permanent-reject policy
- **THEN** the system does not automatically connect to that device

#### Scenario: Discovery is repeated
- **WHEN** repeated beacons announce the same eligible device while it is connected or being connected
- **THEN** the system does not start a duplicate connection attempt

#### Scenario: Discovery identity proof is absent or invalid
- **WHEN** a discovered device has only a matching `device_key`, or its discovery identity proof is missing, expired, replayed, or invalid
- **THEN** the system does not automatically connect through account trust
- **AND** does not treat `device_key` alone as proof of account-device identity

### Requirement: Verified account devices are approved automatically on the LAN
The system SHALL automatically approve an inbound direct device connection only when the feature is enabled, the local session and trusted snapshot are valid, the requester is registered to the same account, and the request contains a fresh non-replayed identity proof that verifies against the registered device identity.
Account-based approval SHALL use an existing valid per-device role when available, otherwise the built-in least-privilege role, and SHALL NOT require a user-configured access key or role.
Each direct connection request SHALL explicitly select either standard authorization or account-device authorization, and each response SHALL explicitly state whether account-device trust authorized it.
An account-device authorization request that cannot satisfy every account proof condition SHALL be rejected rather than downgraded to the standard authorization flow.

#### Scenario: Verified account device requests a connection
- **WHEN** an inbound LAN connection request identifies a device in the trusted snapshot
- **AND** its fresh request proof verifies against that registered device identity
- **THEN** the system automatically approves the connection using the normal connection response
- **AND** grants no broader permission than the resolved existing or least-privilege role

#### Scenario: Account authorization request contains only a matching device key
- **WHEN** an inbound request selects account-device authorization and claims a registered `device_key` but provides no valid identity proof
- **THEN** the system rejects the request
- **AND** does not downgrade it to the standard authorization flow

#### Scenario: Current client requests standard authorization
- **WHEN** an inbound request explicitly selects standard authorization
- **THEN** the system follows the existing non-account authorization flow
- **AND** does not infer account authorization from `device_key`

#### Scenario: Request proof conflicts with registered identity
- **WHEN** an inbound request's identity proof does not match the registered account-device identity
- **THEN** the system rejects account-based authorization
- **AND** records only a sanitized identity-mismatch reason

#### Scenario: Request proof is replayed
- **WHEN** a previously accepted account-device proof is submitted again within or outside its validity window
- **THEN** the system does not automatically approve the replayed request

#### Scenario: Requester is permanently rejected
- **WHEN** a verified same-account requester has a local permanent-reject policy
- **THEN** the permanent rejection takes precedence
- **AND** the system does not automatically approve the request

### Requirement: Account trust is revoked without a persistent online channel
The system SHALL refresh account-device membership at bounded lifecycle points without opening an account-device WebSocket or requesting an account-device WebSocket ticket.
When a refreshed list removes a device, the system SHALL revoke its temporary trust, cancel pending account-based work, and close any connection still marked as established solely by that temporary trust.

#### Scenario: Application resumes with an eligible session
- **WHEN** the application returns to the foreground while the feature and session remain eligible
- **THEN** the system refreshes an expired or near-expiry trusted snapshot before granting new automatic trust

#### Scenario: Device is removed from the account list
- **WHEN** a refreshed account-device list no longer contains a previously trusted device
- **THEN** the system removes its temporary trust
- **AND** cancels pending account-based connection or authorization work for that device
- **AND** closes a connection that is still dependent solely on that temporary trust

#### Scenario: Client is eligible for account auto connection
- **WHEN** the feature is enabled and a valid signed-in session exists
- **THEN** the client does not call the account-device WebSocket ticket operation
- **AND** does not open the account-device WebSocket

### Requirement: Existing device workflows remain available
The account-device LAN feature SHALL be additive to existing LAN discovery, manual connection, manual approval, certificate checks, and permanent device policies.
When a device is not attempting explicit account-device authorization and any account eligibility, freshness, membership, or identity condition is absent, the system SHALL grant no additional trust and SHALL continue through the applicable existing workflow.

#### Scenario: Feature is disabled
- **WHEN** a device is discovered or requests a connection while the feature is disabled
- **AND** the request uses standard authorization
- **THEN** the system follows the existing connection and authorization behavior unchanged

#### Scenario: Account service is unavailable
- **WHEN** the account service cannot refresh device membership and no sufficiently fresh trusted snapshot exists
- **THEN** the system does not grant account-based automatic trust
- **AND** manual discovery, connection, and approval remain available

#### Scenario: Non-account device is discovered
- **WHEN** LAN discovery receives a device that is not in the trusted account-device snapshot
- **THEN** the device remains available through the existing non-account device workflow
