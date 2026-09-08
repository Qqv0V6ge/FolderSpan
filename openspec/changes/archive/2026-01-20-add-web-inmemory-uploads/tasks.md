## 1. Implementation
- [x] 1.1 Add a JS/Wasm in-memory file store for dropped files/folders with path normalization, overwrite semantics, list/traverse helpers, and file size storage.
- [x] 1.2 Implement `PathUtils` for JS and Wasm to use the in-memory store (getFileAndFolder, traverse, exists, create/delete directory, root paths).
- [x] 1.3 Add a document-level drag-and-drop handler in `composeApp/src/webMain` that reads `FileState.path`, defaults to `/`, extracts files/folders, reads file sizes, and writes to the in-memory store.
- [x] 1.4 Refresh the file list after drop by invoking `fileState.updateFileAndFolder()`.
- [x] 1.5 Handle name collisions by overwriting existing entries in the target path.

## 2. Validation
- [x] 2.1 Manual: drop files on root and verify they appear in `/`.
- [x] 2.2 Manual: drop a folder while inside a subfolder and verify the structure is preserved under that path.
