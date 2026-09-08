# Change: Update device share save actions

## Why
Device-share notifications show Save and View but both trigger the same approve path, so Save does not persist the cached save location. Users expect Save to auto-save using the cached path and to configure future saves.

## What Changes
- Split device-share Save and View into distinct request actions.
- Save approves using the cached device receive share path and persists auto-save configuration.
- View approves the request without changing auto-save settings.
- Notification detail messaging explains Save vs View behavior.

## Impact
- Affected specs: handle-device-share-requests (new)
- Affected code: notification actions, request handling, device receive share cache
