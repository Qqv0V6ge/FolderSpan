## Why

FolderSpan currently has no in-app channel for users to submit product feedback or suggestions, nor a place for signed-in users to follow the resulting support ticket. Adding this flow makes feedback actionable and lets users see progress without leaving the app.

## What Changes

- Add a discoverable “Feedback & Suggestions” entry in the app drawer overflow menu that opens a shared Compose Multiplatform feedback experience.
- Allow all users to load server-defined feedback categories and submit feedback or suggestions with category, content, contact details, app version, and platform metadata.
- Add a signed-in “My Feedback Tickets” workspace with filtering, pagination, status, unread counts, an in-workspace ticket detail pane, public processing timeline, and private attachment metadata.
- Support the authenticated ticket actions defined by the report service contract: update, delete, mark read, supplement, withdraw, upload an attachment, and download an attachment.
- Reuse the existing account session for Bearer-authenticated operations, provide an explicit sign-in state for protected views/actions, and handle loading, empty, validation, retry, success, and API error states.
- Add localized strings and cross-platform tests for request mapping, state transitions, navigation, forms, ticket rendering, and supported attachment constraints.
- Use `/home/webb/GolandProjects/file-manager/docs/swagger/report/swagger.yaml` as the source of truth for the `/feedbacks` API contract; this change does not alter the server API.

## Capabilities

### New Capabilities

- `feedback-ticket-management`: Submit feedback and suggestions and, when authenticated, browse and manage the associated feedback ticket lifecycle through the report service API.

### Modified Capabilities

- None.

## Impact

- Shared UI and navigation under `app/shared/src/commonMain/kotlin`, including the app drawer menu entry and new feedback/ticket screens.
- Shared domain/data code under `core/src/commonMain/kotlin` or the existing Pro account/network boundary, including serializable API models, authenticated request handling, repositories, and presentation state.
- Existing persisted account session integration for Bearer tokens; no new credential store is introduced.
- Shared resources/localization plus common/JVM UI and data-layer tests; attachment upload reuses the app-owned file selector while download saving retains target-specific adapters.
- Runtime dependency on the report service `/feedbacks` endpoints and its existing response envelope, category values, ticket state, and 10 MiB attachment policy.
