# account-notification-actions Specification

## Purpose

Define how backend-authored action buttons attached to an account notification are presented and what happens when a user activates one, covering external links, in-app navigation with parameters, unauthenticated users, and destinations this app version does not recognize.

## ADDED Requirements

### Requirement: Account notification carries actions instead of a link

An account notification SHALL carry an ordered list of at most three actions, and SHALL NOT carry a single navigation link field. Each action SHALL declare a label, a style, and exactly one target: either an external URL or an in-app route key with optional parameters.

#### Scenario: Notification with actions

- **WHEN** the server returns an account notification carrying actions
- **THEN** the notification model exposes those actions in the order received

#### Scenario: Notification without actions

- **WHEN** the server returns an account notification with an empty or absent action list
- **THEN** the notification model exposes no actions

#### Scenario: No link field is consumed

- **WHEN** an account notification payload is decoded
- **THEN** no single navigation link field contributes to the notification model or to any navigation decision

### Requirement: Action button presentation

The notification detail view SHALL render one button per action, in the order supplied, using the label text exactly as supplied by the server. At most one action SHALL be presented with primary emphasis. A notification with no actions SHALL render no action area.

#### Scenario: Buttons follow supplied order

- **WHEN** a notification with several actions is opened in detail
- **THEN** the buttons appear in the same order as the actions

#### Scenario: Label rendered verbatim

- **WHEN** an action button is rendered
- **THEN** its label is the server-supplied text, unmodified and not substituted from app string catalogs

#### Scenario: Notification without actions shows no action area

- **WHEN** a notification with no actions is opened in detail
- **THEN** no action button area is shown

### Requirement: External link actions

Activating an action that targets an external URL SHALL open that URL outside the app. The app SHALL only open `http` and `https` URLs; an action carrying any other scheme SHALL be presented as unavailable.

#### Scenario: Open an HTTPS link

- **WHEN** the user activates an action targeting an `https` URL
- **THEN** the app opens that URL externally

#### Scenario: Reject a non-HTTP scheme

- **WHEN** an action targets a URL whose scheme is neither `http` nor `https`
- **THEN** the button is presented as unavailable and activating it opens nothing

### Requirement: In-app navigation actions

Activating an action that targets an in-app route SHALL open the screen the route resolves to, with the supplied parameters applied.

#### Scenario: Navigate to a screen with a parameter

- **WHEN** the user activates an action targeting a registered route with a valid parameter
- **THEN** the app opens the corresponding screen with that parameter applied

#### Scenario: Navigate to a parameterless screen

- **WHEN** the user activates an action targeting a registered route that declares no parameters
- **THEN** the app opens the corresponding screen

### Requirement: Unsupported destinations degrade visibly

An action whose route key is not recognized by this app version, or whose parameters do not satisfy the route's declaration, SHALL be presented as a visible but unavailable button explaining that this version does not support the destination. Such an action SHALL NOT be hidden, SHALL NOT navigate anywhere, and SHALL NOT interrupt rendering of the notification or of the other actions.

#### Scenario: Unknown route key

- **WHEN** a notification carries an action whose route key this version does not recognize
- **THEN** the button is shown as unavailable with an explanatory label and activating it navigates nowhere

#### Scenario: Parameters do not match the route

- **WHEN** a notification carries an action whose parameters do not satisfy the route's declaration
- **THEN** the button is shown as unavailable with an explanatory label

#### Scenario: One bad action does not affect the others

- **WHEN** a notification carries both a resolvable action and an unsupported one
- **THEN** the resolvable action remains fully usable and the notification renders normally

### Requirement: Sign-in for actions requiring a session

Activating an action whose destination requires an authenticated session while no session is active SHALL take the user to sign-in while retaining the destination, and on successful sign-in SHALL continue to that destination rather than to a default screen. Abandoning sign-in SHALL discard the retained destination.

#### Scenario: Sign in then reach the destination

- **WHEN** an unauthenticated user activates an action targeting a destination that requires a session
- **THEN** the app presents sign-in, and after successful sign-in opens the intended destination with its parameters applied

#### Scenario: Abandoned sign-in does not navigate

- **WHEN** the user leaves sign-in without completing it
- **THEN** the app does not open the intended destination and the retained destination is discarded

#### Scenario: Session present goes straight to the destination

- **WHEN** an authenticated user activates an action targeting a destination that requires a session
- **THEN** the app opens the destination without presenting sign-in

### Requirement: System notification activation

Activating an account notification from a system notification SHALL perform that notification's primary action when it has one, applying the same resolution, degradation, and sign-in rules as in-app activation. When the notification has no actions, activation SHALL open the notification center.

#### Scenario: System notification performs the primary action

- **WHEN** the user activates a system notification for an account notification whose primary action targets a registered route
- **THEN** the app opens the corresponding screen with the action's parameters applied

#### Scenario: System notification for an unsupported destination

- **WHEN** the user activates a system notification whose primary action targets an unrecognized route key
- **THEN** the app opens the notification center instead of navigating to an unknown destination

#### Scenario: System notification without actions

- **WHEN** the user activates a system notification for an account notification that carries no actions
- **THEN** the app opens the notification center
