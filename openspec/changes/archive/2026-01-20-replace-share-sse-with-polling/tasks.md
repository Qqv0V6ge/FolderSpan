## 1. Implementation
- [x] 1.1 Add share polling request/response Protobuf models
- [x] 1.2 Add `/api/share/heartbeat` endpoint to return share approval status
- [x] 1.3 Replace the share SSE client with a short polling loop
- [x] 1.4 Remove ShareSseRoutes and SSE plugin wiring/dependencies
- [x] 1.5 Update service/http AGENTS guidance with the polling approach

## 2. Validation
- [x] 2.1 Manually verify device share approval flow (wait → approve/reject) using polling
