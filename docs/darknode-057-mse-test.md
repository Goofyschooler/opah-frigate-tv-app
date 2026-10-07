# Opah Test 0.5.7: Frigate 0.18 pinned playback

Experimental fork update. Not yet TV-verified.

Frigate 0.18 removed the generic `/api/go2rtc/api/stream.mp4` proxy used by
0.5.6. Its authenticated `/live/mse/api/ws` location remains and proxies
go2rtc's WebSocket. This build negotiates H.264/AAC MSE, then supplies the
binary fragmented-MP4 stream to the existing Media3 decoder/display path.
It is continuous video, not refreshed camera images. The grid remains unchanged.

No server, camera, port, firewall, auth or TLS setting is changed. Requests use
the configured HTTPS origin/base path and discovered stream name only, with
the existing authenticated client's cookie jar and certificate validation.
Redirects remain disabled. No RTSP URL, new dependency or browser is introduced.
HTTP handshake failures expose only the status number. Remote text errors and
private URLs are never shown. The direct-camera RTSP route is unchanged.

The bridge has an 8 MiB application buffer, a 20-second read deadline, no
automatic seek/retry, and cancellation before player release to wake blocked
reads. Oversized/overflowing fragments fail instead of corrupting MP4 bytes.
The pre-existing authorization polling and first-frame timeout remain active.
H.265-only streams are not advertised as supported by this H.264-focused test.

Verification: CI must run unit tests, lint and signed APK assembly with the
existing persistent fork signing identity. Added tests cover ordered fragments,
partial reads, buffer reuse/overflow, cancellation, deadlines, safe diagnostics,
and updated URL construction/security boundaries. These are not a live-server
or physical-TV test.

On TV: update using Settings > Update, then Monitor Mode > Fixed > select Cam 1.
Check continuous motion and audio for at least 60 seconds, full image fit,
Back to grid and reopen, background/foreground, and a second camera if it has
a discovered compatible stream. Report only displayed status, not credentials.

Sources:
- https://github.com/blakeblackshear/frigate/blob/v0.18.0/docker/main/rootfs/usr/local/nginx/conf/nginx.conf
- https://github.com/AlexxIT/go2rtc/blob/master/www/video-rtc.js (MSE negotiation and binary fragments)
