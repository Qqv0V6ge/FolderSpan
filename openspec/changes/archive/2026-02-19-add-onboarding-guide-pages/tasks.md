## 1. Implementation
- [x] 1.1 Add a new guide screen route and implement a two-page guide layout (next/back and page indicator).
- [x] 1.2 Implement page 1: app introduction, transfer-management overview, and `Local`/`Device`/`Share`/`Network` explanations based on existing disk/domain models and device connection flow.
- [x] 1.3 Implement page 1 `FileShareScreen` route section, including route source explanation and a direct open action.
- [x] 1.4 Implement page 2: usage introduction for `HomeScreen`, `AppDrawer`, and `FileScreen` with concise operation steps.
- [x] 1.5 Add a theme-introduction section that uses `MaterialTheme.colorScheme` roles defined through `Theme.kt`.
- [x] 1.6 Ensure onboarding guide is first-launch only and no persistent drawer entry is shown.
- [x] 1.7 Add one-time first-launch behavior by using onboarding as initial navigator screen with persisted flag storage.
- [x] 1.8 Adapt guide content/actions for all window classes (`Compact`/`Medium`/`Expanded`/`Large`/`ExtraLarge`) with distinct layouts.

## 2. Validation
- [x] 2.1 Manual: onboarding auto-opens on first launch and does not appear as a persistent drawer item afterward.
- [x] 2.2 Manual: page 1 clearly explains `Local`/`Device`/`Share`/`Network` and `FileShareScreen` route behavior.
- [x] 2.3 Manual: page 1 direct route action opens `FileShareScreen`.
- [x] 2.4 Manual: page 2 explains `HomeScreen` + `AppDrawer` + `FileScreen` usage and theme colors adapt to light/dark themes.
- [x] 2.5 Run `openspec validate add-onboarding-guide-pages --strict`.
- [x] 2.6 Manual: onboarding auto-opens only on first launch and does not auto-open on subsequent launches.
- [x] 2.7 Manual: all window classes keep readable width, actionable controls, and distinct composition patterns.
