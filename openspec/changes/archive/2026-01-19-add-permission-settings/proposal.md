# Change: Add permission settings page

## Why
Users need a single settings page to view and request platform permissions required by the app.

## What Changes
- Add a permissions entry in settings that appears only when the platform exposes required permissions.
- Add a permissions screen to show permission descriptions, statuses, and request actions.
- Audit platform permission usage and codify per-platform permission lists.
- Introduce a cross-platform permission provider via expect/actual for status and requests.

## Impact
- Affected specs: manage-permission-settings (new)
- Affected code: composeApp settings screens, shared/commonMain permission models, platform-specific permission implementations
