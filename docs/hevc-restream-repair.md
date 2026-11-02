# Opah Test HEVC repair

Proposed for review; no TV installation or server configuration change is part of this branch build.

The Hisense accepted Baby Room HTTPS playback on version 6002. Downstairs and
Front Door failed. Read-only Frigate inspection showed only Baby Room under
go2rtc streams; Downstairs is H.264/G.711 and Front Door is 4K HEVC/AAC.
The old MSE request/response whitelist also excluded HEVC. These are two
independent prerequisites; the app patch alone cannot repair missing streams.

Version 6003 retains the test application ID and signer. A decoder capability
query permits Main-profile HEVC through level 5.1 only when the platform reports
a decoder and 3840x2160 support. Other devices continue requesting AVC/AAC.
Negotiation still rejects server errors, unknown codecs, Main10, high tier,
above-advertised levels, audio-only media and multiple video tracks. Auth,
TLS checks, redirect refusal, bounded buffers and output ownership are unchanged.

This is a conservative capability check, not proof of smooth decoding. The
Hisense must still pass actual Front Door playback, repeated camera switching,
background/resume and a ten-minute soak after the separate Frigate repair.
No software video transcoding or public RTSP port is proposed.

CI tests debug and darknodeTest, lints both, builds version 6003 and verifies its
signature. It also builds source 4321a3d2dbb566845e6fb23d78a9b342c86d2255 as
version 6004 with the same signer for forward-version rollback. That restores
the previous application behavior using `adb install -r`, preserving saved
login/data; it does not restore server configuration. No uninstall or data clear.
After rollback a future repair must use a version greater than 6004.

The homelab repair PR owns the server backup/restart/rollback and attended
acceptance checklist. PRs 364 and 365 are excluded. No upstream submission or
release publication is authorized by preparing these artifacts.
