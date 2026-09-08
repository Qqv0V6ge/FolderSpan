## Context

FolderSpan is a Kotlin Multiplatform application whose user-facing text is spread across shared Compose code, `core`, `proMain`, desktop tray integration, Android resources and notifications, iOS native extension code, and browser sharing templates and scripts. Most text is currently embedded Simplified Chinese. The shared module already applies Libres but has no string catalog, while Compose Multiplatform Resources does not provide the public runtime locale override needed by the application language selector.

The change must preserve existing task history and serialized data, work on every supported target, and leave Traditional Chinese unmatched until translations are supplied.

## Goals / Non-Goals

**Goals:**

- Provide complete English and Simplified Chinese coverage for user-visible application text.
- Let users select System, Simplified Chinese, or English and persist that choice per device.
- Resolve locales consistently, with English as the base and unmatched-language fallback.
- Localize persisted task, retry, and notification messages without invalidating existing data.
- Keep the resource and language model ready for a later Traditional Chinese catalog.

**Non-Goals:**

- Supplying Traditional Chinese translations in this change.
- Translating logs, protocol payloads, filenames, remote server error bodies, or developer diagnostics.
- Synchronizing the language preference through the application's settings-sync feature.
- Changing domain behavior unrelated to how user-facing text is selected or rendered.

## Decisions

### Centralize the shared catalog in `core`

Apply Libres in `core` and generate a single `AppStrings` catalog with English as `baseLocaleLanguageCode`. Store English and Simplified Chinese resources with identical keys; use Libres language code `zhHans` internally because generated identifiers do not safely support a hyphenated filename suffix. Remove the empty shared-module Libres setup if it has no remaining resource role.

All Kotlin modules consume the catalog through imports or small typed formatting helpers instead of defining independent copies. Stable string keys use semantic names rather than source-language text. Resource-key parity and accidental user-visible CJK literals are checked statically.

Libres is preferred over Compose Multiplatform Resources for strings because it already exists in the project and exposes a runtime language override. Platform-native resources remain native where the operating system, manifest, extension, shortcut, or notification integration requires them.

### Represent preference and resolved language separately

Add the following shared contracts:

- `AppLanguageMode`: `System`, `English`, and `SimplifiedChinese`.
- `ResolvedAppLanguage`: `English` and `SimplifiedChinese`.
- `AppLanguageController`: reads the platform locale, resolves a mode, applies it to the shared catalog and platform adapter, and exposes observable resolved-language state.

Persist `AppLanguageMode` under `settings.appearance.language`. The setting is device-local and excluded from settings-sync allowlists. A missing or invalid value becomes `System`.

Resolution is deterministic:

- An explicit English or Simplified Chinese selection always wins.
- In System mode, `zh`, `zh-Hans`, `zh-CN`, and `zh-SG`, including case-equivalent tags, resolve to Simplified Chinese.
- `zh-Hant`, `zh-TW`, `zh-HK`, `zh-MO`, and every other unmatched locale resolve to English.
- Libres receives `en` or `zhHans`; the persisted and platform-facing tags remain standard `en` and `zh-Hans`.

The root application observes the resolved language and forces the required recomposition. Android handles locale configuration changes in place to avoid an unnecessary activity rebuild, while settings navigation and list identity use stable IDs rather than translated titles.

### Add a dedicated language settings screen

Place a Language entry after Appearance in Settings. The Language screen presents radio selections for System, Simplified Chinese, and English, indicates the active choice, persists changes immediately, and applies the language without requiring an application restart. The language names remain recognizable in both catalogs.

### Adapt each platform's native surfaces

- Android keeps the entry activity on `ComponentActivity`, declares `en` and `zh-Hans` application locales, reads the unmodified system locale through `LocaleManagerCompat`, and uses the framework `LocaleManager` for application locales on Android 13 and newer. The activity handles `locale` and `layoutDirection` configuration changes in place and resynchronizes the shared controller from `onConfigurationChanged`, avoiding the visible window teardown caused by activity recreation. An empty application locale list represents System. Android 12 and older keep using the Libres runtime override and the app's persisted language mode. Default Android resources are English and `values-b+zh+Hans` supplies Simplified Chinese. In-app changes and Android's external per-app language setting are synchronized where the platform setting is available.
- Desktop rebuilds Compose content and tray labels/actions when the resolved language changes.
- iOS uses the shared catalog for Compose UI and `en.lproj`/`zh-Hans.lproj` for native strings. The app writes the selected mode to its App Group so the Share Extension resolves the same preference on its next presentation. Static localized shortcut text is replaced by a dynamic shortcut so an explicit app language can override the device language.
- The Web application follows the selected application mode. The standalone browser sharing page is not tied to the host application's stored preference; it resolves the visitor's `Accept-Language`/`navigator.languages` using the same matching and English fallback rules. HTML, JavaScript, PowerShell, shell, and batch user-facing output use the resulting catalog.

### Persist message identity instead of rendered language

Introduce `LocalizedMessage(key, args, fallbackDetail)` for task state, retry history, and notification content. Known application messages store a stable key and structured arguments, then render using the current language. Unknown remote or platform errors retain a technical `fallbackDetail` and localize only their surrounding label.

Add optional message fields to existing Proto models using new field numbers; do not rename or renumber existing fields. Readers prefer the new keyed form, lazily map recognized legacy Chinese messages to keys, and otherwise display the legacy value. Writers emit the keyed form while retaining compatibility for older readers where the current schema requires a legacy text field.

## Risks / Trade-offs

- [Large text migration misses a surface] → Inventory user-visible literals by module, require English/Chinese key parity, and run static scans plus platform smoke tests.
- [A global catalog override does not trigger UI updates] → Publish the resolved-language state before applying platform locale changes, then explicitly recompose the platform root and tray.
- [Native surfaces cache locale state] → Handle Android locale configuration callbacks without recreating the activity, use framework and compatibility locale APIs from the platform adapter, rebuild desktop tray state, and document that an already-open iOS extension updates on its next presentation.
- [Serialized-message migration changes old history] → Add only optional fields, keep legacy read paths, and test old, mixed, and new payloads.
- [English fallback hides a missing Simplified Chinese key] → Make English the intentional base fallback while failing key-parity checks for committed catalogs.
- [Technical errors remain untranslated] → Preserve raw external detail for diagnosis while localizing the application's contextual message.

## Migration Plan

1. Add the central catalogs, language contracts, persistence, and locale resolver before replacing call sites.
2. Add the Settings entry and runtime application adapters, then migrate shared Compose and Pro UI in coherent screen groups.
3. Migrate task and notification producers/readers with backward-compatible Proto fields.
4. Localize desktop, Android, iOS, Web, and standalone browser-sharing surfaces.
5. Run parity, static-literal, serialization, UI, and platform build tests before enabling the setting in release builds.

Rollback can disable the Settings entry and restore System mode while leaving additive resource catalogs and optional serialized fields in place. Legacy fields remain readable throughout.

## Open Questions

None. Traditional Chinese resource content and whether it receives a separate explicit selector entry will be specified when those translations are provided.
