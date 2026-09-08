## Why

The app currently uses Voyager `Screen` and `Navigator` throughout shared Compose UI, while the requested dependency set standardizes on AndroidX Navigation 3 and NavigationEvent. Migrating now aligns navigation with Compose Multiplatform state-owned back stacks and removes the Voyager dependency before more screens become coupled to it.

## What Changes

- Replace Voyager-based navigation with AndroidX Navigation 3 `NavKey` routes, an app-owned back stack, and `NavDisplay` rendering.
- Add NavigationEvent handling so system/back gestures pop the app back stack before platform-level exit behavior runs.
- Update shared navigation state so external entry points, drawer items, notifications, quick actions, and tray actions route through a project-owned navigator instead of Voyager `Navigator`.
- Add the missing Navigation 3 and NavigationEvent version catalog entries and dependencies while keeping `androidx-espresso = 3.7.0` and `androidx-lifecycle = 2.10.0`.
- Remove Voyager from app navigation after all screens and helpers are migrated.
- **BREAKING**: Internal UI navigation APIs will no longer accept Voyager `Screen` values; they will accept project route keys or route requests.

## Capabilities

### New Capabilities
- `app-navigation`: Defines application navigation behavior, route keys, back-stack handling, and external screen-opening behavior using AndroidX Navigation 3.

### Modified Capabilities
- None.

## Impact

- Affected code:
  - Version catalog and Gradle dependencies in `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, and `shared/build.gradle.kts`.
  - Navigation entry points under `composeApp/src/commonMain/kotlin/com/folderspan/ui/navigator/`.
  - Shared navigation state in `shared/src/commonMain/kotlin/com/folderspan/ui/state/main/MainState.kt`.
  - Screens and UI components that import Voyager `Screen`, `LocalNavigator`, `Navigator`, or `pushSafe`.
  - Platform entry points that open screens from Android back handling, iOS quick actions, desktop tray actions, notifications, and drawer actions.
- Affected tests:
  - Common navigation tests, resume-refresh root-screen tests, and any tests asserting Voyager `Screen` behavior.
- No user-facing layout or visual redesign is intended.
