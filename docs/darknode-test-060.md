# Opah Test on upstream 0.6.0

Upstream release and main both resolve to 1c96c13b350ebee93160c4409170cc4607738e6b
when checked on 2026-10-27. There is no newer upstream release to import.

The integration starts from HTTPS Lab d1243b0 (already based on upstream 0.6.0),
then restores the older Opah Test customizations, rather than downgrading the
newer transport and lifecycle code to the old fork's implementation.

## Retained fixes

- Authenticated same-origin HTTPS MSE playback for Fixed Monitor pins, bounded
  buffering, TLS/auth checks, cancellation, and late-connection protection.
- Exclusive shared video-output ownership; exact-generation release and
  bounded/cancellable acquisition; system Back and Show grid cleanup.
- Original playback failure, release trigger, initial cleanup outcome and
  duration, latest cleanup stage, and private-safe diagnostic codes.
- Opah Test label/package, persistent test signing key, and fork update feed.
  Feed selection applies only to darknodeTest; other variants keep upstream.

The retired progressive-MP4 endpoint is intentionally not restored: Frigate
0.18 does not expose that old endpoint. Snapshot pins are superseded by the
Fixed Monitor HTTPS pin implementation. Normal direct-camera playback remains
upstream RTSP, not HTTPS.

## Build and installation gate

Build variant: darknodeTest. Package: app.opah.tv.darknode.test. Version code:
6002, version name: 0.6.0-darknode-test. CI requires the existing test signing
secrets and does not fall back to a fresh debug key. CI runs both debug and
test-variant unit tests and lint, verifies APK identity/signature, and uploads
an artifact only. No release or automatic-update publication occurs.

Before replacing the installed test app, compare its signing certificate with
the artifact and verify its installed version code is lower. Never uninstall
an existing app or erase its data to bypass a mismatch. Official Opah and
HTTPS Lab have different package IDs and must remain unchanged.

## Acceptance remains blocked

The d1243b0 TV test reached Live HTTPS video on one camera, but another closed
its stream and later switching left a black screen that Home/resume did not
recover. Only the lab was force-stopped; its camera list returned. No crash
was recorded. Root causes have not yet been proven. Restoring older diagnostics
and updating Opah Test does not establish that these failures are fixed.

Repeat real-camera switching and background/resume, then ten-minute motion
and audio acceptance before upstream submission. Recover by force-stopping
only the affected test app. No Frigate restart or homelab configuration change
is part of this work. PRs 364 and 365 are untouched.
