## 1. Localization Foundation

- [x] 1.1 Configure Libres in `core` to generate the English-base `AppStrings` catalog, add matching English and `zhHans` resource files, and remove the unused shared-module string generator configuration.
- [x] 1.2 Add typed string-formatting helpers and catalog validation that fails when English and Simplified Chinese keys or argument shapes differ.
- [x] 1.3 Implement `AppLanguageMode`, `ResolvedAppLanguage`, locale-tag normalization, and the shared resolver with English fallback.
- [x] 1.4 Implement `AppLanguageController`, observable resolved-language state, Libres runtime override mapping, and application-root recomposition hooks.
- [x] 1.5 Persist `settings.appearance.language` per device with `System` as the invalid/missing-value fallback and exclude the key from settings synchronization.
- [x] 1.6 Add resolver, persistence, invalid-value, explicit-precedence, and settings-sync-exclusion unit tests.

## 2. Language Settings Experience

- [x] 2.1 Extend Settings state and actions with the language mode and add a stable-ID Language entry immediately after Appearance.
- [x] 2.2 Implement the Language settings screen with System, Simplified Chinese, and English radio choices and immediate application.
- [x] 2.3 Add widget tests for the selected indicator, each language transition, persistence callbacks, and stable navigation after translated titles change.

## 3. Shared and Pro Text Migration

- [x] 3.1 Migrate shared application shell, navigation, reusable components, dialogs, crash UI, and Settings screens to semantic `AppStrings` keys.
- [x] 3.2 Migrate file browsing, search, editor, viewer, media, clipboard, favorites, permission, and file-operation surfaces.
- [x] 3.3 Migrate device, sharing, network-drive, WebRTC, synchronization, transfer, task-history, and related status surfaces.
- [x] 3.4 Migrate application-owned user-facing text in `core` and `proMain` while leaving logs, protocol values, filenames, and raw external errors unchanged.
- [x] 3.5 Add English translations and preserve reviewed Simplified Chinese values for every migrated key, including plural, formatted, and accessibility text.
- [x] 3.6 Add a static user-visible-literal audit and resolve all in-scope hardcoded Simplified Chinese findings or explicitly classify them as diagnostics/external data.

## 4. Persisted Task and Notification Messages

- [x] 4.1 Add `LocalizedMessage` with stable key, structured arguments, and optional fallback detail, plus rendering in the current application language.
- [x] 4.2 Extend task, retry-entry, and notification Proto models with new optional localization fields without renaming or renumbering existing fields.
- [x] 4.3 Update application-owned task and retry producers to emit localized message identity and update readers to prefer it.
- [x] 4.4 Update notification producers and platform presentation adapters to render localized titles, bodies, actions, and contextual error labels.
- [x] 4.5 Implement lazy mapping for recognized legacy Chinese messages and retain original unknown legacy or external text as fallback detail.
- [x] 4.6 Add serialization tests for legacy-only, keyed-only, mixed-version, formatted-argument, recognized-migration, and unknown-fallback payloads.

## 5. Platform Locale Integration

- [x] 5.1 Keep Android startup on `ComponentActivity`, read the true system locale through `LocaleManagerCompat`, use framework per-app locales on Android 13 and newer, handle `locale`/`layoutDirection` changes in place without recreating the activity, retain Libres-managed language selection on older Android versions, declare `en` and `zh-Hans`, synchronize external Android language changes, and make default native resources English with `values-b+zh+Hans` overrides.
- [x] 5.2 Localize Android manifest labels, shortcuts, notifications, services, permissions guidance, and other native resources, including System mode represented by an empty application locale list.
- [x] 5.3 Rebuild Desktop tray labels, menus, notifications, and actions when resolved language changes.
- [x] 5.4 Add iOS `en.lproj` and `zh-Hans.lproj` native resources, persist the language mode in the App Group, localize Share Extension UI, and replace static shortcut text with language-aware dynamic shortcuts.
- [x] 5.5 Apply the selected language to the Web application and verify reactive recomposition after a settings change.
- [x] 5.6 Localize standalone browser-sharing HTML, JavaScript, shell, PowerShell, and batch output; resolve visitor language from `Accept-Language`/`navigator.languages` independently of the host preference.

## 6. Verification

- [x] 6.1 Add automated resource-key parity, format-argument parity, locale-resolution, and browser-language-resolution tests, including Traditional Chinese and unrelated-language English fallbacks.
- [x] 6.2 Run `:core:jvmTest` and `:app:shared:jvmTest`, fixing localization, state, widget, and compatibility regressions.
- [x] 6.3 Build Android debug, Desktop JVM, and both Web targets to verify generated resources and platform adapters compile.
- [x] 6.4 Verify iOS main-app and Share Extension resources, App Group preference behavior, dynamic shortcuts, and next-presentation language updates through the available Xcode workflow.
- [x] 6.5 Complete a cross-platform acceptance pass for all three settings choices, restart persistence, external Android language changes, legacy task history, notifications, and browser sharing fallback behavior.
