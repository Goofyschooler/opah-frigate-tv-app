# Compatibility and troubleshooting

This page explains which Frigate and TV versions have been tested and what to
try when a camera does not play.

## Tested versions

| Item | Tested status |
| --- | --- |
| Frigate 0.17.2 | Fully tested stable version |
| Frigate 0.18.0 beta 3 (`344efb6`) | This exact beta build was tested |
| Android TV / Google TV | Android 7.0 or newer |
| Main test device | 2024 onn. 4K Pro with Android 14 |
| Additional test device | NVIDIA Shield TV with Android 11 |
| H.264 camera video | Tested |
| H.265 camera video | Tested on a TV that supports H.265 |
| Birdseye | Tested |
| Activity recordings | Browsing, filtering, reviewed status, seeking, saving, and playback tested |
| Saved recordings | Preview pictures, details, playback, and administrator deletion tested |
| Activity search | Available on Frigate 0.17.2 and 0.18 when search is turned on in Frigate |
| Activity summaries | Shown when Frigate has created a summary for that activity |
| Camera groups | Two and four cameras tested on the main device; results depend on the TV's video hardware |
| Picture-in-picture | Requires Android TV 14 or newer and support from the TV |
| Camera controls | Built for compatible Frigate PTZ cameras; physical-camera testing is still needed |

Newer Frigate releases may work before they are listed here, but they have not
completed the same checks yet. Future Opah updates will aim to support new
Frigate versions without breaking the versions already listed.

## Camera video formats

Camera settings often use names such as H.264, H.264+, H.265, H.265+, or Smart
Codec.

- **H.264** is the most widely supported choice and is a good first option.
- **H.265** can provide high-quality video with less network traffic, but the TV
  must support it.
- **H.264+**, **H.265+**, and **Smart Codec** are camera-maker features. Their
  behavior varies and they may delay or prevent playback on some devices.

If a camera stays on **Preparing** or does not start:

1. Turn on **Compatibility mode** under **Settings** > **Playback**.
2. Try the camera's lower-resolution stream.
3. Turn off Smart Codec, H.264+, or H.265+ in the camera settings.
4. Make sure the camera creates a full video frame regularly. Camera interfaces
   may call this the keyframe or I-frame interval.
5. Try H.264 to determine whether the problem is specific to H.265 support.

## Audio and live controls

Opah can play audio from the camera through the TV. It cannot send microphone
audio back to a camera or doorbell.

Pausing a live camera freezes the current picture. Opah does not record a
temporary copy of live video, so live rewind is not available.

The screen-size control can fit the whole camera picture inside the television
or fill the screen. The fill choice is saved separately for each camera.

## Activity search

Search appears only when Frigate reports that the feature is available. Search
must also be turned on and ready in Frigate. A short description such as
`red car` usually works better than a long sentence.

**Find similar activity** uses the same Frigate search feature. If Search is
unavailable, check the Frigate search setup before changing anything in Opah.

## Camera groups and controls

Camera groups use muted video and choose lower-bandwidth streams when they are
available. Two cameras may also use picture-in-picture on a supported Android
TV 14 device. If a group struggles to play, try fewer cameras or lower camera
resolutions.

Camera movement, zoom, focus, and saved positions appear only when both Frigate
and the camera report support. These controls have not yet been tested with a
physical PTZ camera, so support may vary.

## Saved recordings

Saved recordings stay on the Frigate server. If a saved recording does not
play, first confirm that the same saved recording opens in Frigate. Deleting a
saved recording requires a Frigate administrator account and cannot be undone.

## Network setup

In a typical setup, Opah connects to:

- the Frigate web service, usually on port 8971; and
- Frigate's live-video service, usually on port 8554.

Most users do not need to enter the second address separately. Advanced
connection settings are available when the secure Frigate web address and the
local live-video address are different.

Use `https://` for the Frigate server address when possible. Keep the live-video
service inside a trusted local network rather than exposing it to the internet.

## Report a compatibility problem

Please include:

- the Opah version;
- the exact Frigate version;
- the TV brand/model and Android version;
- the camera video format and resolution; and
- whether **Compatibility mode** or the lower-resolution stream works.

Do not post your password, server address, camera names or images, Frigate
configuration, or unedited logs. See [Contributing to Opah](../CONTRIBUTING.md)
for more guidance.
