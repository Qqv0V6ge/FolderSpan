## Context
The app needs an About Libraries page that lists third-party dependencies and license details. The change introduces a new external dependency for the UI.

## Goals / Non-Goals
- Goals:
  - Provide a Settings entry that opens the About Libraries page.
  - Use AboutLibraries Compose M3 UI components for consistent Material 3 styling.
- Non-Goals:
  - Custom theming beyond existing Material 3 setup.
  - Editing library metadata at runtime.

## Decisions
- Decision: Use AboutLibraries Compose M3 for the About Libraries page.
  - Why: Provides a ready-made Material 3 UI for library listing and details.
- Alternatives considered:
  - Manual rendering from a static list: more maintenance and higher risk of drift.

## Risks / Trade-offs
- New dependency may increase build size and introduce plugin configuration steps.

## Migration Plan
- Add dependency and plugin configuration.
- Add Settings entry and route.
- Verify the page renders and navigates correctly.

## Open Questions
- None.
