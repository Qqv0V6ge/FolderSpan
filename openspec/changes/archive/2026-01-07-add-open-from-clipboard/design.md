## Context
This change adds a cross-platform "Open from Clipboard" action in the AppDrawer menu. Clipboard formats vary by platform (text, file lists, URLs), so a shared parser is needed to normalize entries before opening a local directory.

## Goals / Non-Goals
- Goals:
  - Provide an AppDrawer action that reads clipboard content on all platforms.
  - Parse clipboard entries into path and URL candidates with minimal heuristics.
  - Open a local directory for a valid path entry; show a selection dialog when multiple paths exist.
  - Log URL entries using LogKit.
- Non-Goals:
  - Opening URLs in a browser.
  - Resolving Android content:// URIs into file paths.
  - Syncing clipboard contents across devices.

## Decisions
- Introduce a small common model (e.g., ClipboardEntry with type/path/url) and a parser that accepts text lines plus optional file lists.
- Add expect/actual clipboard readers in composeApp to gather clipboard text and file list data per platform.
- Treat file URLs (file://...) as path candidates after decoding; treat http/https and other schemes as URLs and log them.
- Validate path candidates with FileUtils/PathUtils; if a candidate is a file, open its parent directory.
- When multiple valid path entries exist, display a Material3 AlertDialog with a selectable list; a single entry opens directly.
- Use LogKit.i to log each URL entry detected during parsing.

## Risks / Trade-offs
- Android clipboard URIs may not map to filesystem paths; these will be logged but not opened.
- Web clipboard access may require permission; JS/WASM should degrade gracefully when access is unavailable.

## Migration Plan
- Add common clipboard parsing utilities and models.
- Implement platform clipboard readers.
- Integrate the new action and dialog into AppDrawer.
- Add parser tests for edge cases (file URL, multi-line text, mixed items).

## Open Questions
- None.
