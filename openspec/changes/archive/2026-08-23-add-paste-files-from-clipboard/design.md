## Context

See [proposal.md](proposal.md) for motivation and [paste-clipboard-files spec](specs/paste-clipboard-files/spec.md) for observable behavior.

The current clipboard feature has a common `ClipboardContent(texts, filePaths)` model and platform readers under `app/shared`, but its only consumer parses entries and opens/highlights local paths. That model intentionally flattens values to text and paths, so it cannot preserve Android content URIs, iOS item providers, browser `File` objects, temporary permissions, or cleanup ownership.

External file drag-and-drop already accepts those platform-native sources, but the post-drop behavior is fragmented:

- Desktop resolves an AWT file list, opens the source location on a local disk, and directly enqueues copies for device/network targets.
- Android converts granted URIs into the in-process Share registry and normally navigates to a temporary Share desk.
- iOS stages item-provider results, converts them to local `FileSimpleInfo` values, and enqueues copies to a resolved target.
- Web converts browser file objects into `WebInMemoryFileStore` entries and currently writes them directly into the visible path.

The normal copy/paste path in `FilePasteTaskExecutor` already owns conflict selection and task semantics. External-source cleanup is not part of that API, and its task creation currently returns no task identifiers to a caller that needs to retain resources.

## Goals / Non-Goals

**Goals:**

- Establish one common import boundary after platform-native clipboard or drag payloads have been prepared as readable file sources.
- Route clipboard file paste through the existing conflict planner and copy-task runtime instead of maintaining a second transfer implementation.
- Keep external sources readable across conflict selection, asynchronous execution, failure/retry, cancellation, and process-lifecycle cleanup where the platform permits recovery.
- Preserve existing “从剪贴板打开” behavior and make shortcut interception independently testable.
- Allow drag-and-drop and clipboard paste to share source preparation without forcing them to share user-facing destination behavior.
- Convert clipboard-only image bitmaps or `image/*` blobs from browsers, image viewers, and screenshot tools into leased external files on every platform.

**Non-Goals:**

- Implement cut/move semantics or write application files back to the system clipboard.
- Treat plain-text paths, `file://` text, URL text, or arbitrary non-image rich content as file payloads.
- Guarantee directory paste on platforms whose clipboard provider does not expose a readable directory tree.
- Redesign the existing drag-and-drop navigation behavior or all file-operation dialogs.
- Introduce a new cross-platform filesystem API or change public network/device transfer protocols.

## Decisions

### 1. Separate platform payload preparation from common file import

Introduce a common `PreparedExternalFileBatch`-style contract in `core/commonMain` containing:

- prepared top-level `FileSimpleInfo` sources;
- skipped-entry diagnostics;
- a stable batch/lease identifier when temporary resources are owned by the app;
- source metadata needed for deduplication and user feedback, but no platform-native object.

Platform source sets prepare native clipboard or drag data into this contract. An `ExternalFileImportCoordinator` accepts the prepared batch, a captured destination, and the existing `FileOperationState`.

```text
                    ┌─ Desktop: AWT file list ───────────┐
Clipboard / Drop ──▶├─ Android: content URI preparation ─┤
                    ├─ iOS: item-provider staging ────────┤
                    └─ Web: browser File staging ─────────┘
                                      │
                                      ▼
                         PreparedExternalFileBatch
                                      │
                                      ▼
                         ExternalFileImportCoordinator
                                      │
                     ┌────────────────┴────────────────┐
                     ▼                                 ▼
             conflict resolution               resource lease binding
                     │                                 │
                     └───────────────┬─────────────────┘
                                     ▼
                            existing copy tasks
```

The common contract belongs in `core` because both core-level Desktop/Android handlers and `app/shared` UI/platform adapters must use it without reversing module dependencies.

Alternatives considered:

- Extending `ClipboardContent.filePaths`: rejected because strings lose URI/file-object capabilities and resource ownership.
- Synthesizing drag events from clipboard data: rejected because platform security grants are event-specific and existing drop handlers include drag-only UI behavior.
- Keeping four independent paste implementations: rejected because conflict, retry, and cleanup semantics would diverge.

