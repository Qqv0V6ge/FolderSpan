# Change: Store device TLS identity in obfuscated files

## Why
Device HTTPS currently keeps its self-signed TLS identity in app settings. The TLS private key and certificate should instead live in a dedicated file-backed store with obfuscated file names and independently encrypted randomized content segments.

## What Changes
- Store the per-device TLS certificate/private-key identity in a dedicated app-private directory instead of settings.
- Use an encrypted/obfuscated file name for the identity file.
- Write the file as three independently encrypted segments: one segment contains the TLS identity payload, and two segments contain random filler.
- Randomize segment order and filler size so the total file size varies between writes.
- Do not migrate or read the previous settings-backed TLS identity; devices will generate a new file-backed identity when no valid file-backed identity exists.
- Keep HTTPS transport, published SHA-256 certificate fingerprint, and client certificate pinning behavior unchanged.

## Impact
- Affected specs: `http-socket-tls-transport`
- Affected code: `DeviceTlsIdentity` platform implementations, TLS identity storage helpers, platform app-data path resolution, TLS identity tests
