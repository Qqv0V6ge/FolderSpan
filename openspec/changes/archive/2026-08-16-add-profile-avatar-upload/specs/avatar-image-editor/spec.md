## Purpose

Define how a picture is selected and framed so that a caller which knows nothing about files or images receives an upload-ready square picture that matches exactly what the user saw in the preview.

## ADDED Requirements

### Requirement: Picture request contract
The system SHALL let a caller request a picture and receive exactly one outcome: a delivered picture carrying encoded bytes and a content type, a cancellation, or a failure. The delivered picture SHALL NOT carry the source file's path or name.

#### Scenario: Caller requests a picture
- **WHEN** a caller issues a picture request
- **THEN** picture selection is presented
- **AND** the caller receives no result until selection and editing have both concluded

#### Scenario: Caller receives a picture
- **WHEN** the user completes selection and editing
- **THEN** the caller receives encoded bytes and their content type
- **AND** the caller receives no source path or source file name

#### Scenario: Request is superseded
- **WHEN** a new picture request is issued while one is pending
- **THEN** the pending request is reported as cancelled
- **AND** the new request proceeds

#### Scenario: Host is disposed
- **WHEN** the host of a pending picture request is disposed
- **THEN** the pending request is reported as cancelled
- **AND** any in-flight decoding, editing, or encoding work is stopped

### Requirement: Picture selection
Selection SHALL use the shared file selector inside the full-size selector dialog, SHALL restrict visible entries to supported image types, and SHALL allow exactly one file to be chosen.

#### Scenario: Only images are offered
- **WHEN** the selection stage is presented
- **THEN** only entries with supported image extensions are shown
- **AND** directories remain visible and can be opened

#### Scenario: Only one file can be chosen
- **WHEN** the user selects a file and then selects another
- **THEN** only the most recently selected file is chosen

#### Scenario: Source size guards decode cost
- **WHEN** a visible image file exceeds the maximum source size
- **THEN** it cannot be selected
- **AND** the rejection identifies the size rule

#### Scenario: Source size limit is independent of the upload limit
- **WHEN** an image file is larger than the caller's upload size limit but within the maximum source size
- **THEN** it can be selected
- **AND** it proceeds to editing

#### Scenario: Selection is abandoned
- **WHEN** the user dismisses the selection stage without confirming
- **THEN** the request is reported as cancelled
- **AND** no editing stage is presented

### Requirement: Image editing controls
The editor SHALL offer rotation in quarter turns, independent horizontal and vertical flipping, zooming, and panning, applied to a fixed centered crop window.

#### Scenario: Rotation
- **WHEN** the user rotates the image
- **THEN** the image turns by a quarter turn in the chosen direction
- **AND** four rotations in the same direction return it to its original orientation

#### Scenario: Horizontal and vertical flipping
- **WHEN** the user flips the image horizontally or vertically
- **THEN** the image mirrors along that axis
- **AND** flipping the same axis twice returns it to its previous appearance
- **AND** each axis can be flipped independently of the other

#### Scenario: Zooming and panning
- **WHEN** the user zooms or pans the image
- **THEN** the visible region within the crop window changes accordingly

#### Scenario: Crop window is always covered
- **WHEN** the user zooms out or pans toward an edge
- **THEN** the image cannot be moved or scaled so that the crop window shows an empty region

#### Scenario: Editing does not degrade the source
- **WHEN** the user applies many rotation, flip, and zoom operations in sequence
- **THEN** the result is equivalent to applying the resulting orientation and viewport once to the original image

#### Scenario: Editing is abandoned
- **WHEN** the user dismisses the editing stage without confirming
- **THEN** the request is reported as cancelled

### Requirement: Preview matches the delivered picture
The editor SHALL display the exact region that will be delivered.

#### Scenario: Preview and output agree
- **WHEN** the user confirms the editing stage
- **THEN** the delivered picture shows the same region, orientation, and mirroring that the crop window displayed

### Requirement: Upload-ready output
The editor SHALL deliver a square picture at a fixed edge length, and SHALL verify the encoded result against the caller's size limit before delivering it.

#### Scenario: Output shape and format
- **WHEN** a picture is delivered
- **THEN** it is square at the fixed edge length
- **AND** it is encoded in a format the avatar endpoint accepts
- **AND** its content type matches its encoding

#### Scenario: Output is within the size limit
- **WHEN** a picture is delivered
- **THEN** its encoded size is within the caller's size limit

#### Scenario: Output exceeds the size limit
- **WHEN** the encoded result exceeds the caller's size limit
- **THEN** no picture is delivered
- **AND** the request is reported as a failure

### Requirement: Unreadable source handling
The system SHALL report a failure when a selected file cannot be read or decoded, and SHALL NOT treat it as a cancellation.

#### Scenario: File contents are not a decodable image
- **WHEN** a selected file has a supported image extension but its contents cannot be decoded
- **THEN** the request is reported as a failure
- **AND** the reason is expressed in wording a non-technical user can understand

#### Scenario: File cannot be read
- **WHEN** a selected file's bytes cannot be read
- **THEN** the request is reported as a failure

### Requirement: Work runs off the main thread
Decoding, transforming, and encoding SHALL run off the main dispatcher.

#### Scenario: Large image is processed
- **WHEN** a large image is decoded, edited, and encoded
- **THEN** the interface remains responsive
- **AND** progress is shown while the work is in flight