### 2. Capture and validate the destination before reading files

The shared paste controller captures a destination snapshot containing protocol, protocol id, path, separator, and a directory `FileSimpleInfo` before invoking a platform reader. It checks the current desk's paste permission first, avoiding unnecessary clipboard privacy prompts when the target cannot accept files.

The coordinator always uses this snapshot. If the directory disappears or becomes inaccessible, the operation fails against the captured target rather than silently following later navigation or falling back to a home directory.

Alternative considered: resolve `fileState.path` after slow platform staging. Rejected because navigating during preparation could send files to an unexpected directory.

### 3. Reuse each platform's drag source preparation, not its complete drop action

- **Desktop/JVM:** extract AWT `javaFileListFlavor` parsing and local `FileSimpleInfo` creation into a reusable preparer. Original local paths require no temporary lease. When no file list exists but `DataFlavor.imageFlavor` is present, encode the AWT image into a leased PNG staging file. Drop keeps its existing local-navigation behavior; clipboard paste always sends the prepared sources to the common coordinator.
- **Android:** split URI enumeration/registry construction from `ShareHandler.handleDroppedFiles`, returning top-level Share sources and owned registry paths. Clipboard `ClipData.Item.uri` values remain typed URIs and are never passed through the text parser. Image content URIs use provider bytes and MIME metadata, including generated names when necessary. Sources without a durable permission are staged into an app-owned area before tasks are created; readable directory/tree URIs retain relative paths.
- **iOS:** move the reusable `NSItemProvider` loading/staging portion of `IosDropImportCoordinator` behind a Kotlin-callable external-item preparation entry. Both UIDrop and `UIPasteboard.itemProviders` use it, including `UTType.image` data representations and `UIPasteboard.image` PNG fallback, then forward staged URLs through one source conversion path.
- **Web JS/Wasm:** add a document paste listener alongside `ExternalFileDropEffect`. It extracts `clipboardData.items/files` synchronously during the trusted event, accepts `image/*` `Blob` values even when they are not named `File` objects, gives them generated names, converts them to the same browser item model used by drop, and stages them under a hidden `WebInMemoryFileStore` batch root before common import. JS and Wasm implementations remain behaviorally identical.

Platform preparers deduplicate entries using the strongest available stable identity, falling back to normalized source path/name plus size and modification time. Directory support is conditional on the provider exposing traversal; unsupported directories become skipped diagnostics.

### 4. Extend the existing paste executor with external-source lease binding

Add a dedicated `pasteExternalFiles` path on `FileState`/the paste executor rather than calling `copyTo` directly. It reuses `FilePasteOperationPlanner` and task creation, while accepting an optional external-resource lease.

Task registration and lease binding must be atomic from the coordinator's perspective. The executor records the lease id in task metadata and returns an enqueue result containing task keys and skipped/conflict-cancel status. Internal copy/move callers can continue using the current API with no lease.

The lease remains retained while any related task can still be continued or retried. It is released when all references are removed because tasks succeeded, were canceled/deleted, or can no longer be retried—not merely when a handler invocation returns. A batch that produces no tasks is released immediately.

Alternative considered: copy directly using the private Desktop/iOS drop helpers. Rejected because those paths bypass `FilePasteOperationPlanner` and duplicate task error handling.

### 5. Make temporary resource ownership explicit and recoverable

Introduce an external-source lease registry with reference counting by task key. A lease may own Android Share-registry paths, an Android/iOS staging root, or a Web memory-store root; Desktop local sources normally use a no-op lease.

For filesystem-backed staging, persist minimal lease metadata with the staging root and related task ids. Startup reconciliation retains roots referenced by recoverable tasks and removes orphaned roots. Task deletion/cancellation releases the final reference. Web memory sources are runtime-only and report a diagnostic failure after a page reload rather than pretending they are recoverable.

Staging copies stream through bounded buffers and use the existing file metadata/traversal helpers; the design must not load an entire file into memory. Android may use a readable durable content URI directly only when its lifetime is demonstrably sufficient for task retry; otherwise it stages eagerly.

