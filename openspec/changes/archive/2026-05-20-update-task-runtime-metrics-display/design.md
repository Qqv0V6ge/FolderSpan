# Design: Task Runtime Metrics Display

## Context
`Task` already persists transient runtime values in `values` and active item messages in `activeResults`. Existing transfer paths already report byte progress text for copy-like operations, while delete paths primarily report item progress.

## Decisions
- Store runtime metrics as internal `Task.values` keys so no public model/schema migration is required.
- Compute copy/move speed and ETA from bytes when a byte total is available; compute delete speed and ETA from completed item counts.
- Count current parallel execution from non-empty `activeResults`.
- Treat runtime metrics as current-session display state only; old tasks without these keys are not migrated.
- Change running/paused result display to return only active item messages. Pending queue display remains available through queue helpers but is not used by task result screens.

## Non-Goals
- Do not add persisted database fields.
- Do not infer byte totals for old or partially restored runtime data.
- Do not expose configured worker limits as the parallel count.
