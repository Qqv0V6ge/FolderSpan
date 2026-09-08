## Context

See `proposal.md` for motivation and the three capability specs for observable behavior.

Module dependencies run in one direction: `app:shared` → `proMain` → `core`. Profile screens live in `proMain/presentation/screen/profile`, while `FileSelector` and its supporting UI live in `app:shared/ui/components/file`. `proMain` therefore cannot reference the selector directly.

The feedback attachment flow already crosses this boundary. `proMain` declares `FeedbackAttachmentPicker` plus a `staticCompositionLocalOf` slot, and `app:shared` supplies an implementation through `FeedbackAttachmentPickerProvider`, which renders a `FullSizeFileSelectorDialog` hosting `FileSelector` and reads the chosen file's bytes. Avatar selection reuses that seam.

`FeedbackApiService` already builds a replayable multipart body for authenticated uploads, so avatar upload has an established request shape to follow.

The backend contract provides an upload endpoint that accepts a single multipart `file` field limited to JPEG, PNG, or WebP under the documented size limit, a removal endpoint that clears both the stored object and the profile's avatar address and stays successful when repeated, and a public read endpoint keyed by user UUID. The profile update endpoint also accepts an avatar address string, which is what the current text field writes.

## Goals / Non-Goals

**Goals:**

- Keep `proMain` free of any knowledge about how an avatar image is chosen or edited; it requests an image and receives encoded bytes.
- Reuse the existing composition-local picker seam rather than inventing a second cross-module mechanism.
- Provide rotation, flipping, zooming, panning, and cropping in one editor whose preview matches the uploaded result exactly.
- Produce upload-ready bytes that satisfy the documented format and size limits by construction.
- Make avatar changes take effect immediately, independently of the profile form's save action.

**Non-Goals:**

- Free-form image editing such as filters, drawing, color adjustment, or arbitrary aspect ratios.
- Editing or re-cropping an avatar that is already stored on the server.
- Reading image orientation metadata to auto-correct rotation; the user rotates manually.
- Migrating avatars previously set as external addresses to uploaded objects.
- Adding an in-app camera capture source.

## Decisions

### 1. Extend the existing picker seam rather than adding a new mechanism

`proMain` declares an avatar picture contract and a composition-local slot next to the profile screens, mirroring `FeedbackAttachmentPicker` and `LocalFeedbackAttachmentPicker`. The contract exposes a single request that reports either an upload-ready image, a user cancellation, or a failure.

`app:shared` supplies a provider that wraps application content, fills the slot, and renders its own dialogs when a request is pending. The profile screen reads the slot and calls it; it never sees `FileSelector`, the editor, or any image type.

The delivered value carries the encoded bytes and their content type. It deliberately carries no source file path or original file name, because after editing neither describes the uploaded object and both would leak local filesystem details into a Pro screen.

A Koin-injected service was rejected because the picker must render dialogs inside the composition, which is what the composition-local pattern already handles. Threading a callback through navigation was rejected because every intermediate route would have to forward a parameter it does not use.

### 2. `app:shared` owns the whole selection-to-bytes pipeline

The provider runs three stages before it reports a result: selection through `FullSizeFileSelectorDialog` hosting `FileSelector`, reading the selected file's bytes, then editing and encoding.

Cancelling at any stage reports a cancellation rather than a failure, so the profile screen can distinguish "user changed their mind" from "something went wrong" and only show an error for the latter. A pending request that is superseded or disposed reports cancellation, matching the disposal behavior the feedback provider already implements.

Decoding, editing, and encoding run off the main dispatcher, and a superseded or dismissed request cancels the in-flight work.

### 3. Separate the selection size rule from the upload size rule

The selector's constraints and the API's constraint solve different problems and must not share a value.

Selection constraints limit which files can be chosen, and their purpose is to keep decoding affordable in memory. A photograph far larger than the upload limit is a perfectly valid source, because cropping and re-encoding shrink it by an order of magnitude. Using the upload limit as the selector's maximum size would reject ordinary camera photos before the user ever sees them.

The upload limit therefore applies only to the encoded output. Display constraints restrict visible entries to the supported image extensions; selection constraints add a files-only kind rule and a generous maximum source size chosen to bound decode cost.

### 4. Encode to a fixed square so the size limit is satisfied by construction

The editor always produces a square image at a fixed edge length, encoded as JPEG at a fixed quality. An avatar is displayed as a small circle, so a square source is the natural shape and a fixed edge length removes any dependence on the source image's dimensions.

This makes the encoded size essentially constant and far below the upload limit, which removes the need for a quality-reduction retry loop. The output is still validated against the limit before delivery so the rule is enforced rather than assumed, and a violation surfaces as a failure instead of a rejected request at the server.

