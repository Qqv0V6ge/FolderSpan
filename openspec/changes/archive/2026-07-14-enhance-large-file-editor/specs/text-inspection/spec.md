## ADDED Requirements

### Requirement: Text encoding detection
The system SHALL detect and report ASCII, UTF-8, UTF-16 LE, UTF-16 BE, GBK and ISO-8859-1 using BOM and bounded content samples, and SHALL expose the confidence and BOM state.

#### Scenario: BOM identifies encoding
- **WHEN** a file begins with a supported BOM
- **THEN** the matching encoding and BOM are selected without displaying the BOM as text

#### Scenario: ASCII file is detected
- **WHEN** the sampled content contains only valid ASCII bytes
- **THEN** the editor reports ASCII and decodes the page without replacement characters

#### Scenario: Detection is uncertain
- **WHEN** no higher-confidence encoding matches the sample
- **THEN** the editor falls back to ISO-8859-1 and visibly marks the detection as low confidence

### Requirement: Manual encoding switching
The system SHALL allow the user to reinterpret text with any supported encoding and SHALL use the selected encoding when converting edited text during save.

#### Scenario: User changes encoding
- **WHEN** the user selects a different encoding
- **THEN** visible pages are decoded again with that encoding while source bytes remain unchanged

#### Scenario: Target encoding cannot represent text
- **WHEN** saving text containing characters unavailable in the selected encoding
- **THEN** the encoder substitutes the configured replacement byte and reports every replacement through the save preview summary

#### Scenario: Encoding boundary crosses pages
- **WHEN** a multibyte character is split across two display pages
- **THEN** decoding preserves the complete character without duplicating, dropping or replacing its bytes

### Requirement: Newline recognition and conversion
The system SHALL identify CRLF, LF, CR and mixed newline content across chunk boundaries and SHALL preserve original newline bytes unless the user explicitly selects a conversion.

#### Scenario: CRLF crosses a read boundary
- **WHEN** a chunk ends with CR and the next chunk begins with LF
- **THEN** the pair is counted and displayed as one CRLF newline

#### Scenario: Mixed newline file
- **WHEN** more than one newline form occurs in the file
- **THEN** the editor reports Mixed and preserves each original newline during an ordinary save

#### Scenario: User converts newlines
- **WHEN** the user selects LF, CRLF or CR conversion and confirms save
- **THEN** all decoded line endings are written in the selected form and the conversion is listed in the save preview

### Requirement: Text presentation controls
The system SHALL provide one text editor presentation with line numbers, automatic line wrapping and optional visualization of spaces, tabs and newline markers without changing file content.

#### Scenario: Toggle invisible characters
- **WHEN** the user enables invisible-character display
- **THEN** spaces, tabs and newline boundaries receive distinct visual markers while copied or saved text remains unchanged

#### Scenario: Toggle line wrapping
- **WHEN** the user disables automatic wrapping
- **THEN** long lines remain on one visual row and can be scrolled horizontally

#### Scenario: Presentation preferences persist locally
- **WHEN** the user changes line-number visibility or automatic wrapping and later reopens an editor
- **THEN** the editor restores both choices from local settings while keeping them out of cross-device synchronization

#### Scenario: Line index is incomplete
- **WHEN** a requested page is displayed before background line indexing reaches it
- **THEN** the editor shows available provisional line information and updates it when the index catches up without blocking the page

#### Scenario: Non-text bytes are displayed
- **WHEN** a page contains bytes that do not form high-confidence text
- **THEN** the editor keeps the text presentation, uses the detected fallback encoding and shows low-confidence feedback

### Requirement: Bounded adjacent page presentation
The system SHALL keep page rendering memory-bounded, SHALL prefetch the previous and next page after the current page is displayed, and SHALL require a completed boundary pull followed by release before navigating to an available adjacent page.

#### Scenario: Current page opens
- **WHEN** a page is loaded successfully
- **THEN** available adjacent pages are requested in cancellable background tasks and stored in the bounded page cache

#### Scenario: User scrolls to a prefetched page
- **WHEN** vertical scrolling navigates to a page already present in the cache
- **THEN** the editor displays it without issuing a duplicate full-page source read

#### Scenario: Interactive read takes priority
- **WHEN** the user requests a page while background prefetch is still running
- **THEN** lower-priority prefetch is cancelled as needed and the requested page load takes priority

#### Scenario: Pull down for the previous page
- **WHEN** the current text is already at its top boundary and the user continues pulling down while a previous page exists
- **THEN** a top indicator shows previous-page guidance and determinate progress that follows the pull distance

#### Scenario: Pull up for the next page
- **WHEN** the current text is already at its bottom boundary and the user continues pulling up while a next page exists
- **THEN** a bottom indicator shows next-page guidance and determinate progress that follows the pull distance

#### Scenario: Page turn reaches its threshold
- **WHEN** the pull distance reaches the configured page-turn threshold
- **THEN** the indicator changes to release-to-switch guidance while navigation remains pending

#### Scenario: User releases after reaching the threshold
- **WHEN** the indicator shows release-to-switch guidance and the user releases the pull
- **THEN** the corresponding page navigation is requested and the indicator changes to a loading state

#### Scenario: User releases below the threshold
- **WHEN** the user releases the pull before reaching the configured page-turn threshold
- **THEN** the indicator resets and no page navigation request is produced

#### Scenario: Desktop wheel reaches a page boundary
- **WHEN** a desktop wheel continues toward an available adjacent page after the text reaches its top or bottom boundary
- **THEN** the same progress and threshold guidance is shown, and wheel idle is treated as release without affecting touch-drag timing

#### Scenario: Page turn finishes
- **WHEN** the requested page navigation completes or fails
- **THEN** the loading indicator ends with that actual navigation outcome rather than a fixed display timeout

#### Scenario: Adjacent page is unavailable
- **WHEN** the user pulls down on the first page or pulls up on the final page
- **THEN** no unavailable page-turn indicator or navigation request is produced
