# Change: Add S3 Connection Guidance and Validation

## Why
The S3 add/edit flow supports multiple providers, but users still encounter frequent misconfiguration issues (credential mapping, region/endpoint mismatch, and unclear test errors).
A dedicated requirement is needed so the UI and network client provide consistent S3 connection behavior and troubleshooting guidance.

## What Changes
- Add explicit S3 connection field requirements and validation rules in the network add/edit flow.
- Add endpoint and region consistency checks for S3-compatible endpoints.
- Add actionable test-connection error hints for common S3 authentication/signing failures.

## Impact
- Affected specs: `manage-network-drives`
- Affected code: `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/network/NetworkAddScreens.kt`, `shared/src/commonMain/kotlin/com/folderspan/service/network/KtorS3NetworkClient.kt`, S3 protocol docs
