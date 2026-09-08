## Context

`EasyFileShareSettingsScreen` already persists Easy Share options through `SettingsState` and shows a `SnackbarHost`. The Easy Share HTTP service is controlled through `HttpShareFileServer.getInstance(fileShareState)`, with `isRunning()`, `stop()`, and `start(port)` available on supported native targets. `FileShareState` snapshots default sharing state when a link-share session is initialized or a client is authorized.

The requested behavior is UI-facing, but it crosses the settings screen and service runtime boundary because changing the port while the service is running needs a user-confirmed restart.

## Goals / Non-Goals

**Goals:**

- Give immediate Snackbar feedback for both enabling and disabling automatic startup.
- Detect when the Easy Share service is running during a port change and offer a Snackbar restart action.
- Restart the Easy Share HTTP service after the user confirms through the Snackbar action.
- Preserve existing connections and sessions when default share path, default auto approval, same-device auto approval, or password access settings change; only new connections use the updated defaults.

**Non-Goals:**

- Do not restart the whole application from the settings page.
- Do not force-restart the Easy Share service immediately after saving the port.
- Do not migrate or change persisted settings keys.
- Do not change JS/Wasm behavior where the HTTP share server is unavailable.

## Decisions

- Reuse the existing Material3 `SnackbarHostState` and show a restart action from the port-save coroutine.
  - Rationale: the request is explicitly Snackbar-based and the screen already owns a Snackbar host.
  - Alternative considered: confirmation dialog. This would interrupt the settings flow and does not match the requested UI.

- Restart the service by stopping the current `HttpShareFileServer` instance and starting it again with the persisted port after the Snackbar action is clicked.
  - Rationale: the existing platform implementations already expose stop/start, and persisting the port before restart keeps the setting durable even if restart fails.
  - Alternative considered: automatically restart after port save. This removes user control while active clients may still be connected.

- Treat default share path, default auto approval, same-device auto approval, and password access as defaults for future sessions or future authorizations only.
  - Rationale: existing authorized clients hold their own selected files and authorization state; mutating them in place would surprise connected users and can interrupt transfers.
  - Alternative considered: immediately rewrite active authorization state. This has higher runtime risk and conflicts with the requested "only affects new connections" behavior.

## Risks / Trade-offs

- Restart can fail because the new port is unavailable or the platform service cannot bind → show an error Snackbar and rely on existing server failure notification/logging.
- Saving the port while the service keeps running means UI settings and active server port can temporarily differ → Snackbar copy must make the pending restart clear.
- Snackbar actions are transient → users can save the setting and restart later by stopping/starting Easy Share from existing flows.
