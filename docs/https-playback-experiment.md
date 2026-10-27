# HTTPS playback experiment — not upstream-ready

Based on upstream 0.6.0 (1c96c13). This branch carries only the authenticated
MSE transport and fixed Monitor camera experiment, not the old fork branding,
update feed, signing configuration, or RTSP diagnostic changes.

Frigate 0.18 exposes `/live/mse/api/ws` behind authentication. The older
`/api/go2rtc/api/stream.mp4` experiment is not used. HTTP redirects and TLS
downgrades remain disabled, discovered streams are selected on the configured
origin, and access is rechecked while playing. No server changes are required.

The application byte pipe now treats an idle timeout as terminal and the
data source cancels the socket when its reader fails. This prevents accepting
late fragments after a timeout. WebSocket ping/pong detects a dead connection.

## Scope and limitations

- Fixed Monitor: selecting a camera pins it until Back or a preset change.
- Normal direct-camera playback remains RTSP. Do not claim issue #12 resolved.
- No automatic reconnect; explicit Back/reselection is required after failure.
- Only H.264/AAC is requested. Codec negotiation needs device verification.
- Existing default first-stream selection is not a compatibility test.
- No production, TV, Frigate, or homelab PR #364/#365 changes.
- The fork CI artifact is an unsigned-for-release debug experiment, not an
  official app update. Do not install over a working app without checking its
  application identity and signature first.

## Required before submitting

1. Pass upstream's complete unit-test, lint, and debug-assembly command.
2. Pass the scripted session tests and loopback TLS/WebSocket tests for
   authentication cookies, denial, redirect refusal, negotiation, disconnect,
   stalled data, release races, and late fragments. These tests use synthetic
   certificates and bytes; they do not prove Media3 decoding of real video.
3. Check ownership against the process-wide RTSP player coordinator; do not
   allow competing players after a preset change.
4. Verify real authenticated Frigate 0.18 playback on the Hisense: continuous
   motion and audio for at least 10 minutes, repeated pin/grid transitions,
   background/resume, network interruption, and permission revocation.
5. Confirm no credentials, private URLs, camera names/images, or raw responses
   enter shared test reports.

Current status: implementation candidate only; no live playback verification.
The loopback certificate helper is a test-only dependency; no new runtime
library is added. The workflow publishes only a debug test artifact, not a
GitHub release or an in-app update.
