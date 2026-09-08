## 1. Constraint Model

- [x] 1.1 Add common tests for extension normalization, file kind, minimum/maximum size, unavailable size, invalid configuration, and deterministic typed violations.
- [x] 1.2 Implement immutable `FileSelectorConstraints`, its unrestricted default, construction validation, and pure evaluator.
- [x] 1.3 Add localized mappings for kind, extension, unavailable-size, too-small, and too-large violations with actual and configured values.

## 2. FileSelector Constraint Integration

- [x] 2.1 Add defaulted `displayConstraints` and `selectionConstraints` parameters and document display-before-selection behavior.
- [x] 2.2 Intersect display constraints with existing category display filters while preserving directory traversal for file-only rules.
- [x] 2.3 Intersect selection constraints with existing category selection filters and remove stale selections that become invalid.
- [x] 2.4 Present visible rejected entries with unavailable selection affordances, localized activation feedback, and accessible rejection semantics.
- [x] 2.5 Add tests for display-only, selection-only, simultaneous constraints, existing-filter composition, directory navigation, dynamic constraint changes, and omitted-parameter compatibility.

## 3. Responsive List and Grid

- [x] 3.1 Add Compose tests that assert containers below 600dp render List and containers at least 600dp wide render multiple Grid columns.
- [x] 3.2 Refactor selector entry preparation so List and Grid consume the same sorted, filtered, and selection-aware item model.
- [x] 3.3 Implement container-width switching with adaptive Grid columns, a tested minimum tile width, and path-keyed scroll restoration across resize.
- [x] 3.4 Implement a flat grid tile with file icon, name, metadata, focus, hover, selected, rejected, keyboard, and accessibility states without Card containers.
- [x] 3.5 Verify navigation, filters, hidden-file controls, sorting, constraints, selection, and rejection feedback behave identically in both layouts.
- [x] 3.6 Add a wide-window geometry regression test and enlarge flat Grid entries to a readable 280dp by 168dp minimum with a prominent centered icon.

## 4. Full-Size Selector Dialog Shell

- [x] 4.1 Add an opt-in reusable selector dialog shell that fills available width and height and applies safe drawing/IME insets.
- [x] 4.2 Keep title/header and cancel/confirm actions fixed while the weighted selector entry region scrolls independently.
- [x] 4.3 Add compact and expanded Compose tests for dialog bounds, persistent actions, long directories, and Grid activation.

## 5. Verification

- [x] 5.1 Run shared common/JVM unit and Compose tests for selector constraints, List/Grid behavior, and full-size dialog layout.
- [x] 5.2 Compile affected common, Desktop/JVM, Android, JS/Wasm, and iOS source sets to catch cross-platform API drift.
  - Verified Desktop/JVM, Android, JS, and Wasm compilation.
  - Blocked: common metadata compilation fails in the pre-existing `DefaultAppLanguagePlatform` implementation, and iOS compilation requires unavailable curl/libssh2/libsmb2/OpenSSL headers.
- [x] 5.3 Manually verify compact/expanded resizing, directory navigation, rejected selection messaging, keyboard/accessibility behavior, and full-size dialog insets.
