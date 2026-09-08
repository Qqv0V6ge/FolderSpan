## Context

See `proposal.md` for motivation and the two capability specs for observable behavior.

`FileSelector` already accepts category-based `fileFilterTypesUiState` and `selectionFilterTypesUiState`, but its predicate can only distinguish broad `FileFilterType` groups. `FileSimpleInfo` exposes the entry kind, name, and reported size, so the selector has enough metadata to evaluate exact extension and size rules without reading file contents.

The selector currently renders entries in a single vertical list regardless of container width. Feature-owned dialogs also repeat selector-hosting layout and may impose narrow maximum widths, preventing the selector from using expanded desktop and tablet space.

## Goals / Non-Goals

**Goals:**

- Introduce one reusable constraint representation and expose it through exactly two new `FileSelector` parameters: `displayConstraints` and `selectionConstraints`.
- Keep display and selection stages independent, composable with existing category filters, deterministic, localizable, and source-compatible.
- Adapt automatically between compact List and expanded Grid presentation based on the selector's actual container width.
- Provide a reusable full-size selector dialog shell with stable navigation and actions.

**Non-Goals:**

- Parse file contents to determine their true media type or perform malware scanning.
- Define feature-specific allowed extensions or size limits; callers own those values.
- Implement upload, download, save, or transfer-progress behavior.
- Migrate every existing selector host to the new full-size shell in this change.
- Add a persisted manual List/Grid preference.

## Decisions

### 1. Use one immutable model for both constraint parameters

Add an immutable `FileSelectorConstraints` model near the shared selector state. It contains allowed entry kinds, normalized allowed extensions, optional `minSizeBytes`, and optional `maxSizeBytes`. Empty kind or extension collections mean unrestricted. Construction rejects negative limits and a minimum greater than a maximum.

`FileSelector` receives exactly two defaulted parameters:

```kotlin
displayConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted
selectionConstraints: FileSelectorConstraints = FileSelectorConstraints.Unrestricted
```

Extensions are normalized once by trimming whitespace, removing a leading dot, and lowercasing. Matching uses the file name rather than the historically ambiguous `mineType` field. A negative reported size represents unavailable metadata; zero remains a valid empty-file size.

Evaluation returns an allowed result or a typed violation such as `KindNotAllowed`, `ExtensionNotAllowed`, `SizeUnavailable`, `FileTooSmall`, or `FileTooLarge`. Typed violations keep localization out of the evaluator and make behavior easy to test.

A generic validation lambda was rejected because it would mix domain strings, localization, Compose stability, and rule evaluation. Individual extension and size parameters were rejected because every future rule would expand the composable signature twice.

### 2. Apply display constraints before selection constraints

The effective display predicate is the intersection of existing category display filters and `displayConstraints`. Rejected entries are not rendered and do not produce an error.

The effective selection predicate is the intersection of existing category selection filters and `selectionConstraints`. Entries rejected only at this stage remain visible. Their selection affordance is unavailable, while activating the item presents a localized typed rejection rather than silently doing nothing.

Extension and size rules apply only to non-directory entries. Directories remain visible and openable for traversal unless an explicit display kind rule excludes them. Opening a directory never selects it as a file. When constraints change, any now-invalid selected entry is removed before confirmation can proceed.

### 3. Derive List or Grid from actual container width

`FileSelector` measures its own available width instead of reading a global window-size class. Below 600 dp it keeps the information-dense `LazyColumn`. At 600 dp and above it uses `LazyVerticalGrid` with adaptive columns, a 280 dp minimum tile width, and a 168 dp minimum tile height. This avoids excessive column counts on wide desktop windows.

Both layouts consume the same sorted, filtered, and selection-aware entry model. They share directory activation, constraints, hidden-file behavior, sorting, rejection feedback, focus, keyboard, and accessibility semantics. Grid tiles use a flat treatment with a prominent centered icon, centered name and useful metadata, plus a top-end selection indication, rather than a Card per file.

Path and selection state survive responsive changes. Scroll state is keyed by current path and the nearest visible item is restored when switching between List and Grid.

Always using Grid was rejected because a one-column grid loses the compact list's information density. A manual mode toggle was deferred because automatic adaptation solves wasted space without introducing another setting.

### 4. Provide a reusable full-size selector dialog shell

Add a shared shell for feature-owned file selectors. It disables the platform default dialog width where necessary, fills available width and height, applies safe drawing and IME insets, and avoids fixed maximum-width or fractional-height caps.

The shell has a fixed title/header region, a weighted selector content region, and fixed cancel/confirm actions. Only the entry region scrolls. Compact windows behave as full-screen List selectors; expanded windows naturally activate the Grid and use previously empty horizontal space.

The shell remains opt-in so unrelated existing dialogs do not change unexpectedly. Feature changes can migrate their hosts independently.

### 5. Test constraints and both responsive presentations

Pure tests cover normalization, kind/type/size combinations, unavailable sizes, validation of invalid configurations, deterministic violations, display-before-selection ordering, and intersection with existing category filters.

Compose tests cover display-only and selection-only behavior, simultaneous constraints, directory traversal, localized rejection, compact List, expanded Grid, runtime resize, retained state, flat tile semantics, full-size dialog bounds, safe insets, and persistent header/footer actions.

## Risks / Trade-offs

- [File metadata can be stale or unavailable] → Treat selector constraints as early user feedback, return a typed unavailable-size violation, and require callers to retain authoritative validation before side effects.
- [Switching lazy containers can lose scroll position] → Key state by path and restore the nearest visible entry during layout transitions.
- [Grid tiles can become too narrow or look like miniature list rows] → Use adaptive columns with tested minimum width and height, a prominent icon, and bounded text lines.
- [A full-size dialog can overlap system bars or software keyboards] → Apply safe drawing and IME insets while keeping header/footer independently laid out.
- [Two new constraints can conflict with existing filters] → Define intersection semantics explicitly and keep unrestricted defaults for source compatibility.

## Migration Plan

1. Add the constraint model, evaluator, localized violations, and pure tests without changing current callers.
2. Add the two defaulted selector parameters and integrate them with existing display and selection stages.
3. Refactor entry preparation so List and Grid share one model, then add responsive rendering and retained scroll state.
4. Add the flat grid tile and verify input/accessibility behavior across layouts.
5. Add the opt-in full-size dialog shell and its Compose tests.
6. Let feature-specific changes adopt constraints and the dialog shell independently.

There is no persisted-data migration. Rollback leaves existing callers unaffected because both constraints default to unrestricted and the full-size shell is opt-in.
