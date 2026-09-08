## Context

See proposal.md for why Device→Network and Share→Network must stop staging complete files.

Today both routes land in `FileStateCopyCoordinator`:

- `copyDeviceToNetwork` stages a file with `stageDeviceFileToLocalForNetworkUpload` (directories go through `copyViaLocalTemp`).
- `copyShareToNetwork` always uses `copyViaLocalTemp`. System share copies the already-local source into the same temp tree.

The destination then calls `networkAccess.copyTo` → `NetworkClient.upload(localPath, ...)`. Download already has `ChunkReadableNetworkClient.downloadByChunks`; upload has no counterpart. Network→Device already streams the other direction (`copyNetworkFileToDeviceDirect` feeds `downloadFileByChunks` into device `writeBytes`).

Share.copyTo still requires `destProtocol == Local` and only borrows a temporary `Device` for the Device→Local engine. That constraint stays: this change does not make Share a writable target or a registered Device.

## Goals / Non-Goals

**Goals:**

- Add a sequential upload source that Network clients can consume without a local path.
- Device→Network and remote Share→Network copy files by reading source chunks and writing them to that upload source.
- Keep Local→Network on `upload(localPath)`.
- Preserve current task progress, pause/cancel, and failure recording for these routes.
- Keep Share→Network / Device→Network out of archive batches and chunk recovery.

**Non-Goals:**

- Registering Share as Device, or changing system share into a Device session.
- Browser link-share HTTP uploads.
- Enabling small-file archive or device chunk-recovery when dest is Network.
- Streaming Network→Network across different endpoints (those can keep local staging).
- Multipart/resume semantics for streamed S3 uploads beyond a single successful PUT or a failed-and-retry-from-start upload.
- Changing Share.copyTo so it accepts a Network destination directly.

## Decisions

### 1. Add a chunk-writable Network upload, keep local-path upload

Add a sequential upload API next to `upload(localPath)`, shaped like the existing download chunk callback but reversed: the Network client pulls or is fed `ByteArray` chunks until `size` bytes (or EOF) and reports progress.

`upload(localPath)` stays. Local→Network, editor saves, and any caller that already has a file on disk keep the current path. Streaming is the extra entry for remote sources.

Alternative considered: change `upload` to take only a stream and make local-path upload a wrapper. Rejected — every protocol client and test fake would churn, and S3 multipart already keys off a seekable local file.

Alternative considered: keep staging but stream through a pipe/fifo. Rejected — still a local filesystem artifact, and WASM/iOS content URIs do not give a reliable fifo.

### 2. Coordinator streams; Share still copies only to Local

`copyDeviceToNetwork` / `copyShareToNetwork` become the producers:

1. Resolve the Network access and Device or Share session.
2. If the source is a directory, create the dest folder, traverse, and stream each file (empty dirs → `createFolder`, 0-byte files → `createFile`).
3. If the source is a file, read device/share chunks and feed `upload` streaming.
4. System share (`SYSTEM_SHARE_DESK_ID`) reads the local/content-URI source and feeds the same streaming upload (or `upload(localPath)` when the source is already a real local path). It never opens a remote Share session.

Share.copyTo keeps `destProtocol == Local`. The Network route does not call `share.copyTo(task, source, networkDest)`. Remote Share reads go through the existing session `readBytes` / Device-backed file client, constructing a temporary Device only as a read engine, never registering it.

This matches Network→Device: the coordinator already bridges two protocols instead of teaching one protocol to speak the other.

Alternative considered: teach `Network.copyTo` to accept Device/Share sources. Rejected — NetworkAccess would need Device/Share clients, and Share's Local-only invariant would leak into Network.

Alternative considered: teach `Share.copyTo` / `Device.files.copyTo` to accept Network dest. Rejected — those engines are Local/Device writers; Network upload, progress, and partial-file cleanup belong in the Network client.

### 3. Protocol clients consume the stream at the PUT/write boundary

Each Network client already streams *out* of a local file:

