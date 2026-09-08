## Purpose

让用户把设备或远程分享中的文件粘贴到网络盘时，字节从源端直接流入网络盘上传，而不再把完整文件写到本机临时目录。

## ADDED Requirements

### Requirement: Device-to-network copy SHALL stream without staging the complete file
When a user copies or moves a file from a connected device to a network disk, the system SHALL upload the file bytes as they are read from the device. The system SHALL NOT write the complete source file to a local cache or `sync-stage` path before the network upload starts.

#### Scenario: Copy a device file to a network directory
- **WHEN** the user pastes a non-directory file from a connected device into an FTP, SFTP, SMB, WebDAV, or S3 directory
- **THEN** the file is uploaded to that network path
- **AND** no complete copy of the source file exists under the local sync-stage cache after the operation finishes

#### Scenario: Copy a large device file without filling local cache
- **GIVEN** a device file larger than available local cache space for a full copy
- **WHEN** the user copies that file to a network disk
- **THEN** the copy still proceeds by streaming
- **AND** the operation does not fail solely because a complete local staging file could not be written

### Requirement: Remote share-to-network copy SHALL stream without staging the complete file
When a user copies or moves a file from a remote Share desk to a network disk, the system SHALL upload the file bytes as they are read from the share session. The system SHALL NOT write the complete source file to a local cache or `sync-stage` path before the network upload starts. The share session SHALL NOT be registered as a Device.

#### Scenario: Copy a remote share file to a network directory
- **WHEN** the user pastes a non-directory file from a remote Share desk into a network directory
- **THEN** the file is uploaded to that network path
- **AND** the share remains a Share desk rather than a Device desk
- **AND** no complete copy of the source file exists under the local sync-stage cache after the operation finishes

#### Scenario: Share-to-network does not open a Device desk
- **WHEN** a remote Share file is copied to a network disk
- **THEN** the system does not add that share to the connected-device list
- **AND** the copy still uses the share session's file-read path

### Requirement: Directory copies to network SHALL stream each file
When the source is a directory on a device or remote Share, the system SHALL create the corresponding folders on the network disk and stream each contained file. Empty directories SHALL be created on the network disk. The system SHALL NOT archive the directory to a local zip or other complete local payload solely to perform the network upload.

#### Scenario: Copy a device folder to network
- **WHEN** the user pastes a folder from a connected device into a network directory
- **THEN** the folder tree is created on the network disk
- **AND** each contained file is streamed to the corresponding network path
- **AND** empty child directories exist on the network disk after success

#### Scenario: Copy a remote share folder to network
- **WHEN** the user pastes a folder from a remote Share desk into a network directory
- **THEN** the folder tree is created on the network disk
- **AND** each contained file is streamed to the corresponding network path

### Requirement: System share to network SHALL use the already-local source
When the source is a system share (`SYSTEM_SHARE_DESK_ID`), the system SHALL read the already-local file or content URI and upload it to the network disk. The system SHALL NOT open a remote Share session for that source.

#### Scenario: Paste a system-share file to network
- **WHEN** the user pastes a system-share file into a network directory
- **THEN** the file is uploaded from the local or content-URI source
- **AND** no remote Share session is created for that copy

### Requirement: Failed streaming uploads SHALL NOT leave a completed destination file
If a streaming upload to a network disk fails or is canceled before the source is fully written, the destination SHALL NOT be treated as a successful complete file. When the network protocol can delete the partial object, the system SHALL remove it.

#### Scenario: Cancel mid-upload
- **WHEN** the user cancels a Device-to-network or Share-to-network copy while a file is still uploading
- **THEN** the task finishes as canceled
- **AND** the incomplete network file is not reported as copied successfully

#### Scenario: Transfer error mid-upload
- **WHEN** the device, share session, or network upload fails after some bytes were sent
- **THEN** the copy item is recorded as failed
- **AND** a later retry of that item starts a new upload rather than treating the partial remote file as complete

### Requirement: Network destinations remain outside archive and chunk-recovery planning
Copy and move tasks whose destination is a network disk SHALL NOT plan small-file archive batches and SHALL NOT use device chunk-recovery for those entries, including Device-to-network and Share-to-network.

#### Scenario: Share to network is not archived
- **WHEN** a copy task queues remote Share files whose destination is a network disk
- **THEN** those entries are not placed in a small-file archive batch

#### Scenario: Device to network does not use chunk recovery
- **WHEN** a large device file is copied to a network disk
- **THEN** the copy does not use the device chunk-recovery resume path

### Requirement: Local-to-network upload remains a local-path upload
When the source is already on the local disk, the system MAY keep uploading from the local path. This change SHALL NOT require local-to-network copies to go through a remote stream producer.

#### Scenario: Paste a local file to SFTP
- **WHEN** the user pastes a local file into an SFTP directory
- **THEN** the file is uploaded from the local path
- **AND** the operation does not first copy that local file into sync-stage
