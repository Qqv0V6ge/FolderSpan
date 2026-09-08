# Change: Update device heartbeat to polling

## Why
The current device heartbeat uses SSE, which we are removing. We still need a lightweight, authenticated heartbeat to validate tokens and update last-seen timestamps.

## What Changes
- Replace `/api/devices/heartbeat` SSE with an authenticated POST endpoint using protobuf request/response.
- Add protobuf models for heartbeat request/response.
- Update the device client heartbeat loop to use polling.

## Impact
- Affected specs: device-heartbeat (new)
- Affected code: DeviceRoutes, DeviceRouteClient, HttpRequests
