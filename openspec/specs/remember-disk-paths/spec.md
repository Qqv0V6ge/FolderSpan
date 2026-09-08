# remember-disk-paths Specification

## Purpose
TBD - created by archiving change remember-disk-paths. Update Purpose after archive.
## Requirements
### Requirement: Remember last path per disk
The system SHALL remember the last visited path for each DiskBase instance (Local, Device, Share, Network) within the current app session.

#### Scenario: Path is stored per disk
- **WHEN** a user navigates to a new path on a specific disk
- **THEN** the system records that path for that disk for the current session

### Requirement: Restore last path on disk switch
The system SHALL open the remembered path for the selected disk when switching, if a path was previously recorded in the current session. If no path is recorded, existing default navigation behavior applies. Path validation is not required.

#### Scenario: Switch back to a disk
- **WHEN** a user switches from Local to a Device and back to Local
- **THEN** the Local view opens at the previously recorded path for Local

