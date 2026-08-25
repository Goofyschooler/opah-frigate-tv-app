# Changelog

This page lists the user-visible changes in each Opah release.

## 0.4.0 - 2026-08-25

### A new living-room design

- Use a simpler Home, Activity, Clips, and Settings layout designed for a TV
  remote.
- Preview a camera in a large Home image before opening it.
- Pin favorites, reorder them, or hide cameras you do not need on Home.
- Choose whether Opah starts on Home, the last view, a camera, or a saved view.

### Live camera actions

- Go back 30 seconds and return to live video automatically.
- Take a snapshot of the current camera picture and share it from the TV.
- Start and stop an on-demand recording as a Frigate administrator on supported
  Frigate 0.18 servers.
- View the current Frigate 0.18 Mode and, as an administrator, switch it with
  confirmation and Undo.

### Faster activity review

- Review new activity as a continuous queue.
- Choose **Next activity** while a saved Activity video is playing to keep
  moving through the same list without leaving the player.
- Return from playback to the same Activity item, filters, and list position.
- Optionally mark an item reviewed when its video finishes and see when the
  review queue is caught up.
- Mark every new alert currently shown as reviewed after a warning.
- Move through saved video on a continuous timeline with recording, motion,
  detection, and alert markers.
- Switch cameras without losing the selected time and choose how much time the
  timeline shows.
- Find motion in a selected part of the picture on supported Frigate 0.18
  servers.
- Use recent searches, television voice input when available, and one-press
  suggestions such as Person, Package, Today, and Overnight.

### Clips and Incidents

- Browse kept video in the redesigned Clips page.
- Share clips from the TV. Frigate administrators can also rename or delete
  them.
- Organize clips into simple Incidents on supported Frigate 0.18 servers.
- Save the same moment from several authorized cameras with Save all angles.

### Personalization and updates

- Create a custom color style with simple television-remote controls.
- Turn on high contrast or reduce interface motion.
- Let Opah check quietly for releases after it starts, or turn automatic checks
  off.
- Read longer release notes directly on the Update page with a television
  remote.

Opah remains compatible with Frigate 0.17.2. Features that require Frigate
0.18 stay hidden on older servers.

## 0.3.0 - 2026-08-22

### Camera groups

- Watch two to four live cameras together.
- Save favorite camera groups on the device.
- Open camera groups already set up in Frigate.
- Use picture-in-picture with two cameras on compatible TVs.

### Activity

- Browse saved video by camera, day, and hour.
- See where more motion happened in saved video.
- See how much activity still needs review and review everything currently
  shown at once.
- See clearer activity details, including Frigate summaries, recognized names,
  and license plates when available.
- Find activity that looks similar to an item you are reviewing.
- Search saved activity using a short description when the feature is set up
  in Frigate 0.17.2 or 0.18.
- Filter activity by camera, object, recognized name, area, license plate,
  time, and review status.
- Mark activity as reviewed or not reviewed from its details or video.
- Save a selected part of History as a recording.

### Saved recordings

- Save a recording from activity details or video playback.
- Browse saved recordings with preview pictures and recording details.
- Play saved recordings from the new Saved page.
- Permanently delete a saved recording after a confirmation warning when
  signed in as a Frigate administrator.

### Camera controls

- Move, zoom, focus, and open saved positions on compatible cameras.

### Video display

- Fill the television screen with a camera picture and remember the choice for
  each camera.

### Updates

- See an indicator when a newer Opah release is available.
- Read the new release notes from the Update page.
- Download an official Opah update and open it with Android's installer.
- Download the newest Opah APK using the stable `opah-latest.apk` filename.

## 0.2.2 - 2026-08-20

### Easier setup and Review

- Improved first-time setup when using a television remote, keyboard, or mouse.
- Kept connection details in place after **Test connection**.
- Added a warning before leaving setup with unfinished changes.
- Added **Mark as reviewed** to Review details.
- Added a visible **Reviewed** label to reviewed items.

### Camera shortcuts

- Added a camera link that compatible button-mapping and home-automation apps
  can use to open a chosen live camera directly.

## 0.2.1 - 2026-08-20

### Maintenance update

- Updated the text and artwork shown in the TV launcher and while Opah starts.
- Added an About page with the app version, privacy information, license,
  project link, and support information.

### Important update note

- Android cannot install `0.2.1` over `0.2.0` because the releases use different
  security keys. Uninstall `0.2.0` first, then install `0.2.1` and connect to
  Frigate again. Later updates should install normally.

## 0.2.0 - 2026-08-19

### New features

- Home, Cameras, Birdseye, Review, Information, and Settings pages designed for
  a television remote
- Live playback for cameras using H.264 or H.265 video when supported by the TV
- Camera audio with mute and a video-only troubleshooting option
- A network compatibility option called **Force RTP over TCP**
- Picture-in-picture live video without audio on supported televisions
- Frigate alert and detection browsing with filters, details, and recordings
- Recorded-video controls with a timeline and easy seeking
- Frigate storage and performance information
- Securely saved connection details and automatic sign-in
- Light, dark, system, and custom color themes
- Automatic recovery when a live stream is slow to start or stops updating

### Privacy and safety

- Saved passwords and sign-in information are encrypted on the TV.
- Opah refuses unexpected web redirects that could send sign-in information to
  the wrong server.
- Camera lists and results are limited to the cameras available to the signed-in
  Frigate account.
- Diagnostics leave out passwords, private addresses, camera images, and other
  sensitive details.

### Tested with

- Frigate 0.17.2
- The exact Frigate 0.18.0 beta 3 build `344efb6`
- The 2024 onn. 4K Pro with Android 14
- H.264 and H.265 live video, Birdseye, Review recordings, and
  picture-in-picture

### Known limitations

- Live video cannot be rewound.
- Audio travels from the camera to the TV only; two-way talk is not available.
- Video support depends on the formats supported by the TV and camera.
- Later Frigate 0.18 builds are not automatically covered by the beta 3 test.
