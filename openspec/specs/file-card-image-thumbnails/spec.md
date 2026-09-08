## Purpose

Provide fast, memory-bounded image thumbnails in file cards across all supported file protocols and application platforms while preserving reliable icon fallbacks.

## Requirements

### Requirement: Eligible image files show thumbnails
The system SHALL attempt to display a thumbnail for a non-directory image in both list and grid file cards when thumbnail loading is enabled for that item.

#### Scenario: List image becomes available
- **WHEN** an eligible image file is visible in list view and decoding succeeds
- **THEN** the leading file icon is replaced by a cropped thumbnail with 100% rounded clipping without changing the file name or metadata

#### Scenario: Grid image becomes available
- **WHEN** an eligible image file is visible in grid view and decoding succeeds
- **THEN** the center file icon is replaced by a cropped thumbnail with 100% rounded clipping without changing the card layout

#### Scenario: Selection mode takes precedence
- **WHEN** a file card is displayed in selection mode
- **THEN** the selection control is displayed instead of its thumbnail

### Requirement: Every file protocol is supported
The system SHALL resolve eligible images from Local, Device, Share, and Network sources on every platform where the corresponding source is available.

#### Scenario: Connected remote source
- **WHEN** an eligible image belongs to a connected Device, Share, or Network source
- **THEN** the system reads it through that source and can produce the same card thumbnail behavior as a Local image

#### Scenario: Source becomes unavailable
- **WHEN** an image source cannot be resolved or disconnects during loading
- **THEN** the card remains usable and displays its normal file icon

### Requirement: Thumbnail resource use is bounded
The system MUST bound source size, concurrent work, decoded image dimensions, and cache capacity independently of the number and original dimensions of files in a directory.

#### Scenario: Oversized remote image
- **WHEN** a Device, Share, or Network image exceeds 20 MiB
- **THEN** the system does not read the image for a thumbnail and displays the file icon

#### Scenario: Web source limit
- **WHEN** any image source on JS or Wasm exceeds 8 MiB
- **THEN** the system does not read the image for a thumbnail and displays the file icon

#### Scenario: Rapid scrolling
- **WHEN** image cards rapidly enter and leave the visible region
- **THEN** obsolete work is cancelled and queued thumbnail work remains within the configured concurrency limits

### Requirement: Thumbnail failures are non-disruptive
The system SHALL preserve the existing file icon while a thumbnail is loading and whenever the file is unsupported, oversized, unreadable, or fails to decode.

#### Scenario: Unsupported image encoding
- **WHEN** a file is classified as an image but the current platform cannot decode it
- **THEN** no error UI replaces the card and the normal image-type icon remains visible

#### Scenario: Loading in progress
- **WHEN** a thumbnail request has not completed
- **THEN** the normal file icon remains visible without an item-level progress indicator

### Requirement: Cached thumbnails follow file identity
The system SHALL distinguish cached thumbnails by source identity, path, file size, modification time, and requested display size.

#### Scenario: File content changes
- **WHEN** the size or modification time of a previously cached image changes
- **THEN** the prior thumbnail is not reused for the changed file

#### Scenario: Same path on different sources
- **WHEN** two image files have the same path but different protocols or protocol identifiers
- **THEN** their thumbnails are cached and loaded independently
