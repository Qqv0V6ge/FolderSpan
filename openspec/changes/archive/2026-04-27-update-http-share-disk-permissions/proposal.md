# Change: Enforce link-share Disk permissions

## Why
Link-share serving currently assumes shared entries can be resolved through local filesystem helpers. Files from other Disk types need protocol-aware access, and Disk entries with `menuPermission.share == false` must not be exposed by the HTTP share server.

## What Changes
- Resolve link-share entries by `FileSimpleInfo.protocol` and `protocolId` instead of treating every path as local.
- Filter link-share authorization snapshots so only files from Disks with `share=true` are exposed.
- Re-check Disk share permission during HTTP route handling to prevent stale or externally injected authorization state from bypassing permissions.

## Impact
- Affected specs: `browse-share-network`
- Affected code: `FileShareState`, `HttpShareFileServerCommon`, device/share byte-read helpers
