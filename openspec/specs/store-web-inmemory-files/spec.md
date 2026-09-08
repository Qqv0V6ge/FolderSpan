# store-web-inmemory-files Specification

## Purpose
TBD - created by archiving change add-web-inmemory-uploads. Update Purpose after archive.
## Requirements
### Requirement: Web drag-and-drop uploads stored in memory
The JS/Wasm builds SHALL accept document-level drag-and-drop of files and folders and store them in an in-memory file tree without uploading to a server or writing to disk. Dropped items SHALL be written under the current directory path, defaulting to `/` when no directory is selected, SHALL overwrite existing entries with the same name, and SHALL record file sizes without reading file content.

#### Scenario: Drop into root when no directory selected
- **WHEN** the user drops files while no directory is selected
- **THEN** the files are stored under `/`
- **AND** the file list refresh shows the dropped items

#### Scenario: Drop into current directory
- **WHEN** the user navigates into `/docs`
- **AND** drops files or folders
- **THEN** the items are stored under `/docs`
- **AND** the file list refresh shows the dropped items in `/docs`

#### Scenario: Folder structure preserved
- **WHEN** the user drops a folder containing nested files
- **THEN** the in-memory tree preserves the folder structure under the target path

#### Scenario: Name collision overwrites
- **WHEN** a dropped item has the same name as an existing entry in the target path
- **THEN** the existing entry is overwritten by the dropped item

### Requirement: Web PathUtils reads from in-memory uploads
The JS/Wasm PathUtils implementation SHALL read from the in-memory upload tree for directory listing, traversal, existence checks, and directory create/delete operations.

#### Scenario: List directory from memory
- **WHEN** PathUtils lists `/docs`
- **THEN** it returns the in-memory children stored under `/docs`

#### Scenario: Traverse directory from memory
- **WHEN** PathUtils traverses `/`
- **THEN** it emits the in-memory directory contents for each directory in the tree

