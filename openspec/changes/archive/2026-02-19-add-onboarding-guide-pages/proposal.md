# Change: Add onboarding guide pages

## Why
The app has rich file management and cross-device transfer capabilities, but users do not have a single in-app guide that explains core concepts and entry points.
A dedicated onboarding guide is needed to explain `Local`/`Share`/`Network`, the `FileShareScreen` route, and the daily usage flow around `HomeScreen`, `AppDrawer`, and `FileScreen`.

## What Changes
- Add a new onboarding guide screen with exactly two pages.
- Page 1 introduces app purpose, file/folder transfer-management scenarios, and the `Local`/`Device`/`Share`/`Network` access models (`Disk.kt`, `Share.kt`, `Network.kt` plus device-connection flow).
- Page 1 also explains `FileShareScreen` route behavior and provides a direct route action.
- Page 2 introduces the usage flow of `HomeScreen`, `AppDrawer`, and `FileScreen`.
- Add a theme section that introduces Material theme colors using roles from `Theme.kt`/`MaterialTheme.colorScheme`.
- Auto-open the guide only on first app launch, without keeping a persistent guide entry in drawer/navigation.
- Make the guide layout responsive across all window classes (`Compact`/`Medium`/`Expanded`/`Large`/`ExtraLarge`) with distinct compositions.

## Impact
- Affected specs: `view-onboarding-guide` (new)
- Affected code:
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/navigator/HomeNavigator.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/main/HomeScreen.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/file/FileScreen.kt`
  - `composeApp/src/commonMain/kotlin/com/folderspan/ui/screen/file/share/FileShareScreen.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/data/main/Disk.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/data/main/share/Share.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/data/main/network/Network.kt`
  - `shared/src/commonMain/kotlin/com/folderspan/ui/theme/Theme.kt`
- Non-breaking: yes (additive UI/documentation flow)