- WebDAV PUT `WriteChannelContent` reads `FileUtils.readFileChunks(localPath)`.
- SFTP/SMB/FTP copy a local source stream into the remote write stream.
- S3 small objects PUT from local bytes; large objects use multipart keyed by local path.

Replace the local-file open with the sequential source for the streaming entry:

- WebDAV: `writeTo` writes incoming chunks to the PUT channel (same `Content-Length` when size is known).
- SFTP/SMB/FTP: write incoming chunks to the remote file handle.
- S3: if size is under the single-PUT threshold, buffer-or-stream a single object PUT. If size is at or above the multipart threshold, upload sequential parts from the stream (part N is produced as chunks arrive; no local file). If size is unknown, use multipart or a single PUT only when the protocol requires Content-Length — prefer failing fast with a clear error over staging the whole object. Known size from Device/Share file metadata is the common case.

Do not invent a second WebDAV protocol. Token/Digest/Basic headers stay on the existing client.

Alternative considered: only implement streaming on WebDAV and keep staging for FTP/SFTP/SMB/S3. Rejected — the user-visible bug is Device/Share → any Network disk, and the coordinator should not branch on protocol.

### 4. Bounded memory, not a complete second copy

The in-flight window is one Network write chunk (reuse existing WebDAV/SFTP chunk sizes or the device relay chunk size), not the whole file. Device reads stay on the current Session/WebRTC credit window. Do not accumulate the file in RAM to satisfy Content-Length; use the source `FileSimpleInfo.size` when it is non-negative.

If a protocol truly cannot start an upload without a seekable local file and known length, that protocol may stage *only that file* and must log why. That is an exception path, not the Device/Share default.

### 5. Failure and cancel leave no successful dest

On cancel or error after some bytes were sent:

- Mark the task item failed/canceled (existing task result path).
- Best-effort `delete` the partial remote file when the protocol supports it.
- Do not record the dest as copied.
- Retry starts a new upload. This change does not add S3 multipart resume for streamed sources.

Device chunk-recovery and FSAR archive stay disabled for Network destinations (`shouldUseTransferRecoveryForCopyQueue` / `shouldPlanRuntimeCopyArchive` already exclude dest=Network). Streaming does not change that: Network uploads in this app are not byte-range resumable except S3 multipart-from-local, which we are not extending to Device/Share in this change.

### 6. Progress stays on the Copy/Move task

Reuse the current task `putResult` / byte or chunk progress used by Local→Network and Device→Local. Folder entries keep per-file status; they do not write chunk counts into `progressCur`. Single-file overall chunk progress may follow the Network write chunks when that is what the upload loop observes.

Do not introduce a second progress channel.

## Risks / Trade-offs

- [S3 multipart today seeks a local file] → Streamed multipart must build parts from sequential chunks; if a part fails, retry the whole object, not a local-file resume. Document this as a trade-off versus Local→S3.
- [WebDAV/FTP servers that require Content-Length] → Use `FileSimpleInfo.size` when ≥ 0. If size is unknown, fail with a copy error rather than silently staging. System-share content URIs that cannot report size may fall back to local-path upload when the OS gives a real path.
- [Partial remote files after failure] → Best-effort delete; some servers will leave an object. Retry overwrites or replaces by the existing Network upload contract.
- [Backpressure mismatch between device read window and Network write] → Bound the coordinator to one in-flight Network chunk so Device credit cannot fill RAM. Throughput may be lower than stage-then-upload on fast LAN + slow disk; that is accepted to avoid filling cache.
- [Share session drop mid-upload] → Existing Share disconnect handling still applies; the Network partial file is deleted best-effort and the item fails.

## Migration Plan

- No user-facing setting and no persisted schema change.
- Ship behind the existing Copy/Move routes; Local→Network behavior is unchanged.
- Rollback is reverting the coordinator to `copyViaLocalTemp` / `stageDeviceFileToLocalForNetworkUpload` and leaving the extra upload API unused.

## Open Questions

None. Protocol-specific streaming adapters are implementation tasks, not spec forks.
