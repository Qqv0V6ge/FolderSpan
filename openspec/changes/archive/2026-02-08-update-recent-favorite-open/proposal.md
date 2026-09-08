# Change: Update recent/favorite open behavior

## Why
Recent/favorite entries for non-local protocols do not reliably reopen because favorites are saved with `Local` metadata and open flows do not reconnect remote targets. This prevents users from jumping back into Device/Share/Network locations.

## What Changes
- Persist favorites with the file's `protocol` and `protocolId` instead of hard-coding Local.
- Resolve recent/favorite targets by `protocol` + `protocolId` when opening.
- If the target desk is not connected (Device/Share/Network), attempt to connect and open the target path on success.
- If the target cannot be resolved, show a snackbar message "目标不可用" and do not navigate.

## Impact
- Affected specs: `track-recent-files` (modified), `manage-favorites` (new).
- Affected code: `composeApp/.../RecentScreen.kt`, `composeApp/.../FavoriteScreen.kt`, `shared/.../FileFavoriteState.kt`.