Alternative considered: clean resources in the platform callback immediately after enqueue. Rejected because task execution is asynchronous and failed tasks may still need their source for retry.

### 6. Keep file paste UI state separate from “open from clipboard”

Add a shared clipboard-file paste controller/effect that owns target capture, busy/reentrancy protection, result feedback, and invocation of the platform preparer. The existing `ClipboardOpenState` and text/path parser remain unchanged.

The AppDrawer more-options area exposes a distinct paste-files action. Desktop and Web also install a shortcut/paste-event route active only in the file-browser context. Focus classification is implemented as a small pure policy so tests can prove editable controls, dialogs that accept text, and the file editor retain normal paste behavior. Repeated paste events for the same user gesture are coalesced by a per-invocation id.

### 7. Return structured preparation and paste results

Platform preparation and common import return structured counts and diagnostic categories such as unsupported type, unreadable source, permission denied, missing target, and enqueued count. UI code maps these results to the project's existing user-feedback surface and localized strings; raw clipboard contents and sensitive content URIs are not included in user-visible errors.

Valid entries continue when sibling entries fail. When no task is created, the controller produces one concise result instead of an empty task. Logging may include sanitized counts and failure categories, while existing “从剪贴板打开” logging behavior remains unchanged.

### 8. Normalize clipboard-only images without bypassing file tasks

Platform readers prefer a real file representation when one exists. If an image has no file identity, they create one leased source entry named `clipboard-image-<epochMillis>[-<index>].<extension>`. Existing encoded bytes retain their safe image extension; native bitmap objects are encoded as PNG. The generated source is then indistinguishable from other prepared external files to the common coordinator, so target snapshots, conflicts, progress, cancellation, retry, and cleanup stay unchanged.

Equivalent representations from one clipboard item are collapsed before staging. Image generation is bounded by the platform clipboard payload already materialized by the source application; temporary output remains owned by the existing lease until every dependent task terminates.

## Risks / Trade-offs

- **[Android clipboard URI access may expire or vary by provider]** → Prefer eager streaming to app-owned staging unless durable access is verified; surface per-item permission failures.
- **[Large staged files temporarily require additional storage]** → Check available space where supported, stream with bounded memory, clean failed partial staging immediately, and retain only batches referenced by tasks.
- **[Large clipboard bitmaps can consume memory while being encoded]** → Prefer already encoded `image/*` bytes, encode native bitmaps once as PNG off the UI thread, reject failed/invalid representations, and immediately remove partial staging output.
- **[Temporary resources can leak after crashes]** → Persist filesystem lease metadata and reconcile it with recoverable tasks at startup.
- **[Failed retryable tasks retain large staging roots]** → Release resources when the task is deleted/canceled and make retained external-source storage visible to existing task cleanup paths.
- **[Global paste handling can break text editing]** → Gate by active screen and editable-focus policy, and test file editor, search, rename, dialog, and ordinary file-list focus.
- **[Directory clipboard representations differ across platforms]** → Preserve directories only when traversal is exposed; otherwise skip with explicit feedback rather than flattening or guessing.
- **[Web JS and Wasm copies can drift]** → Keep their model and algorithms structurally identical and cover both source sets with the same behavioral fixtures where possible.
- **[Refactoring drag preparation can regress existing drop behavior]** → First extract characterization-tested preparers without changing drop destinations, then add clipboard callers.

## Migration Plan

1. Add the common batch/result/lease contracts and characterize current drag and paste-task behavior without exposing the new UI.
2. Refactor platform drag source preparation behind the new adapters, keeping existing drop destinations and share-list delivery behavior unchanged.
3. Add the common external import coordinator, paste-executor lease integration, and resource reconciliation.
4. Add platform clipboard acquisition and the explicit paste-files action; then enable Desktop/Web shortcut handling with focus guards.
5. Roll out platform tests and manual smoke tests before enabling the action in release builds.

No stored user data migration is required. Rollback can remove the new action and paste listeners while leaving the extracted drag preparers in place. Any rollback build must retain orphan-staging cleanup long enough to remove leases created by a previous build.
