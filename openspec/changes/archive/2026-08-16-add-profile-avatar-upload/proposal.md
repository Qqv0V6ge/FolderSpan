## Why

The profile editor can only accept an avatar as a manually typed image URL, so a user who wants to use a picture they already have must first host it somewhere and paste a link. The backend already exposes a direct avatar upload endpoint, an avatar removal endpoint, and a public avatar read endpoint, and none of them are reachable from the app.

Pro profile screens live in `proMain`, which cannot depend on `app:shared` where `FileSelector` lives. The feedback attachment flow already solved this exact shape: `proMain` declares a picker contract and a composition-local slot, and `app:shared` fills that slot with a `FileSelector`-backed dialog. Avatar selection follows the same seam and adds one stage — an image editor — between selection and delivery.

## What Changes

- Add an avatar picture contract in `proMain` that lets a profile screen request a ready-to-upload avatar image and receive encoded bytes, without knowing how selection or editing happened.
- Add an `app:shared` provider that fills that contract by presenting `FileSelector` for image selection and then an image editor for framing the picture.
- Add a reusable image editor that supports rotation in quarter turns, horizontal and vertical flipping, zooming, panning, and cropping to a fixed square output, with a live preview of the exact region that will be uploaded.
- Add cross-platform image decoding and encoding so an edited image can be turned back into JPEG, PNG, or WebP bytes on Android, JVM, iOS, JS, and WasmJs.
- Add avatar upload and avatar removal to the Pro user API service and user repository, mapping to the existing multipart request pattern.
- Replace the avatar URL text field in the profile editor with an avatar picture control. Uploading or removing an avatar takes effect immediately through its own endpoint rather than waiting for the profile form's save action.
- Keep the selection size rule and the upload size rule separate: the selector guards decode cost, while the encoded output honors the documented upload limit.

## Capabilities

### New Capabilities

- `profile-avatar-management`: Defines how a signed-in user adds, replaces, and removes their avatar from the profile editor, including immediate effect, progress, success and failure feedback, and confirmation before removal.
- `avatar-image-editor`: Defines image selection through `FileSelector` and the editing surface that produces a framed, correctly oriented, upload-ready image.
- `image-encoding`: Defines decoding image bytes into an in-memory image and encoding an in-memory image back into JPEG, PNG, or WebP bytes across all supported platforms.

### Modified Capabilities

None.

## Impact

- New Pro avatar contract, profile editor control, view model state, and upload/remove flows in `proMain`.
- New `app:shared` avatar picker provider, selector dialog host, and image editor UI.
- New `core` image decoding and encoding capability with per-platform implementations for Android, JVM, iOS, JS, and WasmJs.
- New Pro user API service and repository operations for avatar upload and avatar removal.
- The profile editor no longer exposes a free-text avatar address field; existing avatars set that way continue to display and can be replaced or removed.
- Localized strings for the editor controls, upload progress, size and format rejections, removal confirmation, and success feedback.
