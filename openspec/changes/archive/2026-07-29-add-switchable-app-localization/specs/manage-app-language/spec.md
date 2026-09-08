## ADDED Requirements

### Requirement: Application language choices
The application SHALL provide a Language settings screen with System, Simplified Chinese, and English choices, and SHALL persist the selected choice on the current device.

#### Scenario: Default language choice
- **WHEN** no valid language preference has been stored
- **THEN** the application selects System

#### Scenario: Select an explicit language
- **WHEN** the user selects Simplified Chinese or English
- **THEN** the application persists that choice and applies it without requiring an application restart

#### Scenario: Language preference remains device-local
- **WHEN** application settings are synchronized to another device
- **THEN** the language preference is excluded from the synchronized settings

### Requirement: System locale resolution
In System mode, the application SHALL resolve `zh`, `zh-Hans`, `zh-CN`, and `zh-SG` to Simplified Chinese and SHALL resolve every other unmatched locale to English.

#### Scenario: Simplified Chinese system locale
- **WHEN** the system's preferred locale is a case-equivalent form of `zh`, `zh-Hans`, `zh-CN`, or `zh-SG`
- **THEN** the application resolves Simplified Chinese

#### Scenario: Traditional Chinese system locale
- **WHEN** the system's preferred locale is `zh-Hant`, `zh-TW`, `zh-HK`, or `zh-MO`
- **THEN** the application resolves English while no Traditional Chinese catalog is available

#### Scenario: Other system locale
- **WHEN** the system's preferred locale does not match a supported application language
- **THEN** the application resolves English

### Requirement: Explicit selection precedence
An explicit application language selection SHALL take precedence over the system locale until the user selects System again.

#### Scenario: English on a Simplified Chinese device
- **WHEN** the system locale resolves to Simplified Chinese and the user selects English
- **THEN** the application displays English

#### Scenario: Return to System
- **WHEN** the user changes an explicit language selection to System
- **THEN** the application immediately resolves and applies the current system locale

### Requirement: Runtime language application
The application SHALL apply a changed language to active shared UI and platform-owned user-facing surfaces using each platform's supported lifecycle.

#### Scenario: Shared UI language changes
- **WHEN** the resolved application language changes
- **THEN** visible Compose content recomposes or safely rebuilds with strings from the new language catalog

#### Scenario: Platform surface language changes
- **WHEN** the resolved application language changes
- **THEN** Android native resources, desktop tray content, Web application content, and subsequently presented iOS native extension content use the new language

#### Scenario: Stable settings navigation
- **WHEN** translated Settings titles change after a language switch
- **THEN** navigation and list identity remain valid because they do not depend on translated text

### Requirement: Complete localized surface coverage
The application SHALL provide matching English and Simplified Chinese resources for all application-owned user-visible text across shared UI, Pro UI, task and notification messages, desktop tray UI, Android and iOS native surfaces, and browser sharing pages.

#### Scenario: Catalog key parity
- **WHEN** localization resources are validated
- **THEN** every committed Simplified Chinese key has an English base value and every required English key has a Simplified Chinese value

#### Scenario: External technical detail
- **WHEN** a remote service or platform returns an unknown error
- **THEN** the application localizes its contextual label and preserves the original technical detail

### Requirement: Localized persisted messages
Application-owned task, retry, and notification messages SHALL persist a stable message key and structured arguments instead of depending solely on text rendered in one language.

#### Scenario: Render a new keyed message
- **WHEN** a keyed task, retry, or notification message is displayed
- **THEN** the application renders the message using the current application language and its stored arguments

#### Scenario: Read recognized legacy text
- **WHEN** a stored legacy Chinese message matches a known application message
- **THEN** the application maps it to the corresponding localized message without losing its arguments

#### Scenario: Read unknown legacy text
- **WHEN** a stored legacy message cannot be mapped safely
- **THEN** the application displays the original text as fallback detail

#### Scenario: Read an older serialized payload
- **WHEN** a task, retry, or notification payload does not contain the new optional localization fields
- **THEN** the application reads it successfully using the legacy fields

### Requirement: Browser sharing page language
The standalone browser sharing page SHALL resolve language from the visitor's browser preferences independently of the host application's stored language choice.

#### Scenario: Simplified Chinese browser
- **WHEN** `Accept-Language` or `navigator.languages` resolves to a supported Simplified Chinese locale
- **THEN** the sharing page and its user-facing script output use Simplified Chinese

#### Scenario: Unmatched browser language
- **WHEN** the visitor's browser preferences do not resolve to Simplified Chinese
- **THEN** the sharing page and its user-facing script output use English
