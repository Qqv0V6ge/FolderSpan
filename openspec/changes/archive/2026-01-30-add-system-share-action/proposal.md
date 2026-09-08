# Change: Add system share action on FileShareScreen

## Why
Users want to share selected files and folders through the native system share sheet from the file share screen, instead of only device/link sharing.

## What Changes
- Add a system share action in the "Share to other devices" section of `FileShareScreen`.
- Use the same multi-select share list as link sharing; if nothing is selected, default to sharing the full list.
- Add a common expect API and platform actuals to invoke native system share on each target.
- Use the Web Share API when available on web targets.

## Impact
- Affected specs: share-files-system (new)
- Affected code: `FileShareScreen`, new system share expect/actuals for Android/iOS/JVM/JS/Wasm
