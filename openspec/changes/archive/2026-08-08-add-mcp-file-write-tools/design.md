## Context

See `proposal.md` for motivation. MCP file endpoints already provide `info`, `createDirectory`, `createFile`, `writeRange`, and `abortWrite`; the transfer coordinator uses those primitives for cross-endpoint copies. No public MCP tool currently turns caller-supplied content into a bounded endpoint write, and the HTTP request limit is 1 MiB.

The implementation must work across Local, Device, Share, and Network without accepting a caller-controlled `FileAccessPermission`. Sensitive-path classification and endpoint-specific role/link checks remain authoritative at the gateway or remote device service.

## Goals / Non-Goals

**Goals:**

- Provide a small, deterministic direct-write API suitable for configuration, text, and bounded binary edits.
- Support whole-file overwrite/create, append, empty-file truncation, and idempotent directory creation.
- Detect stale read-modify-write attempts when callers provide metadata preconditions.
- Reuse endpoint abstractions so every protocol follows the same public contract.

**Non-Goals:**

- Arbitrary unbounded uploads, streaming MCP request bodies, random-offset patching, recursive directory creation, or filesystem transactions.
- Replacing task-based copy/move/delete or the existing large-file editor and transfer protocols.
- Letting MCP sensitivity labels or Token scopes override device roles, endpoint capabilities, or protected-path denials.

## Decisions

### Add one bounded writer above `FileEndpointGateway`

A common writer resolves the locator, validates endpoint capability and current metadata, decodes the payload, and calls the existing endpoint primitives. This keeps protocol-specific I/O in the gateways and makes the write contract independently testable. Adding separate protocol-specific MCP tools was rejected because it would duplicate validation and expose inconsistent behavior.

### Limit decoded content to 512 KiB

The decoded payload limit is 524288 bytes. Base64 expansion plus JSON and MCP framing stays below the existing 1 MiB HTTP request ceiling, while the size is sufficient for typical source, note, and configuration edits. Larger data continues to use copy/transfer paths. A 4 MiB limit matching reads was rejected because Base64 requests would exceed the HTTP body limit.

### Expose overwrite and append, not raw offsets

`overwrite` creates a missing target or replaces the entire regular file; `append` requires an existing regular file. Raw `fileSize` and `offset` remain internal because inconsistent combinations can truncate or sparsely corrupt files. Empty overwrite is a supported truncation operation.

### Use optional metadata preconditions

`expectedSize` and `expectedUpdatedAt` are checked against one metadata snapshot before mutation. They are optional for simple creation and unconditional writes, but allow agents to call info/read and then avoid silently overwriting a changed file. A content hash was rejected because it would require an additional full-file read and does not fit large or remote endpoints.

### Keep directory creation separate and idempotent

`folderspan_directory_create` maps directly to the endpoint directory primitive and creates only the requested leaf. Existing directories return current metadata, while an existing regular file conflicts. Combining directory and file creation into the write tool was rejected because their inputs, error handling, and agent intent differ.

### Validate payloads before endpoint resolution

Encoding, decoded size, mode, and non-negative preconditions are validated before endpoint lookup. Scope enforcement remains in tool dispatch, while endpoint capability and path safety are checked before mutation. Errors never include supplied content or a denied target path.

## Risks / Trade-offs

- [Endpoint write failure can leave a partially replaced target] → Keep writes to one bounded range, return a stable failure, and use `abortWrite` only for a target newly created during the current request when safe to do so.
- [Metadata can change between precondition check and write] → Document preconditions as optimistic rather than transactional; endpoint-native atomic compare-and-write is outside scope.
- [Base64 expansion approaches the HTTP body limit] → Enforce the decoded 512 KiB limit before endpoint resolution and retain the server-wide 1 MiB request cap.
- [Endpoint implementations differ on missing targets] → Normalize overwrite/create and append/not-found behavior in the common writer and verify every gateway through contract tests.
- [Direct writes increase MCP mutation impact] → Require `files.write`, endpoint write capability, existing device-role checks, sensitive-path rejection, and symbolic-link safety for every call.

## Migration Plan

1. Add common write request/result models and writer orchestration without changing existing tools.
2. Register the two new tools and schemas under the existing `files.write` scope.
3. Add endpoint-contract, tool-discovery, authorization, size, encoding, precondition, and protected-path tests.
4. Update MCP documentation and run OpenSpec, JVM, Android, JS, and Wasm validation.

Rollback removes the two tool registrations and writer orchestration; existing read, transfer, endpoint, and sensitivity behavior remains unchanged.
