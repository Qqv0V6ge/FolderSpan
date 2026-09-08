# Change: Add crash error page

## Why
Users have no feedback when the app crashes. A dedicated crash screen makes failures visible and actionable without terminating the app.

## What Changes
- Intercept unhandled UI errors and keep the app running on a crash screen.
- Display full error details (name, message, and stack trace when available).
- Provide actions to restart the app, exit the app, or copy error details.

## Impact
- Affected specs: view-crash-screen
- Affected code: composeApp/src/commonMain/kotlin (app root navigation and UI)
