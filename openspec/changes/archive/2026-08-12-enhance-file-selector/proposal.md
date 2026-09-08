## Why

`FileSelector` currently distinguishes only broad display and selection categories, so callers cannot express exact file-type and file-size rules before a user confirms a choice. Its single-column presentation and fixed-width dialog hosts also leave substantial space unused on expanded windows.

## What Changes

- Add a reusable file constraint model that can evaluate file kind, allowed file extensions, and minimum or maximum file size and return a typed rejection reason.
- Add two independent, optional `FileSelector` parameters: one controls which entries are displayed and one controls which displayed entries are selectable. Both parameters can be supplied at the same time and are evaluated in display-then-selection order.
- Make `FileSelector` automatically render a list in compact containers and an adaptive multi-column grid when more horizontal space is available, without changing navigation or selection behavior.
- Add a reusable full-size file-selector dialog shell that occupies all available width and height instead of imposing a narrow fixed maximum width.
- Keep directory traversal usable while applying file-only constraints, and preserve existing `FileSelector` behavior when the new parameters are omitted.

## Capabilities

### New Capabilities

- `file-selector-constraints`: Defines independent, composable display and selection constraints for `FileSelector`, including type and size rules and rejected-selection feedback.
- `adaptive-file-selector-layout`: Defines responsive list/grid presentation and full-size file-selector dialog behavior across compact and expanded windows.

### Modified Capabilities

None.

## Impact

- Shared Compose file-selection models and UI in `app/shared`.
- A reusable shared full-size selector dialog shell for feature-specific picker hosts.
- Localized strings and Compose/unit tests for constraint evaluation and responsive selector behavior.
- Existing `FileSelector` callers remain source-compatible because both new parameters default to unrestricted behavior.
