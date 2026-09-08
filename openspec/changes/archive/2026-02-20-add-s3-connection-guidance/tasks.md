## 1. Implementation
- [x] 1.1 Define required S3 form fields and validation (access key id, secret key, bucket, region, optional endpoint).
- [x] 1.2 Add endpoint/region consistency validation for common S3 endpoint patterns.
- [x] 1.3 Add test-connection error mapping for common S3 failures (`InvalidAccessKeyId`, `SignatureDoesNotMatch`, `AccessDenied`).
- [x] 1.4 Update user-facing protocol documentation for S3 connection setup.

## 2. Validation
- [x] 2.1 Verify test connection succeeds for valid S3 credentials.
- [x] 2.2 Verify invalid S3 credentials show targeted guidance.
- [x] 2.3 Run `./gradlew :shared:compileKotlinJvm`
- [x] 2.4 Run `./gradlew :composeApp:compileKotlinJvm`
