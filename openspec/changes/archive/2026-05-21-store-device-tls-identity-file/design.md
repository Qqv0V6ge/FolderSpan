## Context
The existing HTTPS device transport already uses per-device self-signed TLS certificates and client-side SHA-256 fingerprint pinning. The requested change is limited to local persistence of the server identity material. The wire protocol, discovery fields, and trust store for remote device fingerprints stay the same.

## Goals
- Keep each device's TLS identity independent.
- Persist the local TLS identity outside `Settings`.
- Store identity material in an app-private directory with an encrypted/obfuscated file name.
- Store file contents in three separately encrypted segments with randomized filler and randomized segment order.
- Avoid compatibility with legacy settings-backed identity data.

## Non-Goals
- Do not change HTTPS routing, request bodies, or file transfer payload formats.
- Do not change remote device fingerprint pinning or trusted-device fingerprint storage.
- Do not migrate old `deviceTlsIdentity.*` settings values.
- Do not expose a user-facing setting for the storage format.

## Storage Layout
Each supported native platform resolves an app-private TLS identity directory:

- Android: under `androidContext().filesDir`.
- JVM desktop: under the same per-user FolderSpan application data area used for durable app files.
- iOS: under the app sandbox home/application-support area.

The directory contains a single active identity file. The logical identity file name is encrypted or otherwise non-readable using an internal storage key and encoded as a filesystem-safe name. The clear text name must not appear on disk.

## File Format
The identity file is a three-segment container:

- The file contains exactly three segment envelopes.
- Each segment is encrypted independently with its own nonce/IV.
- One encrypted segment stores the platform TLS identity payload.
- The other two encrypted segments store random filler bytes.
- The identity segment is chosen randomly on write.
- Filler segment sizes are randomized so the full file size is not stable.
- Segment plaintext includes an internal segment type marker after decryption so readers can identify the identity payload without storing the identity segment index in clear text.

The platform TLS identity payload may remain platform-specific:

- JVM/Android can store the PKCS12 keystore bytes already used to initialize the server `SSLContext`.
- iOS can store the certificate PEM and private-key PEM needed to create the OpenSSL server context.

## Encryption
Use an internal, deterministic storage key source that is independent from user-editable crypto settings. The implementation may derive the key from stable local app/device material plus an application salt, or use platform secure storage where already available. The key must not be stored in the identity file.

## Error Handling
If the file is missing, unreadable, undecryptable, or does not contain exactly one valid identity segment, the device should create and persist a new file-backed TLS identity. Because legacy settings compatibility is intentionally out of scope, old settings-backed identities are ignored.

## Security Notes
This format is obfuscation plus encryption at rest for local identity material. It does not replace operating-system file protection. The directory should remain app-private and should not be placed in cache or shared external storage.
