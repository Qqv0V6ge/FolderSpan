## ADDED Requirements
### Requirement: S3 connection required fields
The system SHALL provide an S3 configuration form that supports:
- AccessKeyId
- SecretAccessKey
- Bucket
- Region
- Endpoint (optional)
- SessionToken (optional)
- forcePathStyle (optional)

The system SHALL block save/test when required fields (AccessKeyId, SecretAccessKey, Bucket, Region) are empty.

#### Scenario: Save S3 entry with required fields
- **WHEN** the user fills AccessKeyId, SecretAccessKey, bucket, and region
- **THEN** the S3 form allows save and test actions

### Requirement: S3 endpoint and region consistency
When endpoint is provided, the system SHALL validate endpoint format and ensure region consistency for region-encoded endpoint hosts.

#### Scenario: Reject malformed S3 endpoint
- **WHEN** the user enters an invalid endpoint URL in the S3 form
- **THEN** the form shows endpoint format validation error
- **AND** save/test actions are blocked

#### Scenario: Reject endpoint region mismatch
- **WHEN** the endpoint host encodes a region and the configured region does not match
- **THEN** the form reports a mismatch validation error
- **AND** save/test actions are blocked until corrected

### Requirement: Actionable S3 test connection feedback
The system SHALL map common S3 connection failures to actionable feedback.

#### Scenario: Access key id invalid
- **WHEN** test connection fails with `InvalidAccessKeyId`
- **THEN** feedback explains AccessKeyId format or value is incorrect

#### Scenario: Signature mismatch
- **WHEN** test connection fails with `SignatureDoesNotMatch`
- **THEN** feedback suggests checking region, endpoint, and signing-related fields

#### Scenario: Access denied
- **WHEN** test connection fails with `AccessDenied`
- **THEN** feedback suggests checking key permissions and bucket access scope
