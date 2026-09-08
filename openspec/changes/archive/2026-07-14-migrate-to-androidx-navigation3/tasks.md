## 1. Tests First

- [x] 1.1 Add common navigation tests for initial Home route, duplicate-route suppression, pending route delivery, pop, pop-to-root, and current-route reporting.
- [x] 1.2 Update resume-refresh tests to assert Home-route detection without Voyager `Screen`.
- [x] 1.3 Add compact-window navigation tests proving pushed routes collapse the drawer.
- [x] 1.4 Add Android back handling tests or focused helpers proving non-root back pops the stack and root back falls through to platform behavior.

## 2. Dependencies

- [x] 2.1 Add `androidx-navigation3 = "1.1.1"` and `androidx-navigationevent = "1.1.0"` to `gradle/libs.versions.toml`.
- [x] 2.2 Add `androidx-navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "androidx-navigation3" }`.
- [x] 2.3 Add `androidx-navigationevent-compose = { module = "org.jetbrains.androidx.navigationevent:navigationevent-compose", version.ref = "androidx-navigationevent" }`.
- [x] 2.4 Add Navigation 3 and NavigationEvent dependencies to the source sets that host common Compose navigation.

## 3. Navigation Model

- [x] 3.1 Add a serializable `AppRoute` route-key hierarchy for current app destinations, using object routes for singleton screens and data routes for parameterized screens.
- [x] 3.2 Add `AppNavigator` operations for `push`, `pop`, `popToRoot`, `replaceRoot`, and current route lookup.
- [x] 3.3 Update `MainState` to store the project navigator boundary instead of Voyager `Navigator`.
- [x] 3.4 Preserve pending external navigation requests and compact-window drawer collapse in the new `MainState` navigation methods.
- [x] 3.5 Add a narrow route payload store only for destinations that cannot be represented by stable serializable ids.

## 4. Navigation Host

- [x] 4.1 Replace `HomeNavigator` Voyager host with a Navigation 3 host using an app-owned `NavBackStack` and `NavDisplay`.
- [x] 4.2 Register the Navigation 3-backed `AppNavigator` with `MainState` from the host.
- [x] 4.3 Map every `AppRoute` to the matching existing screen content in one entry provider.
- [x] 4.4 Add NavigationEvent back handling so non-root back actions pop the app stack.
- [x] 4.5 Update app resume logic to inspect `MainState` current route instead of Voyager `lastItem`.

## 5. Screen and Call-Site Migration

- [x] 5.1 Convert screen declarations away from Voyager `Screen` while preserving their composable content.
- [x] 5.2 Replace `LocalNavigator.currentOrThrow` usage in screens with route callbacks or the project navigation boundary.
- [x] 5.3 Replace drawer, scaffold, onboarding, notification, tray, and quick-action navigation calls with `AppRoute` requests.
- [x] 5.4 Replace `pushSafe`, `popUntilRoot`, `lastItem`, and direct `Navigator` usage with `AppNavigator` equivalents.
- [x] 5.5 Resolve parameterized screen navigation through ids or the documented temporary payload store.

## 6. Voyager Removal

- [x] 6.1 Remove `voyager-navigator` dependencies from `composeApp` and `shared` Gradle source sets.
- [x] 6.2 Remove obsolete Voyager helper extensions and imports.
- [x] 6.3 Run a source scan proving app and shared sources no longer import Voyager navigation symbols.

## 7. Verification

- [x] 7.1 Run `./gradlew :composeApp:jvmTest`.
- [x] 7.2 Run `./gradlew :shared:jvmTest`.
- [x] 7.3 Run `./gradlew :composeApp:compileDebugKotlinAndroid`.
- [x] 7.4 Run `openspec validate migrate-to-androidx-navigation3 --strict`.
- [x] 7.5 Smoke-check Android, Desktop, iOS quick action, JS, and Wasm navigation startup or compile paths where locally available.
