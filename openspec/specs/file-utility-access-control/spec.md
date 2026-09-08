# file-utility-access-control Specification

## Purpose

Defines an explicit, fail-closed permission boundary for common filesystem utilities and a maintained inventory of sensitive FolderSpan storage locations.

## Requirements

### Requirement: Filesystem utility calls require an explicit permission decision
Every operation that inspects or mutates a filesystem object through the common file or path utilities SHALL require a trusted internal caller to provide either `Allowed` or `Denied`. The permission SHALL have no implicit default and SHALL NOT be accepted from an untrusted network or MCP request payload.

#### Scenario: Trusted local operation is allowed
- **WHEN** a trusted local workflow invokes a filesystem operation with `Allowed`
- **THEN** the operation preserves its existing filesystem behavior and result shape

#### Scenario: Caller omits the permission decision
- **WHEN** code attempts to call a filesystem-touching utility without a permission value
- **THEN** the call does not compile

### Requirement: Denied access fails before filesystem observation or mutation
A filesystem operation invoked with `Denied` MUST fail before path probing, existence checks, directory enumeration, metadata access, content access, logging of the target path, task creation, or mutation. Result-bearing APIs SHALL report an authority failure, stream APIs SHALL emit one authority failure and terminate, boolean probes SHALL return false, and other APIs SHALL throw an authority failure.

#### Scenario: Denied directory listing
- **WHEN** a caller requests a directory listing with `Denied`
- **THEN** the entire listing fails without returning child names or metadata
- **AND** the filesystem is not enumerated

#### Scenario: Denied mutation
- **WHEN** a caller requests create, write, append, rename, or delete with `Denied`
- **THEN** the operation reports an authority failure
- **AND** no filesystem object is created, changed, renamed, or removed

#### Scenario: Denied existence probe
- **WHEN** a caller probes a path with `Denied`
- **THEN** the result is false regardless of whether the path exists
- **AND** the target path is not logged

### Requirement: External access combines authorization with sensitive-path classification
Device HTTP/WebRTC and MCP services SHALL authenticate first, validate their existing scope or role/path permissions, classify the requested path without filesystem probing, and invoke common filesystem utilities with `Allowed` only for an authorized non-sensitive path. A protected-path classification MUST produce `Denied` and MUST NOT be overridden by authentication, device approval, role permissions, or MCP scopes.

#### Scenario: Valid device token requests an ordinary file
- **WHEN** a device presents a valid token with read permission for a non-sensitive path
- **THEN** the request proceeds with the existing filesystem behavior

#### Scenario: Valid device token requests a protected file
- **WHEN** a device presents a valid token and role permission for a protected path
- **THEN** the request fails with an authority error before protected-path probing or mutation

### Requirement: Sensitive paths have stable machine-readable labels
The sensitive storage inventory SHALL be backed by a cross-platform path registry. A classification SHALL contain a sensitivity level (`none`, `sensitive`, or `critical`) and a stable category identifier that does not disclose credentials, file content, or an otherwise unknown target path. Platform implementations SHALL register their application-private roots and common code SHALL register maintained runtime, staging, editor, and recovery locations.

#### Scenario: Authorized parent contains protected storage
- **WHEN** an external listing has already been authorized for a parent containing a registered protected child
- **THEN** the child can be labeled without treating that label as permission to inspect or mutate it

#### Scenario: Malformed external path cannot be classified
- **WHEN** an external path is relative, escapes above its root, or cannot be normalized for the platform
- **THEN** classification fails closed and no filesystem probe occurs

### Requirement: Sensitive storage inventory is maintained
The repository SHALL maintain a document that identifies sensitive and important files and directories, their platform locations or storage providers, contained data, compromise impact, and ownership rules. The inventory MUST include databases and sidecars, settings stores, TLS identity material, application data stores, editor recovery and content caches, task and transfer state, staging data, and device request logs without including real credentials or user data.

#### Scenario: New sensitive persistent storage is introduced
- **WHEN** a change adds a persistent file, directory, or platform-managed store containing credentials, identity material, user content, or operational recovery state
- **THEN** the sensitive storage inventory and Markdown index are updated in the same change
