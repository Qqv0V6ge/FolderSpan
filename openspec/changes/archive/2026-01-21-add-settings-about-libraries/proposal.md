# Change: Add About Libraries settings page

## Why
Users need a clear, in-app place to review third-party libraries and licenses.

## What Changes
- Add an "About Libraries" entry in Settings that opens a new page.
- Render the page using AboutLibraries Compose M3 components.
- Add the AboutLibraries dependency and required build configuration.

## Impact
- Affected specs: view-about-libraries-settings (new)
- Affected code: Settings screen/navigation, About Libraries UI, build configuration
