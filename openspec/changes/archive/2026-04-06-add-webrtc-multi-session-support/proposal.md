# Change: Add multiple WebRTC room entries in the drawer

## Why
The current WebRTC drawer and settings model only one `wssUrl + roomId` pair. After moving room editing out of the drawer, switching between signaling hosts or rooms now requires going back to Settings and manually rewriting the single saved value.

## What Changes
- Allow the existing WebRTC settings fields to hold multiple signaling servers or multiple room IDs using delimiter-separated values.
- Parse those saved values into multiple drawer room entries and let the user join, leave, or switch between them from the drawer.
- Expose the currently active room configuration from the WebRTC controller so the drawer can show which entry is active.
- Keep the signaling layer single-session: switching entries disconnects the current room before reconnecting to the selected entry.

## Impact
- Affected specs: `drawer-webrtc-server-connections`, `view-webrtc-settings`
- Affected code: `AppDrawerWebRtc.kt`, `WebRtcSettingsScreen.kt`, `MultiPeerWebRtcController.kt`, `DeviceState.kt`, shared WebRTC parsing helpers
