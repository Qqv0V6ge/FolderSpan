# Change: Add JS/Wasm in-memory uploads via drag-and-drop

## Why
Web builds currently cannot accept uploads or drag-and-drop files/folders. Users need to drop files into the web app without uploading to a server, and then browse them via the existing file UI.

## What Changes
 - Add a JS/Wasm in-memory file store that captures dropped files and folders, recording file sizes without reading file content.
- Implement `PathUtils` for JS/Wasm to read from the in-memory store (list, traverse, exists, create/delete directory, root paths).
 - Register a document-level drag-and-drop handler for JS/Wasm that writes dropped items into the in-memory store for the current directory, overwriting existing entries, without persisting to disk or uploading to a server.

## Impact
- Affected specs: `store-web-inmemory-files` (new)
- Affected code:
  - `shared/src/jsMain/kotlin/com/folderspan/utils/PathUtils.js.kt`
  - `shared/src/wasmJsMain/kotlin/com/folderspan/utils/PathUtils.wasmJs.kt`
  - `composeApp/src/webMain` (new drop handler integration)
  - New JS/Wasm in-memory store under `shared/src/jsMain` and `shared/src/wasmJsMain`

## Non-Goals
- Persisting uploads across page refreshes.
- Upload buttons or file pickers (drag-and-drop only for now).
- Server uploads or share-service integration.
- Expanding `FileUtils` web support beyond listing/traverse in this change.
