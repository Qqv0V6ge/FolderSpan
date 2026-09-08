## Context

The app is a Compose Multiplatform project with most screens in `composeApp/src/commonMain` and shared UI state in `shared/src/commonMain`. Navigation currently depends on Voyager:

- top-level rendering uses `Navigator` and `CurrentScreen` in `HomeNavigator`
- screens implement Voyager `Screen` and call `LocalNavigator.currentOrThrow`
- `MainState` stores a Voyager `Navigator?`, exposes `pushScreen` and `requestOpenScreen`, and several platform entry points call those methods
- app resume logic and drawer selection inspect `navigator.lastItem`

Navigation 3 uses a state-owned back stack instead of a library-owned navigator. The migration therefore needs an app navigation boundary, not a direct symbol-by-symbol replacement.

## Goals / Non-Goals

**Goals:**

- Replace Voyager with Navigation 3 in the shared Compose UI.
- Keep existing screen destinations, drawer actions, notification deep links, tray actions, quick actions, and resume-refresh behavior working.
- Add NavigationEvent-backed return handling so back gestures pop the Navigation 3 stack consistently.
- Keep route state serializable where possible and avoid passing large mutable domain objects through navigation.
- Update Gradle dependencies to the requested versions and remove Voyager once no code imports it.

**Non-Goals:**

- Redesign screen layouts, drawer layout, or onboarding flow.
- Add public URL deep links or browser history integration.
- Rewrite business state, file transfer flows, or network protocols.
- Introduce graph-scoped ViewModels; current state is already Koin/shared-state based rather than Voyager ViewModel based.

## Decisions

1. **Use project-owned route keys as the navigation API.**

   Create an `AppRoute` sealed hierarchy whose entries implement Navigation 3 `NavKey`. Object routes cover singleton pages such as Home, Device, FileShare, Settings, Toolbox, Favorites, Recent, and TaskList. Data routes cover parameterized pages such as notification detail, task result/detail, sync edit/detail, network edit, file filter manager, role edit, and similar destinations.

   Alternative considered: keep accepting Voyager `Screen` values and wrap them in Navigation 3 entries. That preserves old call sites briefly but keeps Voyager types in the app contract and makes removal incomplete.

2. **Introduce a small app navigation controller interface.**

   `MainState` should not store a Navigation 3 UI object directly. It should store a project-owned navigator interface with operations such as `push(route)`, `pop()`, `popToRoot()`, `replaceRoot(route)`, and `currentRoute`. `HomeNavigator` owns the `rememberNavBackStack` list and registers this controller with `MainState`.

   Alternative considered: expose the mutable back stack from `MainState`. That makes non-UI code depend on Compose snapshot state and spreads list mutation rules through the app.

3. **Render destinations through a single entry provider.**

   Replace `Screen.Content()` dispatch with a `NavDisplay` entry provider that maps each `AppRoute` to the existing composable content. During migration, each screen can become a regular composable or an object/class with a composable render function, but it must no longer implement Voyager `Screen`.

   Alternative considered: migrate leaf screens one at a time with a hybrid Voyager-inside-Nav3 host. This adds temporary nesting complexity and is unnecessary because the app has one primary Voyager host.

4. **Represent complex destination arguments with stable identifiers where practical, with a narrow process-local payload fallback.**

   Routes must remain lightweight and serializable. `AppRoute.Home` covers the singleton root route and `AppRoute.Payload(payloadId, routeKey)` is the temporary serializable key used for existing destinations that still need live screen objects, callbacks, or domain objects. The payload store is owned by the navigation layer and is process-local; those routes are not process-restorable until each destination is converted to stable ids.

   Alternative considered: serialize full domain objects into routes. That risks stale mutable data, large saved state, and serialization failures on non-JVM targets.

5. **Use NavigationEvent for stack-level back handling.**

   NavigationEvent should consume back events when the app back stack has more than one route. If the stack is already at root, Android keeps the current Activity behavior of moving the task to the background; other platforms keep their current window or button behavior.

   Alternative considered: keep Android Activity checking Voyager `navigator.items.size`. That blocks Voyager removal and leaves non-Android back behavior inconsistent.

## Risks / Trade-offs

- Route surface is broad -> Migrate through a single `AppRoute` and `AppNavigator` boundary first, then convert screens and call sites against that boundary.
- Complex object routes may not serialize cleanly -> Prefer stable ids and only use a temporary payload store for destinations that cannot be resolved from existing state.
- Back behavior can regress on Android -> Add tests for root vs non-root back handling and keep `moveTaskToBack(true)` only for root.
- Resume refresh depends on detecting Home -> Replace `navigator.lastItem is HomeScreen` with `mainState.currentRoute is AppRoute.Home` and test the same cases.
- Duplicate top-level routes can stack repeatedly -> Preserve the existing `pushSafe` behavior by suppressing pushes when the current route has the same route class/key.

## Migration Plan

1. Add Navigation 3 and NavigationEvent aliases and dependencies, keeping current lifecycle and espresso versions.
2. Add `AppRoute`, `AppNavigator`, route helpers, a process-local payload store for non-id routes, and unit tests for push, duplicate suppression, pending navigation, root detection, payload resolution, and pop behavior.
3. Replace `HomeNavigator` with a Navigation 3 host and register the app navigator with `MainState`.
4. Convert screen declarations and `LocalNavigator` call sites to route callbacks or `MainState` navigation methods.
5. Update platform entry points and drawer/notification/tray/quick-action code to open `AppRoute` values.
6. Remove Voyager dependency and obsolete extension helpers.
7. Run common, JVM, Android compile, and OpenSpec validation checks.

Rollback is a normal git revert of this change before release. No persistent data migration is required.

## Open Questions

None. Route payloads that cannot be id-based during implementation should use the temporary in-memory payload-store fallback described above and be listed in implementation notes.
