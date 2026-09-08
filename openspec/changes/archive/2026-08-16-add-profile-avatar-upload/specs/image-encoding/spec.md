## Purpose

Define decoding image bytes into an in-memory image and encoding an in-memory image back into image bytes, so features that transform images can produce uploadable results on every supported platform.

## ADDED Requirements

### Requirement: Decode image bytes
The system SHALL decode JPEG, PNG, and WebP bytes into an in-memory image, and SHALL report a failure rather than raising an unhandled error when the bytes cannot be decoded.

#### Scenario: Supported bytes are decoded
- **WHEN** JPEG, PNG, or WebP bytes are decoded
- **THEN** an in-memory image is produced
- **AND** its dimensions match the encoded image's dimensions

#### Scenario: Bytes are not a decodable image
- **WHEN** bytes that are not a decodable image are decoded
- **THEN** a failure is reported
- **AND** no unhandled error escapes

#### Scenario: Empty input
- **WHEN** an empty byte sequence is decoded
- **THEN** a failure is reported

### Requirement: Encode an image
The system SHALL encode an in-memory image into JPEG, PNG, or WebP bytes at a caller-specified format and quality, on Android, JVM, iOS, JS, and WasmJs.

#### Scenario: Encoding produces decodable bytes
- **WHEN** an image is encoded in a supported format
- **THEN** bytes are produced
- **AND** decoding those bytes yields an image with the same dimensions

#### Scenario: Quality affects lossy output size
- **WHEN** the same image is encoded as JPEG at a lower quality and at a higher quality
- **THEN** the lower-quality result is not larger than the higher-quality result

#### Scenario: Format is unavailable on a platform
- **WHEN** a requested format cannot be encoded on the current platform
- **THEN** a failure identifying the unavailable format is reported
- **AND** no other format is substituted

#### Scenario: Behavior is consistent across platforms
- **WHEN** the same image is encoded on any supported platform
- **THEN** the result is decodable, carries the requested format, and preserves the image's dimensions

### Requirement: Content type reporting
The system SHALL report the content type that corresponds to the format an image was encoded in.

#### Scenario: Content type matches the format
- **WHEN** an image is encoded in a supported format
- **THEN** the reported content type is the one that corresponds to that format
