# manage-favorites Specification

## Purpose
TBD - created by archiving change update-recent-favorite-open. Update Purpose after archive.
## Requirements
### Requirement: Persist favorite protocol metadata
The system SHALL store favorites with the file's protocol and protocolId.

#### Scenario: Favorite a device item
- **WHEN** the user favorites a file on a device desk
- **THEN** the favorite record includes protocol=Device and the device id as protocolId

### Requirement: Open favorite entries
Selecting a favorite folder SHALL open that folder path on the desk resolved by protocol + protocolId.
Selecting a favorite file SHALL open the file on the resolved desk.
If the resolved desk is not currently connected (Device/Share/Network), the system SHALL attempt to connect and open the target path on success.
If the target desk cannot be resolved, the system SHALL show a snackbar message "目标不可用" and remain on the current desk.

#### Scenario: Open favorite with auto-connect
- **WHEN** the user selects a favorite on a Device/Share/Network desk that is discoverable but not connected
- **THEN** the app attempts to connect and opens the target path on success

#### Scenario: Favorite target unavailable
- **WHEN** the user selects a favorite whose protocol/protocolId cannot be resolved
- **THEN** the app shows a snackbar message "目标不可用"
- **AND** no navigation occurs

