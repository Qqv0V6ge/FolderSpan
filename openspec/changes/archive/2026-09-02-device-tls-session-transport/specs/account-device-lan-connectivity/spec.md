## MODIFIED Requirements

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
