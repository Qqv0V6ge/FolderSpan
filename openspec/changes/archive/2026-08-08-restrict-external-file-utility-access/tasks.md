## 1. Common permission boundary

- [x] 1.1 Add the required `FileAccessPermission` enum and common authority guard without a permissive default
- [x] 1.2 Add the permission parameter to filesystem-touching `PathUtils` and `FileUtils` expect APIs while leaving pure path-string helpers unchanged
- [x] 1.3 Update Android, JVM, iOS, JS, and Wasm actuals so denied calls fail before path probing or I/O with the specified return-shape behavior

## 2. Caller migration and external policy

- [x] 2.1 Migrate trusted local UI, editor, sync, persistence, cleanup, share, and test callers to explicit `Allowed`
- [x] 2.2 Migrate device HTTP/WebRTC path, file, and copy services to `Denied` without allowing role permissions to upgrade access
- [x] 2.3 Migrate MCP Local gateway and MCP-owned local staging/cleanup to `Denied`, rejecting operations before task creation

## 3. Documentation

- [x] 3.1 Add `docs/sensitive-files-and-directories.md` with source-backed critical and sensitive storage inventory, platform locations, risks, and ownership rules
- [x] 3.2 Update `md_descriptions_paths.md` for the sensitive storage document and all active OpenSpec artifacts in this change

## 4. Tests and validation

- [x] 4.1 Add common/JVM tests for allowed regression and denied Result, Flow, boolean probe, throwing API, listing, read, and mutation behavior
- [x] 4.2 Update device and MCP tests to assert authority failures occur before filesystem side effects or task creation
- [x] 4.3 Run OpenSpec strict validation, core/shared JVM tests, Android debug build, and JS/Wasm compilation checks; resolve all failures attributable to this change

## 5. Correct over-broad external denial

- [x] 5.1 Revise the design and capability specs so configured device/MCP permissions authorize ordinary paths while protected paths remain denied
- [x] 5.2 Add a cross-platform sensitive-path registry with stable sensitivity/category labels and fail-closed lexical matching
- [x] 5.3 Restore MCP Local, MCP staging, and device service access for authorized ordinary paths; keep protected paths and symbolic links denied
- [x] 5.4 Expose sensitivity metadata for listed MCP entries without turning the label into an access grant
- [x] 5.5 Replace blanket-denial tests with ordinary-path success and protected-path denial coverage, then run strict OpenSpec and targeted builds/tests
