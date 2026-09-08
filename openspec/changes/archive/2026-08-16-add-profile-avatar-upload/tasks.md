## 1. Image Encoding in `core`

- [x] 1.1 Add an intermediate source set that JVM, iOS, JS, and WasmJs depend on, following the existing `nonJvmMain` pattern in the `core` build script.
- [x] 1.2 Add the common decode and encode declarations, the supported format model, the content-type mapping, and a typed failure for unavailable formats.
- [x] 1.3 Implement encoding for Android through the platform bitmap compressor.
- [x] 1.4 Implement encoding for the Skia-backed targets in the shared intermediate source set.
- [x] 1.5 Add tests for decode round-trips, dimension preservation, quality ordering for lossy output, undecodable and empty input, and unavailable-format failures.

## 2. Avatar Picture Contract in `proMain`

- [x] 2.1 Add the avatar picture contract and its composition-local slot next to the profile screens, mirroring the feedback picker seam.
- [x] 2.2 Define the delivered picture model carrying encoded bytes and content type, deliberately without source path or file name.
- [x] 2.3 Define the three-way outcome so delivered, cancelled, and failed are distinguishable by callers.
- [x] 2.4 Add tests asserting that an absent provider yields an unavailable control and never fails when activated.

## 3. Picker Provider and Selection in `app:shared`

- [x] 3.1 Add a provider that fills the contract slot, holds at most one pending request, and cancels a pending request when superseded or disposed.
- [x] 3.2 Host the shared file selector in the full-size selector dialog with image-only display constraints, a files-only single-selection rule, and a generous maximum source size that guards decode cost rather than the upload limit.
- [x] 3.3 Read the selected file's bytes off the main dispatcher, reporting a failure when the bytes cannot be read.
- [x] 3.4 Provide an alternative selection source on JS and WasmJs behind the same contract, leaving the editing and encoding stages unchanged.
- [x] 3.5 Add tests for cancellation at the selection stage, supersession, disposal, single-selection behavior, and that a source larger than the upload limit is still selectable.

## 4. Image Editor in `app:shared`

- [x] 4.1 Add the editor state model holding quarter-turn rotation, independent horizontal and vertical flip flags, scale, and pan offset.
- [x] 4.2 Add pure tests for the state model covering rotation wraparound, flip involution, axis independence, and the clamping that keeps the crop window covered.
- [x] 4.3 Implement the editing surface with a fixed centered crop window, gesture-driven zoom and pan, and rotate and flip controls.
- [x] 4.4 Implement single-pass rendering of the confirmed result into a fixed-size square image, decoding the source only once.
- [x] 4.5 Encode the result, validate it against the caller's size limit, and report a failure when it exceeds the limit.
- [x] 4.6 Report a failure with user-readable wording when the source cannot be decoded, and a cancellation when the editing stage is dismissed.
- [x] 4.7 Add tests asserting the delivered picture matches the previewed region, orientation, and mirroring, and that output is square, within the size limit, and carries a matching content type.
- [x] 4.8 Verify the editor across compact and expanded windows, dark mode, and progress state while work is in flight.

## 5. Avatar Transport in `proMain`

- [x] 5.1 Add avatar upload to the Pro user API service as a single-file multipart request using the existing replayable multipart pattern.
- [x] 5.2 Add avatar removal to the Pro user API service.
- [x] 5.3 Add both operations to the user repository interface and its implementation, mapping server errors to user-readable messages without leaking raw error text, tokens, endpoints, or identifiers.
- [x] 5.4 Add mock-engine tests for request shape, authentication, success mapping, repeated removal remaining successful, unauthenticated handling, and server rejection mapping.

## 6. Profile Editor Integration in `proMain`

- [x] 6.1 Replace the avatar address field with an avatar picture control showing the current avatar or a placeholder.
- [x] 6.2 Stop submitting an avatar address from the profile form's save action.
- [x] 6.3 Wire the choose and replace actions to the picture contract and upload the delivered bytes immediately.
- [x] 6.4 Add in-flight state that shows progress and rejects further avatar actions until the request settles.
- [x] 6.5 Refresh the displayed profile from the response on success and report a confirmation distinct from the profile save confirmation.
- [x] 6.6 Keep the previous avatar displayed on failure and offer a retry entry point.
- [x] 6.7 Add a confirmation step before removal and offer the remove action only when an avatar exists.
- [x] 6.8 Route unauthenticated failures through the existing profile-screen handling.
- [x] 6.9 Add view model tests for delivered, cancelled, and failed picture outcomes, upload success and failure, duplicate-submission rejection, removal confirmation and decline, and repeated removal.

## 7. Localization

- [x] 7.1 Add strings for the avatar control, its choose, replace, and remove actions, and its unavailable state.
- [x] 7.2 Add strings for the editor controls, progress, and the size, format, and unreadable-source rejections.
- [x] 7.3 Add strings for upload and removal success, failure with retry, and the removal confirmation.
- [x] 7.4 Remove strings that only described the avatar address field, and update the profile editor subtitle that mentions an avatar picture link.
- [x] 7.5 Verify all new strings avoid technical terms and do not repeat their page title.

## 8. Verification

- [x] 8.1 Confirm the profile editor's save action no longer changes the stored avatar.
- [x] 8.2 Confirm an avatar previously set as an external address still displays and can be replaced and removed.
- [x] 8.3 Confirm a camera-sized photograph is selectable, editable, and produces an upload within the documented size limit.
- [x] 8.4 Confirm encoded output stays within the documented size limit on every supported platform.
- [x] 8.5 Confirm loading, empty, failure, unauthenticated, expired-session, and network-error states behave correctly on the profile editor.
- [x] 8.6 Run the relevant `core`, `proMain`, and app-level test and compile tasks from the repository root.
- [x] 8.7 Update `md_descriptions_paths.md` if any Markdown documents were added or changed.

### Verification notes (2026-08-12)

- `:core:jvmTest` and `:app:shared:jvmTest` pass, including image round trips, editor transforms, cancellation, responsive layout, dark mode, and a 4032×3024 source producing bounded 512×512 output.
- The four avatar-focused `:proMain:jvmTest` classes pass, covering the picker contract, transport, profile state, authentication failure, retry, removal, and UI behavior.
- `:app:androidApp:assembleDebug`, `:app:shared:compileKotlinJs`, and `:app:shared:compileKotlinWasmJs` pass.
- iOS compilation is disabled on this Linux host because the repository's existing native cinterops require Apple toolchains. iOS uses the same `skiaMain` encoder and common output-limit validation compiled by the JVM, JS, and WasmJs targets.
- The unfiltered `:proMain:jvmTest` run executes 240 tests and retains 9 pre-existing failures where dynamic Chinese `AppStrings` assertions are compared with English values captured by unrelated top-level constants. All avatar-focused tests pass independently; this change does not alter those unrelated localization constants.
