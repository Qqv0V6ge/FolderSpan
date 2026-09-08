## 1. Streaming upload API

- [x] 1.1 Add a sequential upload entry next to `NetworkClient.upload(localPath)` that consumes `ByteArray` chunks (or an equivalent pull source) with known size and progress callback; keep the local-path upload unchanged
- [x] 1.2 Expose the same sequential upload on `NetworkAccess` without going through `uploadFileFromLocal`
- [x] 1.3 Update `UnsupportedNetworkClient` and test fakes (`NetworkCopyToTest`, `NetworkEditorTransferTest`) so they compile against the new entry

## 2. Protocol clients

- [x] 2.1 Implement streaming WebDAV PUT from incoming chunks in `WebDavNetworkClient`, using `FileSimpleInfo.size` / declared size for `Content-Length` when ≥ 0; do not read a local staging file
- [x] 2.2 Implement streaming upload for SFTP, SMB, and FTP on JVM, Android, and iOS by writing incoming chunks to the remote file handle
- [x] 2.3 Implement streaming S3 upload: single-object PUT below the existing threshold; sequential multipart parts from the stream at or above it; on part failure retry the whole object, not local-file resume
- [x] 2.4 If size is unknown and the protocol requires `Content-Length`, fail the copy with a clear error instead of silently staging the complete file

## 3. Device and Share producers

- [x] 3.1 Change `copyDeviceToNetwork` so a non-directory file reads Device chunks (Session / WebRTC) and feeds the streaming upload; stop calling `stageDeviceFileToLocalForNetworkUpload` for the default path
- [x] 3.2 Change `copyDeviceToNetwork` so a directory creates dest folders, streams each contained file, and uses `createFolder` / `createFile` for empty dirs and 0-byte files; stop `copyViaLocalTemp` for this route
- [x] 3.3 Change `copyShareToNetwork` so a remote Share file/folder streams through the share session read path (temporary Device read engine only); do not call `share.copyTo` with a Network dest; do not register the share as a Device
- [x] 3.4 Keep `SYSTEM_SHARE_DESK_ID` on the already-local or content-URI source: stream or `upload(localPath)` when a real local path exists; never open a remote Share session
- [x] 3.5 Bound in-flight data to one Network write chunk; reuse existing Copy/Move task progress (`putResult` / byte or chunk progress). Folder entries MUST NOT write chunk counts into `progressCur`

## 4. Failure, cancel, and non-goals

- [x] 4.1 On cancel or mid-upload error, mark the item failed/canceled, best-effort delete the partial remote file, and do not record the dest as copied; retry starts a new upload
- [x] 4.2 Confirm `shouldPlanRuntimeCopyArchive` and `shouldUseTransferRecoveryForCopyQueue` still exclude dest=Network for Device and Share sources
- [x] 4.3 Leave Local→Network on `upload(localPath)`; leave Network→Network cross-endpoint staging; leave Share.copyTo Local-only; leave browser link-share unchanged

## 5. Tests and validation

- [x] 5.1 Add coordinator/route tests that Device→Network and remote Share→Network call sequential upload and do not write a complete file under `sync-stage`
- [x] 5.2 Add tests that system share → Network never opens a remote Share session and that remote Share → Network does not add the share to the connected-device list
- [x] 5.3 Add tests that a canceled or failed streaming upload is not treated as a successful dest file
- [x] 5.4 Keep existing archive/recovery assertions: Share→Network and Device→Network stay out of archive batches and chunk recovery (`FileTaskArchiveQueuePlannerTest`, `FileRuntimeTaskExecutorRecoveryRouteTest`)
- [x] 5.5 Cover WebDAV streaming PUT (and at least one of SFTP/SMB/FTP or a shared fake) so the PUT/write body is built from chunks rather than a local path
- [x] 5.6 Run `openspec validate --change stream-device-share-to-network --strict` and the affected `:core:jvmTest` copy/network tests
