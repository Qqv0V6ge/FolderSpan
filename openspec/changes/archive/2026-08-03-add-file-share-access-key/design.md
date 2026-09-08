## Context

See `proposal.md` for motivation. The app-hosted HTTP service has one raw dispatcher that multiplexes discovery, device, path, bookmark, file, share, and HTTP WebRTC signaling routes. Several routes already perform token- or role-specific authorization, while discovery and connection-establishment requests intentionally precede those controls. First-party requests are issued by both long-lived Ktor clients and ad-hoc discovery, liveness, polling, and signaling calls.

File-share preferences use multiplatform Settings storage and selected keys participate in the existing Pro per-setting synchronization pipeline. The access-key control therefore crosses shared UI, persisted state, HTTP client construction, central server dispatch, CORS behavior, and remote settings application.

## Goals / Non-Goals

**Goals:**

- Add one opt-in gate before every normal app-hosted HTTP route while retaining all existing route-specific authorization behind it.
- Make configuration changes effective for newly issued requests without restarting services or recreating long-lived clients.
- Keep the access key out of ordinary settings presentation and provide a secure-random default generation path.
- Keep the enable flag and value consistent during local persistence and remote synchronization.
- Preserve compatibility by leaving protection disabled for existing installations until the user explicitly enables it.

**Non-Goals:**

- Replacing device tokens, role permissions, link-share authorization, WebRTC peer approval, or TLS certificate validation.
- Hiding the service at the network or port-scanning layer; rejected hosts can still observe that an HTTP service is listening.
- Providing per-device keys, key rotation sessions, request signing, replay prevention, or failed-attempt rate limiting.
- Providing transport confidentiality for plaintext HTTP deployments. The header is a bearer secret and TLS remains necessary against passive LAN capture.

## Decisions

### Store one fixed-header configuration atomically

Use a fixed internal header, `X-FolderSpan-Key`, and store `{enabled, value}` as one version-tolerant JSON setting. Normalize surrounding whitespace before persistence, accept only printable ASCII, and cap the value at 256 characters so it is safe to place in an HTTP header. A malformed persisted object falls back to the disabled default, while an enabled but invalid decoded value fails closed at request enforcement.

Keeping the header name fixed avoids persisting and synchronizing two independently editable protocol fields, prevents invalid user-chosen header names, and lets CORS advertise one stable contract. The UI intentionally describes only the access key rather than exposing this protocol detail.

Alternatives considered:

- Separate settings for enable state and value were rejected because sync or process interruption could expose a partial state.
- A user-configurable header name was rejected because it adds validation, interoperability, and CORS complexity without improving authorization strength.
- Reusing route-specific authorization tokens was rejected because discovery and initial connection flows occur before those tokens exist.

### Enforce the key once at the dispatcher boundary

Perform the comparison in the central raw HTTP dispatcher before path-specific routing and before protected operations. Keep `OPTIONS` outside the gate so browsers can complete CORS preflight, but advertise the fixed header only through the existing origin policy. Return an empty 403 for missing, mismatched, or invalid enabled configurations, and use a constant-work comparison for the configured and supplied byte sequences.

Central enforcement minimizes the chance that a newly added route silently omits the gate and ensures discovery, signaling, and authenticated file operations have the same outer boundary. Existing route-specific authentication remains authoritative after the shared key matches.

Alternatives considered:

- Adding checks to each route was rejected because it duplicates policy and makes future route omissions likely.
- Returning 401 was rejected because the shared key is an outer access gate rather than the route's identity credential; 403 also avoids triggering existing token-unauthorized client behavior.

### Resolve the key at request construction time

Add a reusable request-header helper. Install it in `defaultRequest` for long-lived device and share clients, and invoke it explicitly for ad-hoc discovery, liveness, approval-polling, and HTTP signaling requests. The helper re-reads the atomic setting for every request, removes an existing copy of the fixed header, and appends exactly one latest valid value.

This makes local edits and remotely applied settings effective on the next request without rebuilding clients. Capturing the key during client creation was rejected because stale clients would continue sending an old credential until reconnect or restart.

### Mask the value and generate a high-entropy default

Show only whether a value exists in the settings overview and use the existing password field in the editor. If the user tries to enable protection without a valid key, open the editor and enable protection only after successful confirmation. The generate action uses the existing cross-platform secure-random source to create 32 alphanumeric characters, avoiding header-hostile punctuation while providing a strong default.

Manual values remain supported for interoperability and memorability. Enforcing the generated format was rejected because it would prevent users from matching existing installations, although short manual values provide less protection.

### Synchronize the whole configuration as an explicit secret exception

Add the JSON configuration to the ordinary-setting allowlist so Pro settings sync uploads and applies both fields together. A newer remote value replaces the local value, then the settings state reload path refreshes the UI and subsequent requests read it immediately.

This is an explicit exception to the normal rule that remote setting values do not contain plaintext secret access keys. It enables the intended same-key experience across a user's devices, but makes the Pro settings account and remote setting store part of the key's trust boundary. Keeping the key local-only was rejected because users would have to copy and maintain the value independently on every device.

## Risks / Trade-offs

- [The bearer key is readable from plaintext HTTP traffic and can be replayed] → Treat the feature as a LAN access gate rather than transport encryption, retain TLS/pinning where already available, and document that TLS is required against passive capture.
- [The key is stored locally and synchronized remotely as plaintext setting data] → Mask it in UI, limit sync to the explicit allowlisted atomic key, use the authenticated Pro sync channel, and recognize account/remote-store compromise as key compromise.
- [Users can choose a low-entropy manual value and there is no attempt throttling] → Provide the secure-random 32-character action as the primary convenience path; rate limiting or a stronger minimum policy can be added separately if required.
- [A remotely newer configuration can unexpectedly lock out a device] → Apply enable state and value atomically and let users disable protection locally to recover access.
- [Reading Settings on each request adds small overhead] → Read only one bounded string and decode one small JSON object; favor immediate correctness over client-side caching complexity.
- [Third-party or older clients do not know the fixed header] → Protection remains disabled by default; once enabled, those clients are intentionally rejected until updated or configured.

## Migration Plan

1. Ship the new setting with the disabled default; no existing request behavior changes on upgrade.
2. Add the allowlisted atomic default so settings sync can distinguish an absent legacy value from an explicit disabled configuration.
3. Ensure first-party clients send the current key before users can enable enforcement from the settings screen.
4. When a user enables protection, require a valid saved value and immediately enforce it for subsequent server requests.
5. To roll back operationally, disable access-key protection. To roll back the code, older clients and servers ignore the stored setting, while removing the allowlist entry stops further remote updates without requiring a data migration.
