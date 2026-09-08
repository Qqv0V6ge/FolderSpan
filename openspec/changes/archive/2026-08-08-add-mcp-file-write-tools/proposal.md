## Why

FolderSpan MCP currently exposes file reading and task-based copy/move/delete operations, but it cannot directly create or update a small file from UTF-8 or Base64 content. Agents therefore cannot perform common configuration, note, or source-file edits even when the Token and endpoint already grant `files.write`.

## What Changes

- Add `folderspan_file_write` for bounded whole-file overwrite/create and append operations on Local, Device, Share, and Network locators.
- Add optional optimistic concurrency fields so callers can reject stale overwrite or append attempts after inspecting file metadata.
- Add `folderspan_directory_create` as an idempotent directory-creation tool.
- Require `files.write`, endpoint write capability, existing path/symbolic-link checks, and the sensitive-path policy before mutation.
- Return refreshed metadata and the number of decoded bytes written; reject invalid encodings, directories, oversized payloads, stale preconditions, and unsupported endpoints with stable errors.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mcp-file-access`: Extend MCP file mutation behavior with bounded direct file writing/appending and idempotent directory creation.

## Impact

- MCP tool registration and JSON input schemas in `McpToolRegistry`.
- `McpFileFacade` write orchestration and response models.
- Existing `FileEndpointGateway` create/write implementations for Local, Device, Share, and Network endpoints.
- MCP file tests, HTTP service/tool discovery tests, and user documentation.
- No database migration or new dependency is required.
