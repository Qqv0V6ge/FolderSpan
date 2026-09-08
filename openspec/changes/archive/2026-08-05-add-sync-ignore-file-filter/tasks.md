## 1. Task model and Ignore resolver

- [x] 1.1 Add advanced Ignore fields to `SyncTask`, normalize supported file names, enforce structural validation, and persist the current model without legacy compatibility code.
- [x] 1.2 Refactor endpoint-aware Ignore file reading for reuse by normal file operations and sync, including strict Local, Device, and Network loading with stable multi-file order and size checks.

## 2. Sync execution

- [x] 2.1 Load the task matcher before target mutation and combine it with simple filters during source snapshot traversal, directory pruning, and Ignore-file self-exclusion.
- [x] 2.2 Expose a shared exclusion predicate/protected-path behavior for deletion-capable planners and directory-watch reconciliation without changing current non-deleting sync semantics.
- [x] 2.3 Add core tests for combined rules, strict failure with zero target writes, supported endpoint readers, directory pruning, and refreshed rules across runs.

## 3. Shared UI and localization

- [x] 3.1 Add the state-hoisted advanced Ignore switch, selected-name summary, and accessible multi-select dialog to the sync editor; save and restore both task fields.
- [x] 3.2 Add Simplified Chinese and English strings for advanced Ignore configuration, selection, validation, and runtime read failures.
- [x] 3.3 Add shared UI/state tests for default-off behavior, empty-selection validation, preserved disabled selections, multi-select behavior, and Switch/Checkbox semantics.
- [x] 3.4 Discover supported regular Ignore files directly under the configured source root, enforce source-root presence when saving, and keep discovery endpoint-aware for Local, Device, and Network sources.
- [x] 3.5 Place advanced Ignore above simple filter rules, hide its selection entry when no candidates exist, restrict the dialog to discovered candidates, and add shared UI/state tests for these behaviors.
- [x] 3.6 Clear confirmed and draft Ignore selections immediately whenever the source type, endpoint reference, or root path changes, without treating initial edit composition or switch toggles as a source change.
- [x] 3.7 Redesign advanced Ignore as a compact Material 3 contained settings item with an inline file-selection row, selected-name summary, and no selection action/icon when candidates are absent; update UI tests.
- [x] 3.8 Flatten advanced Ignore to the same horizontal bounds as Sync empty directories, removing the card outline, container backgrounds, divider, and extra horizontal insets while preserving accessible row interactions.
- [x] 3.9 Restrict Advanced Ignore enable/disable interaction to the Material 3 Switch itself and keep the surrounding header non-clickable; update shared UI tests.
- [x] 3.10 Restore the Ignore file selector as a transparent Material 3 ListItem with standard content insets while preserving full-row selection interaction and Switch-only enable/disable behavior.
- [x] 3.11 Convert the Advanced Ignore header to a transparent Material 3 ListItem so both rows share identical content insets, while keeping only the nested Switch toggleable and aligning the no-candidate status text.
- [x] 3.12 Align the Advanced Ignore switch row and file selector with adjacent flat switch settings by removing ListItem horizontal insets, matching title styling and trailing control placement, and preserving vertical breathing room.

## 4. Verification and documentation

- [x] 4.1 Update `md_descriptions_paths.md` for the new OpenSpec Markdown artifacts and run strict OpenSpec validation.
- [x] 4.2 Run core and shared JVM tests, Android debug compilation, and relevant Web/iOS compilation checks; resolve regressions attributable to this change.
- [x] 4.3 Update the OpenSpec Markdown descriptions for the refined behavior, rerun affected tests and platform compilation checks, and pass strict OpenSpec validation.
- [x] 4.4 Update Markdown descriptions for the refined source-reset and visual behavior, rerun affected tests and platform compilation checks, and pass strict OpenSpec validation.
- [x] 4.5 Update Markdown descriptions for the flat settings layout, rerun shared UI tests and relevant platform compilation checks, and pass strict OpenSpec validation.
- [x] 4.6 Update Markdown descriptions for switch-only toggling and standard ListItem spacing, rerun shared UI tests and relevant platform compilation checks, and pass strict OpenSpec validation.
- [x] 4.7 Update Markdown descriptions for matching header and selector ListItem insets, rerun shared UI tests and relevant platform compilation checks, and pass strict OpenSpec validation.
- [x] 4.8 Update Markdown descriptions for adjacent-setting alignment, rerun shared UI tests and relevant platform compilation checks, and pass strict OpenSpec validation.
