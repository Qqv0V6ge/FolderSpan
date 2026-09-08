## 1. Implementation
- [ ] 1.1 Add JVM desktop share adapter interface and OS detection
- [ ] 1.2 Add Windows 10+ share UI integration via WinRT interop
- [ ] 1.3 Add macOS 11+ share sheet integration via AppKit interop
- [ ] 1.4 Add Linux xdg-desktop-portal share integration
- [ ] 1.5 Wire JVM `shareSystemItems` to desktop adapters and return false on failure
- [ ] 1.6 Add required JVM dependencies and packaging notes

## 2. Validation
- [ ] 2.1 Manual: Windows share UI opens with multiple selected items
- [ ] 2.2 Manual: macOS share sheet opens with files and folders
- [ ] 2.3 Manual: Linux portal share opens; missing portal shows snackbar
