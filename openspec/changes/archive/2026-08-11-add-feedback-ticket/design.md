## Context

See `proposal.md` for motivation and `specs/feedback-ticket-management/spec.md` for observable behavior. The app drawer overflow menu already opens feature destinations through the app-owned Navigation 3 boundary, while `proMain` already owns official-gateway configuration, the signed Ktor client, device identity headers, account-session persistence, token refresh, layered repositories, and Pro route composition.

The supplied OpenAPI document describes service-local `/feedbacks` routes. Production traffic reaches those routes through the existing gateway as `/api/v1/feedbacks...`; the shared client must continue applying request signing and device headers. Category lookup and submission are public/optional-auth operations, while list, detail, mutation, workflow, and attachment operations require an owned account session. JSON calls use the `{ code, data, msg }` envelope, but attachment download returns binary content.

The feature targets Android, Desktop, iOS, JS, and Wasm. Upload selection must reuse FolderSpan's common `FileSelector` instead of opening a platform-native picker; download saving still needs target-specific adapters, and private attachment bytes must not be persisted in the API response cache.

## Goals / Non-Goals

**Goals:**

- Keep HTTP, mapping, business rules, presentation state, and UI in the existing `proMain` layers, exposing only a small route factory to the shared app drawer menu.
- Reuse the existing signed gateway client and session-refresh behavior for every authenticated operation.
- Keep public submission genuinely usable without an account while preserving ticket ownership whenever a valid session exists.
- Bridge the app-owned attachment selector into Pro UI through a small common contract, isolate platform download saving, and keep the Compose experience responsive and testable.
- Model the complete `/feedbacks` contract without leaking raw JSON or transport exceptions into the UI.

**Non-Goals:**

- Changing the report service, gateway authentication policy, ticket workflow, SLA calculation, notification delivery, or OpenAPI document.
- Adding the separate `/reports` abuse-reporting feature or `/support/summary` endpoint.
- Providing offline ticket mutation, background attachment transfer, local ticket persistence, or anonymous-ticket lookup.
- Generating a client from OpenAPI or introducing another networking framework.

## Decisions

### 1. Implement the feature as a layered `proMain` vertical slice

Add feedback DTOs and `FeedbackApiService` under `data/remote`, a mapper and default repository under `data/repository`, transport-independent models plus `FeedbackRepository` under `domain`, and route/ViewModel/UI state under `presentation`. `AppServices` will construct one repository from the existing lifecycle-scoped `HttpClient`. The shared app drawer menu will only add a localized item that calls `ProRoutes.feedbackScreen()`.

This keeps account and gateway knowledge out of `app/shared` and follows the module's existing user, plugin, and settings patterns. Putting HTTP calls directly in a shared composable was rejected because it would couple UI state to Ktor and duplicate signing, refresh, and error mapping. Moving the feature into `core` was rejected because the official account gateway is already a `proMain` responsibility.

### 2. Extend gateway routing without creating a second client

Add a configurable `feedbackPrefix` with default `/api/v1/feedbacks` to `GatewayConfig` and a `RouteBuilder.feedback(path)` helper. `FeedbackApiService` will derive every URL from that helper and inherit `BaseApiService` JSON envelope handling, request signing, device headers, language headers, and user-friendly failure mapping.

The API mapping is fixed as follows:

| Client operation | Method and gateway route | Authentication |
| --- | --- | --- |
| Categories | `GET /api/v1/feedbacks/categories` | None |
| Submit | `POST /api/v1/feedbacks` | Optional Bearer |
| List | `GET /api/v1/feedbacks` | Bearer |
| Update | `PUT /api/v1/feedbacks` | Bearer |
| Delete | `DELETE /api/v1/feedbacks` | Bearer |
| Detail | `GET /api/v1/feedbacks/{uuid}` | Bearer |
| Upload | `POST /api/v1/feedbacks/{uuid}/attachments` | Bearer, multipart `file` |
| Download | `GET /api/v1/feedbacks/{uuid}/attachments/{attachmentUuid}` | Bearer, binary response |
| Mark read | `POST /api/v1/feedbacks/{uuid}/read` | Bearer |
| Supplement | `POST /api/v1/feedbacks/{uuid}/supplements` | Bearer |
| Withdraw | `POST /api/v1/feedbacks/{uuid}/withdraw` | Bearer |

JSON requests will use the current client and `BaseApiService`; multipart upload and binary download will use focused helpers that retain the same client/plugins but parse metadata and `Content-Disposition` explicitly. Creating a generated or standalone client was rejected because it would bypass project request plugins and add a second error model.

### 3. Map defensive DTOs into stable domain models

Define serializable request/response DTOs that mirror the supplied schema, then map them into domain types for feedback type, category, list page, ticket, event, attachment, and workflow-operation result. Status, priority, category, actor type, and event type will retain their raw server value alongside localized known-value presentation so new values remain renderable.

The OpenAPI examples and schemas disagree on some scalar representations, notably `dueAt`. The DTO layer will use a narrow tolerant serializer for that field that accepts the documented string and observed numeric value, normalizing both into a nullable domain timestamp. Missing optional fields remain nullable; malformed required identity fields fail mapping with a controlled data error rather than producing a partially actionable ticket.

Private lists, details, timelines, contacts, and attachments will be held only in ViewModel memory. Existing disk response caching will not be enabled for feedback calls. Categories may be retained in memory for the feature-service lifetime. This favors privacy and freshness over offline display.

### 4. Make authentication behavior explicit and ownership-preserving