JPEG is chosen because it is supported by every target's encoder, needs no alpha channel for a circular avatar, and is the smallest of the three accepted formats. PNG and WebP remain available in the encoding capability for other callers.

### 5. Represent editor state as orientation plus a viewport, not as repeated bitmap rewrites

Editor state is a quarter-turn rotation count, independent horizontal and vertical flip flags, a scale factor, and a pan offset. The crop window is fixed and centered; the image moves beneath it. This is the conventional avatar-cropper interaction and it keeps the preview and the output derived from the same values.

Every gesture updates these values only. The source image is decoded once and never mutated, so repeated rotation or zooming causes no cumulative quality loss and no repeated allocation. The final image is produced in a single pass that applies orientation and the viewport transform while drawing into the fixed-size output.

Scale is clamped so the crop window is always fully covered by the image, and pan is clamped so it can never expose an empty region. Rotation is restricted to quarter turns because arbitrary-angle rotation would require exposing empty corners or an additional automatic zoom, neither of which the request calls for.

### 6. Place decoding and encoding in `core`, split by rendering backend

Decoding and encoding are platform capabilities with no Pro or profile semantics, and `core` is the lowest module with all five target source sets. Placing them there keeps them reusable and independently testable, and lets the editor UI stay free of platform code.

Decoding uses the multiplatform common API already available in the project's Compose version, so it needs no per-platform implementation. Encoding has no common API and needs one.

Encoding splits two ways rather than five: Android encodes through its platform bitmap compressor, and JVM, iOS, JS, and WasmJs all encode through the Skia image encoder that backs Compose on those targets. The four Skia targets share one implementation via an intermediate source set they all depend on, following the existing `nonJvmMain` and `serverRouteMain` pattern in the `core` build script.

Encoding reports an explicit failure when a requested format is unavailable on a target, rather than silently substituting another format.

### 7. Avatar changes take effect immediately

Upload and removal are their own endpoints, so they apply as soon as they succeed rather than joining the profile form's save action. Mixing them into the form would mean a user could edit their avatar, leave without saving, and see an inconsistent result, since the object was already stored server-side.

While a request is in flight the control shows progress and rejects further requests, preventing duplicate submissions. Success refreshes the profile from the response so the newly stored address is displayed, and reports a distinct confirmation. Failure leaves the previously displayed avatar in place and offers a retry.

Removal is destructive and irreversible from the app's perspective, so it requires a second confirmation. Repeated removal stays successful, matching the documented endpoint behavior, so a retry after an ambiguous failure is safe.

### 8. Replace the avatar address field rather than keeping both

Two independent ways to set the same value would require a precedence rule that no user can predict: a typed address and an uploaded object would each silently overwrite the other depending on which action was taken last and whether the form was saved.

The profile editor therefore exposes an avatar picture control instead of the address field, and the form's save action no longer submits an avatar address. An avatar previously set as an external address still displays and can be replaced or removed through the new control.

### 9. Degrade explicitly when no provider is present

If the composition-local slot is empty, the profile screen presents the avatar control as unavailable rather than failing when tapped. This keeps `proMain` screens usable in tests and in any host that does not install the provider, and mirrors the null-check the feedback detail screen already performs before falling back.

`FileSelector` browses an in-memory store on JS and WasmJs, where the user's real pictures are not reachable. Those targets receive an alternative selection source through the same provider, so the contract and the editor stay identical and only the first stage differs.

## Risks / Trade-offs

- Decoding a very large source image is memory-intensive on constrained devices. Mitigated by the selector's maximum source size rule and by decoding once rather than per gesture, but a hostile or unusually large file can still be costly; the failure surfaces as a readable message rather than a crash.
- A file whose extension is a supported image type but whose contents are not decodable will only fail after selection. Contents are not inspected during selection, so the failure is reported from the editor stage with an explanation.
- Encoder availability and output size differ slightly between the platform bitmap compressor and the Skia encoder. The fixed output dimensions and the post-encode size validation keep both within the documented limit, and tests assert the limit on every target.
- Placing the editor in `app:shared` means its wording and visual treatment are owned outside `proMain`, so future profile-specific changes to it require a change in another module. Accepted because it keeps the profile screen free of image handling entirely.
- Immediate effect means a user who uploads and then abandons the profile form has still changed their avatar. This is intentional and made visible by the distinct success confirmation.

## Migration Plan

The change is additive except for the avatar address field. Stored avatar addresses are untouched: the profile response still carries an address, the app still displays it, and the new control replaces or removes it. No data migration and no server-side change are required, and the profile update request simply stops sending an avatar address.

## Open Questions

None.
