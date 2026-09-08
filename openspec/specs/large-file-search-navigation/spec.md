# large-file-search-navigation Specification

## Purpose
TBD - created by archiving change enhance-large-file-editor. Update Purpose after archive.
## Requirements
### Requirement: Asynchronous bounded search
The system SHALL execute whole-file and multi-page searches outside the UI thread using bounded range reads, SHALL publish progress and result batches, and SHALL remain responsive while searching.

#### Scenario: Search a huge file
- **WHEN** a search starts across a file larger than available memory
- **THEN** the engine scans bounded chunks, emits matches incrementally and never aggregates the complete file

#### Scenario: Results arrive before completion
- **WHEN** early chunks contain matches
- **THEN** those matches appear in the result list while later chunks continue scanning

#### Scenario: Search is cancelled
- **WHEN** the user cancels an active search
- **THEN** pending reads and matching work stop, progress becomes cancelled and already returned results remain inspectable

### Requirement: Search modes
The system SHALL support text and regular-expression queries, including matches that cross internal read boundaries.

#### Scenario: Text match crosses chunks
- **WHEN** a text query begins at the end of one chunk and ends in the next
- **THEN** the engine returns one match at the correct absolute byte range

#### Scenario: Regex search
- **WHEN** a regular expression produces matches whose individual spans do not exceed 1 MiB
- **THEN** the engine evaluates bounded overlapping windows, removes duplicate window results and reports the matches

#### Scenario: Regex span exceeds limit
- **WHEN** a pattern requires an individual match spanning more than 1 MiB
- **THEN** the UI reports the supported span limit instead of silently returning an incomplete match

### Requirement: Text search options
The system SHALL support case-sensitive or case-insensitive matching, whole-word matching and the active text encoding for text and regular-expression searches.

#### Scenario: Case-sensitive search
- **WHEN** case sensitivity is enabled
- **THEN** only text with the same case is returned

#### Scenario: Whole-word search
- **WHEN** whole-word matching is enabled
- **THEN** a candidate is returned only when its decoded boundaries are not word characters

#### Scenario: Non-UTF text search
- **WHEN** a GBK or UTF-16 file is searched in text mode
- **THEN** query and file content are matched as decoded text while results retain exact source byte offsets

### Requirement: Search direction and range
The system SHALL support forward and backward navigation over matches and SHALL restrict searches to the current page, current selection or entire file as selected by the user.

#### Scenario: Search current selection
- **WHEN** the user selects a byte or text range and chooses Current selection
- **THEN** the engine reads and reports matches only within that range

#### Scenario: Search current page
- **WHEN** Current page is selected
- **THEN** search completes against the displayed page without scanning other pages

#### Scenario: Navigate backward
- **WHEN** the user requests the previous result
- **THEN** the editor jumps to the preceding result by absolute byte offset and wraps only if wrap navigation is enabled

### Requirement: Multiple keyword search
The system SHALL accept multiple text keywords, search them in one pass where possible, and tag every result with the keyword that matched.

#### Scenario: Any-keyword search
- **WHEN** multiple keywords are submitted with Any semantics
- **THEN** a result is returned for each occurrence of any keyword and identifies that keyword

#### Scenario: Overlapping keyword matches
- **WHEN** two keywords match overlapping ranges
- **THEN** both logical results are retained and can be selected independently

### Requirement: Search results and history
The system SHALL provide a result list with absolute offset, available line number, contextual preview, keyword tag and completion state, and SHALL maintain at most 50 synchronized editor-search history entries.

#### Scenario: Result list reaches memory limit
- **WHEN** more than 10,000 matches are found
- **THEN** the UI retains a bounded result detail set, continues the total count and explains that additional details were truncated

#### Scenario: Open result
- **WHEN** the user activates a result
- **THEN** the editor loads the containing page, scrolls to the result and highlights its exact range

#### Scenario: Search history update
- **WHEN** a non-empty search is executed
- **THEN** its query type and options are moved to the front of the independent editor-search history with duplicate removal and the oldest entry removed beyond 50

### Requirement: Background line index
The system SHALL build a cancellable sparse line index after first-page display, SHALL publish indexed progress and SHALL cache completed checkpoints in application-private storage using the source fingerprint.

#### Scenario: First page before index completion
- **WHEN** a large text file is opened without a cached line index
- **THEN** the first page is displayed immediately and indexing starts in the background

#### Scenario: Cached index matches source
- **WHEN** a cached index has the same source fingerprint
- **THEN** line-number jumps can use it without rescanning earlier file content

#### Scenario: Cached index is stale
- **WHEN** file fingerprint differs from the cached index
- **THEN** the stale index is ignored and rebuilt without modifying the source

### Requirement: Navigation targets
The system SHALL support jumps to line number, absolute byte offset, percentage position and search result.

#### Scenario: Jump to byte offset
- **WHEN** the user enters an offset within the file
- **THEN** the editor loads the containing page and places the caret or selection at that byte

#### Scenario: Jump to percentage
- **WHEN** the user enters a percentage from 0 through 100
- **THEN** the editor maps it to a valid byte position using file size and displays the containing page

#### Scenario: Jump beyond indexed lines
- **WHEN** a requested line is beyond the currently indexed range
- **THEN** indexing is prioritized toward that line, progress remains visible, and the jump completes or reports that the line does not exist

#### Scenario: Invalid target
- **WHEN** the entered line, offset or percentage is invalid
- **THEN** no navigation occurs and the UI displays validation feedback
