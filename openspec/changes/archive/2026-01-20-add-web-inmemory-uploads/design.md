## Context
Web (JS/Wasm) builds do not have filesystem access, so PathUtils and FileUtils are stubbed. Users need drag-and-drop uploads to populate a local, in-memory file tree without any server upload.

## Goals / Non-Goals
- Goals:
  - Capture files and folders dropped on the document in JS/Wasm.
  - Store dropped data in an in-memory tree keyed by normalized paths, recording file sizes without reading file content.
  - Implement JS/Wasm PathUtils against the in-memory tree.
  - Respect the current directory path for drop targets; default to root when unset.
  - Overwrite existing entries on name collisions.
- Non-Goals:
  - Persistence across reloads or sessions.
  - Upload buttons or file picker UI.
  - Server uploads or share-service integration.
  - Full FileUtils support for web.

## Decisions
- Introduce a JS/Wasm in-memory file store (directory tree + file sizes) with helpers:
  - normalize paths (`/`-based), list children, check existence, create/delete directories, and traverse.
  - overwrite by removing the target subtree before inserting new nodes.
- Implement a document-level drop handler in `composeApp/src/webMain`:
  - On drop, read the current path from `FileState.path` and default to `/` when blank.
  - Parse dropped items using `DataTransfer.items` with `webkitGetAsEntry` when available to preserve folders; fall back to `dataTransfer.files` for flat file lists.
  - Reuse the extraction approach from `shared/src/commonMain/composeResources/files/share-file/static/websocket/websocket-upload.js` for drag-and-drop item handling to stay consistent with existing web upload behavior.
  - Read `File.size` and write size metadata into the in-memory store at the target path.
  - Trigger `fileState.updateFileAndFolder()` after writes to refresh the UI.

## Alternatives considered
- Relying solely on `dataTransfer.files`: simpler but loses folder structure on directory drops.
- Adding a web upload button: out of scope per request.

## Risks / Trade-offs
- Folder drag-and-drop relies on browser-specific APIs (`webkitGetAsEntry`) and may not work in all browsers; fallback will only handle files.
- In-memory storage has no persistence and is limited by browser memory.

## Migration Plan
- Add the in-memory store and PathUtils implementations.
- Add the web drop handler and refresh behavior.
- Manual validation in JS and Wasm builds.

## Open Questions
- None.
