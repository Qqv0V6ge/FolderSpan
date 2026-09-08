## 1. Implementation
- [x] 1.1 Add a Settings tray action that shows the app window and opens SettingsScreen

## 2. Validation
- [x] 2.1 Desktop: verify selecting Settings from the tray shows the app window and opens the Settings page

## 3. Tooling / Checks
- [x] 3.1 Run `openspec validate add-tray-open-settings --strict`
- [x] 3.2 Run `./gradlew :composeApp:compileKotlinJvm`