Public category lookup never sends account authorization. Submission checks the session at action time: with no session it sends anonymously; with a session it runs through `AuthorizedUserRequestExecutor`, refreshes when needed, and sends Bearer authorization. If refresh or authorization fails, the action returns to a sign-in state and never retries anonymously, preventing an apparently owned ticket from becoming untrackable.

All other calls use `AuthorizedUserRequestExecutor`. A final unauthorized result clears the invalid session through the existing mechanism, retains only non-sensitive form/filter context, and moves protected views to a sign-in action. Tokens, contact details, form text, and attachment names are excluded from logs.

An alternative in which every submission is anonymous was rejected because signed-in users would not reliably see their new ticket. Automatically falling back from failed authentication to anonymous submission was rejected because it silently changes ownership semantics.

### 5. Add public feedback and authenticated ticket-workspace routes to the Pro navigation host

Introduce serializable routes for the public feedback home, an authenticated ticket workspace with an optional initially selected ticket UUID, and a feedback-specific sign-in handoff. Do not create a standalone ticket-detail route or screen. Update navigation policy to distinguish public routes from authenticated and unauthenticated-only routes: session reconciliation must never redirect the public form, protected routes must redirect when the session disappears, and the feedback sign-in handoff must return to the ticket workspace after success while preserving a notification-requested ticket UUID.

`ProRoutes.feedbackScreen()` opens the public home from the app drawer overflow menu. The ticket workspace owns the selected-ticket UI state. On compact widths, selecting a ticket replaces the list pane with the detail pane inside the same route, and Back returns to the list before leaving the workspace. On expanded widths, the list and selected detail share the available width as a single list-detail workspace. ViewModels expose immutable state with independent flags for initial load, refresh, pagination, submission, and each ticket operation so one action does not blank unrelated content.

Reusing the existing generic login redirect unchanged was rejected because it currently routes successful login to the profile/marketplace flow rather than back to feedback tickets.

### 6. Reuse the app-owned selector and isolate download saving

Expose a small composition-local attachment-selection contract from `proMain`, then provide it from `app/shared` with FolderSpan's existing `FileSelector`. The selector remains single-choice, navigates the app-visible local filesystem, and returns portable filename/byte metadata to Pro code. This avoids a reverse dependency from `proMain` to `app/shared` while keeping upload selection visually and behaviorally consistent with the rest of the application.

Validate the selected file's declared size before reading, then repeat filename extension/MIME type and 10 MiB checks in Pro code and the repository before multipart construction. Download remains behind target adapters because choosing or opening a destination differs across Android, Desktop, iOS, JS, and Wasm. It uses the response `Content-Disposition` filename when safely parseable, otherwise the server attachment metadata name, and sanitizes path separators.

Platform-native upload pickers were rejected because FolderSpan already provides a cross-platform file-selection experience. Passing platform handles into domain models was rejected because they are not portable or serializable. Persisting downloaded private files automatically was rejected because destination choice and sandbox permissions differ by target.

### 7. Refresh server state after mutations instead of predicting workflow transitions

Successful update, supplement, withdrawal, upload, mark-read, and delete operations update minimal local flags and then refresh the relevant detail/list. The UI uses `canSupplement` and `canWithdraw` to gate actions, but the server remains authoritative for edit/delete eligibility, status transitions, version, unread count, and timeline events. Destructive delete and withdrawal require confirmation.

Optimistically fabricating timeline entries or status changes was rejected because server workflow rules can change and responses only return limited operation metadata.

### 8. Verify the feature at transport, state, navigation, and UI boundaries

Use Ktor `MockEngine` contract tests for exact method/path/query/body/auth mappings, envelope failures, tolerant scalar decoding, multipart metadata, and binary filename parsing. Repository and ViewModel tests cover anonymous versus authenticated submission, refresh failure without anonymous fallback, pagination de-duplication, stale-data preservation, action gates, and per-operation concurrency guards. Navigation-policy tests cover public, protected, logout, and feedback-login return behavior. Compose tests cover validation, loading/error/empty states, confirmation dialogs, semantics, and compact/expanded layouts; platform adapter tests cover filename, type, size, cancellation, and resource cleanup where the target permits automation.

## Risks / Trade-offs

- [OpenAPI scalar examples differ from schemas] → Keep tolerance limited to explicitly inconsistent fields, add fixture tests from the supplied document, and fail closed for missing ticket identity.
- [Optional authentication can accidentally create an anonymous ticket] → Resolve/refresh the session before a signed-in submission and never downgrade that request to anonymous.
- [Attachment save APIs vary significantly across targets] → Reuse the common app selector for upload, keep the platform save contract small, validate before reading, and test shared policy independently.
- [Private ticket data could leak through cache or logs] → Disable disk API caching for feedback responses and redact tokens, contacts, content, UUID-rich URLs, and attachment names from logs.
- [Mutation responses do not contain the full updated ticket] → Refresh server state after success and preserve the last good UI state if that refresh fails.
- [Adding public routes complicates current session reconciliation] → Add explicit route classification and focused policy tests rather than scattering session checks through screens.
- [A 10 MiB common-memory upload can pressure browser/mobile targets] → Reject oversize files before read, avoid extra copies, release buffers promptly, and leave streaming as a compatible follow-up if profiling shows pressure.

## Migration Plan

1. Add gateway routing, DTO/domain/repository code, and tests without exposing a UI entry.
2. Add navigation, ViewModels, shared UI, localized strings, and platform attachment adapters behind the new Settings destination.
3. Verify against a report-service environment using anonymous and authenticated accounts on every supported target, including expiry and attachment cases.
4. Release additively; no local or server data migration is required.
5. If rollback is needed, remove the app drawer menu entry and feedback route wiring first, then remove the unused vertical slice. Existing server tickets and account sessions remain untouched.
