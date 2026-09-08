## 1. Write orchestration

- [x] 1.1 Add serializable write mode/result models, UTF-8/Base64 decoding, decoded-size validation, and optimistic metadata preconditions
- [x] 1.2 Implement overwrite/create, append, empty truncation, endpoint capability checks, and idempotent directory creation above `FileEndpointGateway`

## 2. MCP tools

- [x] 2.1 Register `folderspan_file_write` and `folderspan_directory_create` under `files.write`
- [x] 2.2 Add strict JSON schemas and argument parsing for encoding, mode, data, and optional metadata preconditions

## 3. Tests and documentation

- [x] 3.1 Add writer contract tests for UTF-8/Base64, overwrite/create, append, empty content, stale preconditions, payload limits, directories, and endpoint capabilities
- [x] 3.2 Add tool registry/scope tests and protected-path regression coverage
- [x] 3.3 Update MCP documentation and `md_descriptions_paths.md`

## 4. Validation

- [x] 4.1 Run strict OpenSpec validation, Core and Shared JVM tests, Android debug assembly, and Core JS/Wasm compilation
