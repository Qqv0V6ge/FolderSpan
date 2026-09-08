## Why

`PathUtils` and `FileUtils` currently accept only paths, so trusted local features, device RPC handlers, and MCP gateways can reach the same filesystem primitives without an explicit security decision. Sensitive application data such as the database, settings, TLS identity, editor recovery data, and transfer state therefore needs a mandatory caller-controlled access boundary and a maintained inventory.

## What Changes

- Add a required `FileAccessPermission` enum to every filesystem-touching `PathUtils` and `FileUtils` API, with explicit `Allowed` and `Denied` states and no permissive default.
- Reject `Denied` before path probing, logging, task creation, or filesystem I/O, using failure shapes appropriate to each existing API.
- Keep trusted local flows on `Allowed`; device services and MCP operations derive the final utility decision from existing role/Token authorization plus a maintained sensitive-path registry.
- Classify externally listed entries as `none`, `sensitive`, or `critical`, and expose a non-secret category label while denying direct access to protected entries.
- Add a normative document listing sensitive and important files/directories, their platform locations, risks, and ownership rules.
- **BREAKING**: authenticated device and MCP file operations remain subject to a non-overridable sensitive-path policy even when role or Token scopes otherwise permit access.
- **BREAKING**: callers of filesystem-touching `PathUtils` and `FileUtils` methods must supply the new permission argument.

## Capabilities

### New Capabilities
- `file-utility-access-control`: Defines mandatory explicit permission checks for common filesystem utilities and the sensitive-data inventory contract.

### Modified Capabilities
- `mcp-file-access`: MCP Local access and local staging must be rejected before I/O or task creation when the utility permission is denied.
- `webrtc-device-file-rpc`: Device file RPC operations must not override the common utility denial with a valid token or role permission.
- `webrtc-device-path-rpc`: Device path RPC listing, probing, creation, and deletion must respect the common utility denial.

## Impact

- Common and platform-specific `PathUtils`/`FileUtils` declarations, implementations, extensions, and tests across Android, JVM, iOS, JS, and Wasm.
- Local UI, editor, synchronization, task persistence, sharing, HTTP/WebRTC device services, MCP file gateways, and MCP-owned staging that call those utilities.
- Existing device and MCP behavior, tests, metadata contracts, and OpenSpec contracts.
- Repository documentation and `md_descriptions_paths.md`; no database migration, wire-format enum, operating-system ACL, or encryption algorithm change.
