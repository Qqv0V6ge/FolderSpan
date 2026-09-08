# Change: Add open-from-clipboard action

## Why
Users need a quick way to open a directory based on clipboard contents across all platforms.

## What Changes
- Add an "Open from Clipboard" action in AppDrawer MoreOptionsDropdown.
- Parse clipboard items (file list, path strings, URLs) and open directories for valid paths.
- Present a selection dialog when multiple path candidates exist.
- Log URL entries via LogKit.

## Impact
- Affected specs: open-clipboard-items (new)
- Affected code: AppDrawer UI, clipboard reader/parser utilities, platform clipboard integrations
