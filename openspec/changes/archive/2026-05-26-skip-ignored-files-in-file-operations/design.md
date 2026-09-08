## Context

Ignore support is implemented as a shared matcher plus path-scoped preferences persisted by `FilePathPreference`. The matcher feeds browsing display, where ignored entries can be visually marked, and copy/move runtime creation, where directory trees are converted into persisted task queues.

Before this change, device/share serving also used ignore matches to hide children and reject direct served path access. That made ignore files behave like a remote access-control layer, while copy/move still traversed and transferred ignored entries. This change narrows ignore serving semantics and uses the same preferences as source-side file-operation filters.

## Goals / Non-Goals

**Goals:**
- Stop using ignore file matches to hide children from device/share list responses.
- Stop using ignore file matches to reject direct served path lookup/read requests.
- Apply source-side ignore rules before copy/move manifests are persisted.
- Support `Local`, `Share`, `Device`, and `Network` source protocols.
- Skip ignored files, ignored directories, enabled ignore files themselves, and explicitly selected ignored roots.
- Ensure move tasks never delete ignored source entries that were skipped.
- Preserve retry and continue behavior by persisting only entries that are eligible to execute.
- Keep ignore preference persistence, nearest preference resolution, parsing, matching, and local UI marking intact.

**Non-Goals:**
- Removing ignore file preferences or local ignored-state display.
- Hiding ignored entries from browsing lists.
- Denying remote reads/lookups of ignored paths.
- Applying destination-side ignore preferences as a write blocker.
- Changing hidden-file visibility, extension filtering, or delete-only tasks.
- Adding a new remote access-control mechanism.

## Decisions

- Remove ignore enforcement from serving code paths instead of deleting the shared matcher.
  - Rationale: normal device/share authorization remains responsible for access decisions, while the matcher is still needed for browsing display and task traversal.
  - Alternative considered: keep injection but make enforcement a no-op. Removing the dependency from serving paths makes the behavior clearer and reduces accidental reintroduction.
- Resolve ignore behavior from the source endpoint and source path before building copy/move queues.
  - Rationale: file operation traversal should use the same nearest-preference semantics as browsing.
  - Alternative considered: filter each concrete copy call. That would still persist ignored entries in manifests and retry metadata, which makes progress and recovery misleading.
- Add a shared operation ignore resolver that accepts `FileProtocol`, `protocolId`, source path, separator, and a protocol-specific ignore-file reader.
  - Rationale: `Local`, `Share`, `Device`, and `Network` all need the same matching behavior but different read/list clients.
  - Alternative considered: only support local ignore files. That would make the UI promise inconsistent across protocol tabs.
- Filter both selected roots and traversed descendants.
  - Rationale: users can manually select a dimmed ignored entry, and enabling ignore should still prevent that item from being copied or moved.
- Treat enabled ignore files themselves, such as `.gitignore`, as skipped operation entries.
  - Rationale: once an ignore file is enabled as a rule source, copying/moving it into the destination contradicts the user’s expectation that enabled ignore files shape the transfer manifest.
- For move tasks, build the delete-source stage from the same executable source entries and protect directories that contain skipped descendants.
  - Rationale: ignored entries are not moved, so deleting their parent directory would delete content the user asked to skip.
- Report skipped counts as task metadata, but do not create retry entries for skipped items.
  - Rationale: skipped items are intentional and should not look like failures.

## Risks / Trade-offs

- Previously hidden served files become remotely visible when a share/device permission allows them -> Document as breaking behavior and rely on existing share/device authorization boundaries.
- Users may expect ignore files to protect secrets from sharing -> Keep UI copy and release notes clear that ignore files are local/file-operation hints, not security policy.
- Remote ignore file reads can fail or be expensive -> Treat unreadable enabled ignore files as absent for that operation and keep traversal deterministic.
- Large remote trees may still require traversal to discover entries before filtering -> Apply filtering before enqueuing and skip ignored subtrees once a directory itself matches ignore rules.
- Moving a directory with ignored descendants may leave the original directory behind -> Surface skipped counts in task metadata so the leftover source is expected.
