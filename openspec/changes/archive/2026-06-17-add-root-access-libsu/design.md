## Context

The Android implementation already has a Shizuku-backed privileged file service. `ShizukuManager` owns authorization and service binding, `ShizukuFileServiceClient` exposes file operations over Binder, and Android `FileUtils`/`PathUtils` use `withShizukuFallback` when normal app IO fails with a permission error.

Root support needs explicit authorization controls from Settings, no surprise prompts unless the user opts in, and file operations that behave like the current Shizuku path when root is not active. The change also introduces a new external Android dependency, topjohnwu/libsu, so root access should be isolated to Android source sets and keep common code unchanged.

## Goals / Non-Goals

**Goals:**
- Add libsu-based root authorization and status reporting for Android.
- Provide a root-backed file operation client with the same operation surface currently used through Shizuku.
- Replace Shizuku-only fallback helpers with a privileged access abstraction that uses Root directly when granted, otherwise chooses one authorized backend after local permission failures.
- Surface Root in the existing permissions settings page and startup reminder flow.
- Add a user-controlled permissions setting for requesting Root during app startup.
- Preserve existing content URI read-only behavior.

**Non-Goals:**
- Do not add root-only UI flows beyond the existing permissions settings page.
- Do not prompt for root automatically during app startup unless the user has enabled the startup Root request setting.
- Do not change iOS, desktop JVM, JS, or Wasm behavior.
- Do not make root access a requirement for normal file management when standard Android permissions are sufficient.

## Decisions

- Decision: Add a shared Android-only privileged file client abstraction.
  - Rationale: Current `FileUtils` and `PathUtils` know about `ShizukuManager` directly. Introducing an interface such as `PrivilegedFileClient` lets Shizuku and root expose the same methods (`list`, `getFile`, `getFileInfo`, `delete`, `createDirectory`, `createFile`, `rename`, `exists`, `totalSpace`, `freeSpace`, `openFile`) without duplicating fallback code at every call site.
  - Alternatives considered: Keep adding `RootManager` calls beside every Shizuku call. That is simpler at first, but it makes ordering, error handling, and future privileged backends harder to reason about.

- Decision: Use libsu for explicit root acquisition and a root process service for file operations.
  - Rationale: A root service mirrors the existing Shizuku Binder model and avoids building complex file operations out of shell command strings. The service can reuse the current file operation semantics, return typed results, and expose file descriptors for stream/range read and write paths.
  - Alternatives considered: Use only `su` shell commands for every file operation. That is more fragile because paths and binary payloads require careful quoting/encoding, and it would diverge from the current Shizuku operation model.

- Decision: Keep normal file IO first, then choose exactly one authorized privileged backend.
  - Rationale: Root and Shizuku are alternative privileged channels, not cumulative requirements. When normal IO fails with a permission error, the app should use Root if it is already granted; otherwise it should use Shizuku if Shizuku is already granted. The app never requests Root or Shizuku from passive file operations.
  - Alternatives considered: Try Shizuku and then root as a chain. That is more forgiving when a backend is temporarily unavailable, but it obscures which privileged channel is active and can run the same operation through multiple elevated contexts.

- Decision: When Root is already granted, use root-backed access before normal Android app file IO.
  - Rationale: A user who has granted Root expects protected and ordinary filesystem operations to use the same elevated backend consistently. This avoids mixed local/root semantics and makes Root a clear global file operation mode.
  - Alternatives considered: Keep local IO first even when Root is granted. That is less privileged for ordinary paths, but it conflicts with the requested Root mode and can produce inconsistent behavior across paths.

- Decision: Gate startup Root authorization prompts behind a setting on the permissions page.
  - Rationale: Startup prompts are disruptive, so they must be opt-in. If enabled, startup may invoke libsu authorization when Root is not already granted; otherwise startup only performs passive checks.
  - Alternatives considered: Always request Root at startup. That is convenient on rooted devices but surprising and too aggressive for users who only want manual Root authorization.

- Decision: Model Root as a platform permission.
  - Rationale: The permissions settings page and startup reminder already provide title, description, status, request action, and refresh behavior. Adding `PermissionIds.Root` keeps root authorization consistent with Shizuku while reporting `Unsupported` when no root provider is available.
  - Alternatives considered: Add a separate Root settings page. That would duplicate permission UI with no extra workflow benefit for the first implementation.

## Risks / Trade-offs

- Root prompts can be disruptive if triggered from passive operations -> root request MUST only happen from the permissions settings request action or the explicit startup request setting.
- Root shell availability varies by device and su implementation -> map missing su or bind failures to `Unsupported` or `Denied`, keep normal file behavior when unavailable, and preserve the original permission error when no privileged backend succeeds.
- Root file operations can modify protected paths -> keep the same operation surface as Shizuku, avoid new destructive capabilities, and surface failures through existing task/error UI.
- Root process lifecycle can be killed or revoked -> treat the root client as transient, re-check status before use, and fall back to the original error if the client is unavailable.
- Adding libsu affects Android build only -> declare dependencies in Android source sets and keep common source sets untouched unless only IDs/models are needed.

## Migration Plan

1. Add libsu dependency aliases to the Gradle version catalog and Android source set dependencies.
2. Add root state/manager/client classes under Android source sets.
3. Introduce a privileged file client/fallback abstraction and adapt Shizuku plus root to it.
4. Replace Shizuku-only fallback calls in Android `FileUtils` and `PathUtils`.
5. Add Root to the Android permission provider and status/request mapping.
6. Validate with unit tests and Android build/test tasks.

Rollback is straightforward: remove the Root permission entry and root backend from the privileged fallback list, then remove libsu dependencies if needed.
