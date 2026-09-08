# Change: Add ignore file filtering

## Why
File browsing currently has hidden-file visibility and extension filtering, but it does not understand project ignore files. Users need ignored paths to be visually identified locally and excluded when another device browses or downloads files served by this device.

## What Changes
- Store enabled ignore file names in per-path preferences.
- Resolve ignore behavior from the nearest ancestor directory that has a non-empty ignore file configuration.
- Parse common ignore file syntax and match paths relative to the configured directory.
- Mark locally viewed ignored entries with lower opacity without hiding them.
- Filter ignored entries and reject direct access on device/share HTTP serving paths.

## Impact
- Affected specs: `filter-directory-list`
- Affected code: SQLDelight `FilePathPreference`, file filter state, file list UI, ignore parser, device path/file services, share routes, raw HTTP dispatcher
