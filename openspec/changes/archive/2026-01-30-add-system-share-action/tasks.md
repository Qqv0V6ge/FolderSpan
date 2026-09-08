## 1. Implementation
- [x] 1.1 Add a system share entry and selection flow in `FileShareScreen` (system share mode)
- [x] 1.2 Define a common system share data model and expect API
- [x] 1.3 Implement Android system share using FileProvider URIs
- [x] 1.4 Implement iOS system share using UIActivityViewController
- [x] 1.5 Implement JVM system share (best-effort; return unsupported when unavailable)
- [x] 1.6 Implement JS/Wasm/Web system share using the Web Share API when available
- [x] 1.7 Surface unsupported share via snackbar in common UI

## 2. Validation
- [x] 2.1 Manual: system share uses checked items; empty selection shares all
- [x] 2.2 Manual: system share can include a folder without compression
- [x] 2.3 Manual: web target uses Web Share API when available
