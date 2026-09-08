## Context

See `proposal.md` for motivation. `PathUtils` and `FileUtils` are Kotlin Multiplatform expect objects with Android, JVM, iOS, JS, and Wasm actuals and more than one hundred callers. Existing device services apply role permissions above the utility layer, while MCP applies token scopes and endpoint checks. Neither mechanism forces the lowest common filesystem boundary to know whether the trusted caller permits I/O.

The original implementation used the caller-supplied decision as a blanket external-access switch. That made every device and MCP Local operation fail even after role or Token authorization succeeded. The revised policy separates authorization from data sensitivity: existing scopes and device path permissions authorize ordinary paths, while a source-backed protected-path registry denies application-private and recovery data.

## Goals / Non-Goals

**Goals:**
- Make every filesystem-touching utility call explicit and fail closed.
- Prevent denied calls from observing path existence, logging targets, creating tasks, or mutating data.
- Apply one error contract across all platforms while preserving existing method return types.
- Document sensitive storage locations and maintenance rules from source-controlled evidence.
- Preserve configured device/MCP access to ordinary paths and expose a stable sensitivity label for protected entries.

**Non-Goals:**
- Operating-system ACL changes, new encryption, database migrations, or a wire-format permission field.
- Disabling Share or Network endpoint behavior that does not invoke a denied local utility context.

## Decisions

### Use a two-state required enum with no default

Define `FileAccessPermission` in the common utilities package with `Allowed` and `Denied`. Add it as a required argument to filesystem-touching methods and convenience extensions. Pure path-string helpers such as app/home/cache path lookup and separator lookup do not require it.

This is preferred over a Boolean because call sites remain self-documenting and the enum can be extended deliberately. It is preferred over a default `Allowed` because defaults allow missed migrations to bypass the boundary.

### Guard at the first executable statement of each actual operation

A common guard creates `AuthorityException` without including the target path. Each platform actual checks before normalization, provider lookup, existence probing, directory enumeration, logging, or I/O. Result APIs capture the exception as failure; Flow APIs emit one failure; boolean probes return false; other legacy non-Result APIs throw. Directory listing does not return partial results when denied.

Checking only at services was rejected because lower-level or future callers could bypass it. Changing every return type to `Result` was rejected because it would add unrelated API churn.

### Propagate trusted context at service boundaries

Local UI, editor, synchronization, persistence, and cleanup flows pass `Allowed`. Device HTTP/WebRTC path and file services first authenticate, apply role/path permissions, classify the requested path without filesystem probing, and pass `Allowed` only for a non-sensitive authorized path. MCP Local applies the same classification after Token-scope checks. MCP-owned staging uses `Allowed` internally at an application-selected staging path, while that staging directory itself remains protected from MCP Local browsing. Device role permissions and MCP scopes remain necessary but cannot authorize a protected path.

### Keep authorization and sensitivity as separate concepts

`FileAccessPermission` remains the final low-level I/O decision and does not encode whether a file is sensitive. `SensitiveFileAccessPolicy` owns lexical path classification using platform-specific application-private roots plus maintained named storage locations. Classification returns `none`, `sensitive`, or `critical` and a stable category identifier. Invalid or unclassifiable external paths fail closed. This avoids the previous error where `Denied` was used as an external-caller identity rather than an operation decision.

Directory listing may expose a protected child's name only when its parent was independently authorized. Such entries are marked with their sensitivity and category, have no advertised file capabilities, and reject info/content/mutation requests before probing the child path.

### Maintain a source-backed, graded storage inventory

Create `docs/sensitive-files-and-directories.md` with critical identity/credential storage and sensitive user-content/operational storage. Record stable names, dynamic filename patterns, platform base locations, contents, compromise effects, and authorized owners. Do not publish live values or user-specific paths. Update `md_descriptions_paths.md` whenever the inventory or OpenSpec Markdown files are added.

## Risks / Trade-offs

- [Large source-compatible break across many call sites] → Use compiler errors as the migration inventory and keep the enum argument mandatory.
- [Platform implementation misses an early guard] → Add representative denied tests for every return shape and inspect all actual declarations.
- [Existing device and MCP Local features are accidentally disabled] → Cover authorized ordinary-path access for list, metadata, content, and mutation with integration tests.
- [Caller-controlled policy can misclassify future contexts] → Keep external boundary constructors explicit, prohibit request-derived permission values, and require the storage inventory to document ownership.
- [A protected child is encountered during an authorized directory transfer] → Reject the protected entry before content I/O and never allow its sensitivity label to become an access grant.
- [Dirty worktree contains unrelated changes] → Restrict edits to permission migration, targeted tests/docs, and additive Markdown index entries.

## Migration Plan

1. Add the enum, guard, common expect signatures, and platform actual signatures.
2. Migrate all compilation errors: trusted local contexts to `Allowed`, and external contexts to an explicit sensitive-path decision.
3. Add ordinary-path regression tests, protected-path denial tests, and update device/MCP compatibility expectations.
4. Add the sensitive storage inventory and Markdown index entries.
5. Run OpenSpec strict validation, JVM tests, shared tests, Android build, and JS/Wasm compilation checks.

Rollback requires reverting the enum/signature migration and the modified external behavior together; partial rollback is unsafe because mixed call sites would not compile or could bypass the intended boundary.
