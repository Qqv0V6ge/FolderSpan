## Context
Path preferences already persist sort and hidden-file visibility by `protocol`, `protocolId`, and `path`. Ignore filtering extends that same scope with a list of enabled ignore files.

## Goals / Non-Goals
- Goals: support common ignore syntax, resolve only the nearest configured ancestor, provide local UI indication, and enforce remote-serving access denial.
- Non-Goals: full gitignore engine parity, old schema migration compatibility, or syncing ignore preferences between devices.

## Decisions
- Store `ignoreFiles` as `List<String>` on `FilePathPreference`.
- Resolve candidates from the current path up to the root and stop at the first row with non-empty `ignoreFiles`.
- Read only the enabled ignore files under that resolved directory.
- Match child paths relative to the resolved directory.
- Local UI marks ignored files with opacity; serving APIs remove ignored list entries and reject direct ignored reads/lookups.

## Risks / Trade-offs
- Ignore syntax is implemented in common Kotlin instead of depending on a platform gitignore library so the same matcher can run in common tests and shared state.
- Remote UI marking is best-effort and uses the locally configured protocol/protocolId/path scope; remote servers still enforce their own ignore configuration when serving.

## Migration Plan
No legacy database migration is required. The schema is updated directly and callers preserve existing sort and hidden-file values when toggling ignore files.
