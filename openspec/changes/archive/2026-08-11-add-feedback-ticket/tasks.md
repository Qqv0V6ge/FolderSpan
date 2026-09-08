## 1. API Contract and Domain Models

- [x] 1.1 Add the configurable `/api/v1/feedbacks` gateway prefix and route builder, plus focused route-construction tests for every feedback endpoint.
- [x] 1.2 Define serializable feedback/category/list/detail/event/attachment/workflow DTOs and transport-independent domain models matching the supplied OpenAPI contract.
- [x] 1.3 Implement tolerant decoding and mapping for inconsistent optional scalar fields such as `dueAt`, preserving unknown enum-like values and rejecting missing ticket identity.
- [x] 1.4 Implement `FeedbackApiService` JSON calls for categories, optional-auth submission, list, update, delete, detail, mark-read, supplement, and withdrawal.
- [x] 1.5 Implement signed-client multipart upload and binary download helpers with safe `Content-Disposition` filename parsing and no private response caching.
- [x] 1.6 Add Ktor `MockEngine` contract tests for methods, paths, query parameters, bodies, auth headers, response envelopes, malformed payloads, multipart data, and binary downloads.

## 2. Repository and Session Semantics

- [x] 2.1 Add the `FeedbackRepository` interface and default implementation covering public submission and the complete authenticated ticket lifecycle.
- [x] 2.2 Integrate `AuthorizedUserRequestExecutor` so protected operations refresh/retry consistently and signed-in submission never falls back to anonymous after authorization failure.
- [x] 2.3 Add repository tests for anonymous submission, authenticated submission, expiry/refresh outcomes, business failures, DTO mapping, workflow refresh results, and sensitive-data-safe errors.

## 3. Dependency Injection and Navigation

- [x] 3.1 Register the feedback API service, repository, and required feature services in `AppServices` using its lifecycle-scoped signed `HttpClient`.
- [x] 3.2 Add serializable public home, authenticated ticket-workspace, and feedback sign-in handoff routes without a standalone detail route, expose `ProRoutes.feedbackScreen()`, and render them in the Pro navigation host.
- [x] 3.3 Extend session reconciliation with explicit public/protected route classification and add tests for signed-out entry, login return, logout, expiry, and back-stack behavior.
- [x] 3.4 Add a localized Feedback & Suggestions item to the app drawer overflow menu that opens the new route through the existing app navigator.

## 4. Presentation State

- [x] 4.1 Implement feedback-form state and ViewModel behavior for category loading/retry, validation, app-version/platform metadata, optional contact, duplicate-submit guards, and form preservation on failure.
- [x] 4.2 Implement ticket-list state and ViewModel behavior for sign-in state, type/platform/time filters, page reset, next-page loading, UUID de-duplication, totals, empty state, and stale-data retention.
- [x] 4.3 Implement ticket-detail state and ViewModel behavior for load/refresh, read marking, update, confirmed delete, supplement, confirmed withdrawal, per-operation progress, action gates, and server-authoritative refresh.
- [x] 4.4 Add coroutine unit tests for form, list, and detail state transitions, including concurrency guards, unauthorized transitions, failed refresh retention, and unknown server values.

## 5. Compose User Experience

- [x] 5.1 Build the public feedback form with feedback/suggestion selection, server categories, content/contact fields, validation, loading, retry, and success states.
- [x] 5.2 Build My Feedback Tickets with signed-out CTA, filters, unread/status/priority presentation, pagination, empty/error states, and compact versus expanded single-route list-detail layouts.
- [x] 5.3 Build an in-workspace ticket detail pane with metadata, status note, public timeline, attachment list, edit/delete/supplement/withdraw controls, confirmations, and non-blocking refresh errors.
- [x] 5.4 Add localized strings and safe fallback labels for known and unknown category/status/priority/event values without exposing technical request or token terminology.
- [x] 5.5 Add Compose tests for app drawer menu navigation, form validation/submission, list and detail states, confirmations, keyboard/focus semantics, and representative compact/expanded layouts.

## 6. Cross-Platform Attachments

- [x] 6.1 Define the common app-selector bridge and platform save boundary, filename sanitization, supported format checks, and the repeated 10 MiB pre-upload guard.
- [x] 6.2 Implement and verify Android and Desktop download-save adapters with cancellation and resource cleanup.
- [x] 6.3 Implement and verify the iOS download-save adapter with security-scoped URL lifecycle handling.
- [x] 6.4 Implement and verify JS and Wasm browser save adapters with capability detection and user-gesture-safe behavior.
- [x] 6.5 Connect the app-owned file selector, upload, download, progress, cancellation, retry, and post-success detail refresh to the ticket UI.
- [x] 6.6 Add app-selector reading, shared policy, and target-appropriate save-adapter tests for type/size rejection, filename fallback, successful save, cancellation, failure, and cleanup.

## 7. Verification and Release Readiness

- [x] 7.1 Run focused `proMain` and shared UI unit/Compose tests and compile the affected Android, JVM, iOS, JS, and Wasm source sets.
- [x] 7.2 Smoke-test anonymous submission, authenticated ticket ownership, pagination, expiry handling, every workflow action, and attachment upload/download against a report-service environment.
- [x] 7.3 Verify dark mode, compact/expanded layouts, keyboard navigation, screen-reader labels, user-facing error text, and absence of token/contact/content/attachment data in logs or disk API cache.
