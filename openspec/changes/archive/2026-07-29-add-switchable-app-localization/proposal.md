## Why

FolderSpan currently exposes user-facing text primarily in Simplified Chinese and has no application-level language preference. Adding a shared localization contract now allows English-speaking users to use every supported surface and provides a stable path for Traditional Chinese resources later.

## What Changes

- Add English and Simplified Chinese string catalogs for shared Compose UI, Pro UI, task and notification messages, desktop tray UI, Android and iOS native surfaces, and browser-based sharing pages.
- Add an application language setting with System, Simplified Chinese, and English choices, persisted per device and applied at runtime.
- Define deterministic locale matching: Simplified Chinese locales use Simplified Chinese; Traditional Chinese and all other unmatched locales fall back to English.
- Replace persisted user-facing task, retry, and notification text with stable localized message keys while preserving compatible fallback details for legacy and external errors.
- Keep the localization architecture extensible so Traditional Chinese resources can be added without changing the language-selection contract.

## Capabilities

### New Capabilities

- `manage-app-language`: Select, persist, resolve, and apply the application language consistently across shared UI, platform-native surfaces, background messages, and browser sharing pages.

### Modified Capabilities

None.

## Impact

- Affects shared UI and core state, `proMain`, Android, Desktop, Web, and iOS application entry points and native resources.
- Adds a central generated string catalog and platform adapters for runtime locale application.
- Extends persisted task, retry, and notification payloads compatibly without renumbering existing fields.
- Adds localization-focused unit, UI, platform, and static resource coverage tests.
