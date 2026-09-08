## Context
The current app provides powerful operations, but understanding requires users to discover multiple modules on their own (`Local`/`Share`/`Network`, drawer actions, file operation menus, and share routes).
This change introduces a concise two-page onboarding guide so users can understand capability boundaries and start paths without leaving the app.

## Goals / Non-Goals
- Goals:
  - Auto-open the guide on first app launch only.
  - Keep the guide to two pages only, matching the requested scope.
  - Explain `Local`, `Device`, `Share`, and `Network` in terms of current domain classes, connection flow, and permission characteristics.
  - Explain `FileShareScreen` route behavior and provide a practical route action.
  - Explain the primary daily flow of `HomeScreen`, `AppDrawer`, and `FileScreen`.
  - Introduce app theme colors via Material 3 roles from `Theme.kt`.
  - Keep the guide readable across all window classes with layout differences by screen size.
- Non-Goals:
  - Replacing existing help/settings pages.
  - Introducing a new design system outside current Material 3 theming.

## Decisions
- Add a standalone guide screen as a normal route/screen.
- Trigger one-time auto-open by selecting onboarding as the initial `HomeNavigator` screen, using a persisted settings flag and without exposing a persistent drawer entry.
- Use a two-page structure:
  - Page 1: capability overview (app intro + transfer management + disk model + `FileShareScreen` route section).
  - Page 2: usage overview (`HomeScreen`/`AppDrawer`/`FileScreen` workflow + theme color explanation).
- Keep the visual style bold but Material-consistent:
  - Distinct section cards, clear hierarchy, and motion transitions.
  - Color accents sourced from `MaterialTheme.colorScheme` roles (`primary`, `secondary`, `tertiary`, `surface*`).
- Use window-size-class-driven layouts (`Compact`/`Medium`/`Expanded`/`Large`/`ExtraLarge`) and adaptive action areas so each class has a distinct composition pattern.
- Keep content coupled to current implementation paths to reduce conceptual drift.

## Risks / Trade-offs
- Risk: Guide text can become stale when route entry points or feature behavior change.
  - Mitigation: reference concrete modules and include this screen in future feature update checklists.
- Trade-off: guide is no longer directly discoverable after first launch.
  - Mitigation: keep guide behavior stable and only use first-launch onboarding to reduce recurring UI noise.

## Migration Plan
- No data migration is required.
- The change is additive and safe to roll out by default.

## Open Questions
- None.
