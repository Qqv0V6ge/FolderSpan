## MODIFIED Requirements
### Requirement: Message routing for WebRTC exchange
The server SHALL route `connect-request`, `connect-approved`, `connect-rejected`, `offer`, `answer`, and `ice` messages to the `to.id` peer within the same room.

#### Scenario: Route connect request to peer
- **WHEN** a client sends a `connect-request` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route connect approval to peer
- **WHEN** a client sends `connect-approved` or `connect-rejected` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route offer to peer
- **WHEN** a client sends an `offer` with `to.id` in the same room
- **THEN** the server forwards the message to that peer

#### Scenario: Route to missing peer
- **WHEN** a client sends a `connect-request/connect-approved/connect-rejected/offer/answer/ice` to a non-member
- **THEN** the server responds with `error`
