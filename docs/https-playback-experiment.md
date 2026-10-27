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
- The fork CI artifact is a debug-signed experiment, not an official update.
  Its package is `app.opah.tv.httpslab`, label **Opah HTTPS Lab**. CI checks
  package/label/signature before publishing the artifact. This installs next
  to official Opah and the older `.dev` fork, with separate settings. Do not
  uninstall either existing app. The lab needs its own Frigate login.
- Do not use the lab's in-app updater to obtain experimental builds. CI debug
  keys are ephemeral; replacing a later lab build may require uninstalling
  only the lab and entering its settings again.

## Required before submitting

1. Pass upstream's complete unit-test, lint, and debug-assembly command.
2. Pass the scripted session tests and loopback TLS/WebSocket tests for
   authentication cookies, denial, redirect refusal, negotiation, disconnect,
   stalled data, release races, and late fragments. These tests use synthetic
   certificates and bytes; they do not prove Media3 decoding of real video.
3. Pass the process output-slot ownership tests. HTTPS atomically reserves an
   empty registry slot before constructing its player. A pending RTSP
   registration cannot be overwritten. RTSP cannot claim the slot while HTTPS
   owns it. Closing cancels readers, conceals the exact output generation, and
   releases the decoder before freeing the slot. Failed cleanup keeps the slot
   occupied and requests an app restart; stale callbacks cannot release a new
   owner. The monitor waits at most three seconds for a previous owner, and
   cancellation/backgrounding prevents a queued start.
4. Verify real authenticated Frigate 0.18 playback on the Hisense: continuous
   motion and audio for at least 10 minutes, repeated pin/grid transitions,
   background/resume, network interruption, and permission revocation.
5. Confirm no credentials, private URLs, camera names/images, or raw responses
   enter shared test reports.

## Attended TV acceptance and recovery

Install only the verified lab APK after the build passes. Keep existing apps
installed. Open **Opah HTTPS Lab**, use the existing HTTPS Frigate address and
log in locally (never paste credentials into an issue). Select Monitor > Fixed
and a camera. Verify full-frame continuous motion and audio for ten minutes.
Back first hides controls; the next Back returns from the pin to the grid.
Repeat pin/grid, preset switches, Home/resume, and app exit/reopen ten times.
Briefly disconnect the TV network: playback should fail safely, then allow
Back/reselect after connectivity returns. Check the app's privacy mode/access
revocation path; no old frame or audio may remain visible/audible after access
is denied. The normal Home camera path is still RTSP, not the test path.

If cleanup cannot be proven, force-stop only the lab and reopen. To abandon
testing, exit/uninstall only **Opah HTTPS Lab** and open the previous app. No
server rollback, Frigate restart, port change, or homelab package is involved.

Current status: implementation candidate only; no live playback verification.
The loopback certificate helper is a test-only dependency; no new runtime
library is added. The workflow publishes only a debug test artifact, not a
GitHub release or an in-app update.
