# notification-route-registry Specification

## Purpose

Provide a stable, declared catalog of app screens that a server-authored notification action may target, together with the typed parameters each screen accepts, so that the app can resolve a route key into a real screen or degrade safely when it cannot, and so that the catalog can be exported for external consumers to offer a route picker over.

## ADDED Requirements

### Requirement: Declared route catalog

The app SHALL maintain a single declaration that maps a stable route key to a screen, its parameter schema, whether it requires an authenticated session, and human-readable names in each supported UI language. A route key SHALL be unique within the catalog and SHALL NOT be reused for a different destination once released.

#### Scenario: Registered route exposes its schema

- **WHEN** the catalog is inspected for a registered route key
- **THEN** it reports the route's parameter declarations, whether authentication is required, and its display names

#### Scenario: Route requiring a parameter

- **WHEN** a route accepts a parameter
- **THEN** the declaration states the parameter name, its type, and whether it is required

#### Scenario: Unregistered screen is not addressable

- **WHEN** a screen is not present in the catalog
- **THEN** no route key resolves to that screen

### Requirement: Route catalog export

The build SHALL be able to export the catalog to a machine-readable catalog file for consumption by other systems. The export SHALL contain, for every registered route, its route key, display names, group, authentication requirement, and parameter declarations. The export SHALL be deterministic: two exports of an unchanged catalog SHALL produce byte-identical output.

#### Scenario: Export lists every registered route

- **WHEN** the catalog is exported
- **THEN** the output contains one entry per registered route with its key, display names, group, authentication requirement and parameter declarations

#### Scenario: Repeated export is stable

- **WHEN** the catalog is exported twice without any change to the declaration
- **THEN** both outputs are byte-identical

#### Scenario: Export carries a format version

- **WHEN** the catalog is exported
- **THEN** the output declares the version of the catalog file format

### Requirement: Route catalog drift verification

The build SHALL fail when the committed catalog file does not match the catalog produced from the current declaration, so that adding, removing, or changing a route cannot silently diverge from the file other systems consume.

#### Scenario: Catalog file is current

- **WHEN** verification runs and the committed catalog file matches the declaration
- **THEN** verification succeeds

#### Scenario: Route added without regenerating

- **WHEN** a route is added to the declaration and the committed catalog file is not regenerated
- **THEN** verification fails and identifies the mismatch

#### Scenario: Route removed without regenerating

- **WHEN** a route is removed from the declaration and the committed catalog file is not regenerated
- **THEN** verification fails and identifies the mismatch

### Requirement: Route resolution and parameter validation

The app SHALL resolve a route key and supplied parameters into a screen only when the route key is registered, every required parameter is present, every supplied parameter name is declared for that route, and every supplied value parses as its declared type. Otherwise resolution SHALL fail without raising an error to the caller.

#### Scenario: Resolve a route with valid parameters

- **WHEN** a registered route key is supplied with parameters that satisfy its declaration
- **THEN** resolution produces the corresponding screen with those parameter values applied

#### Scenario: Resolve a parameterless route

- **WHEN** a registered route key that declares no parameters is supplied with no parameters
- **THEN** resolution produces the corresponding screen

#### Scenario: Unknown route key

- **WHEN** a route key that is not registered is supplied
- **THEN** resolution fails and no screen is produced

#### Scenario: Missing required parameter

- **WHEN** a registered route key is supplied without one of its required parameters
- **THEN** resolution fails and no screen is produced

#### Scenario: Undeclared parameter supplied

- **WHEN** a registered route key is supplied with a parameter name that its declaration does not include
- **THEN** resolution fails and no screen is produced

#### Scenario: Parameter value has the wrong type

- **WHEN** a registered route key is supplied with a value that does not parse as its declared type
- **THEN** resolution fails and no screen is produced
