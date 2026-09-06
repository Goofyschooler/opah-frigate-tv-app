# Opah

Opah is an Android TV and Google TV app for viewing cameras and recordings from
an existing [Frigate NVR](https://frigate.video/). Designed
for a television remote, it lets you watch live cameras, use multi-camera views,
review recent activity, search saved video, browse history, and keep or share
important clips. Opah can also show calm TV alerts, watch a camera view for
important activity, and summarize what happened since you last watched.

The vision for Opah is to provide Frigate users with an intuitive, simple,
NVR-like television experience. Instead of presenting Frigate as a technical
web interface on a TV, Opah organizes its most useful everyday features into
clear, approachable screens that are comfortable to navigate from the sofa.

Opah connects directly from your TV to your Frigate server and does not operate
a separate camera cloud or recording service. It is an independent community
project and is not affiliated with, endorsed by, or supported by Frigate, Inc.

## Screenshots

| Home | Live camera |
| --- | --- |
| <img src="docs/screenshots/04-home.png" alt="Opah Home with example camera previews" width="720"> | <img src="docs/screenshots/10-live-playback.png" alt="Opah live camera controls" width="720"> |

| Recent activity | Activity history |
| --- | --- |
| <img src="docs/screenshots/08-review.png" alt="Opah recent activity with example alerts" width="720"> | <img src="docs/screenshots/19-activity-history.png" alt="Opah History with example times and motion levels" width="720"> |

See the [complete interface gallery](docs/screenshots.md). All camera scenes,
names, addresses, and data shown in the gallery are fictional examples.

## What you need

- A working Frigate server
- Android TV or Google TV running Android 7.0 or newer
- A Frigate username and password
- Network access from the TV to the Frigate server

Opah supports Frigate 0.17.2. Features that need Frigate 0.18 target the exact
Frigate 0.18.0 RC1 build `a745070`; its authenticated read-only API and core
TV workflows have been tested. See the [compatibility guide](docs/compatibility.md)
for camera video formats, tested devices, and troubleshooting tips.

## Install Opah

Opah is currently downloaded from GitHub rather than Google Play.

1. Download [`opah-latest.apk`](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases/latest/download/opah-latest.apk)
   from the newest Opah release.
2. Send that file to the TV.
3. Follow the [step-by-step installation guide](docs/installation.md).

Only download Opah from this repository. Copies from other websites may have
been changed or may be unsafe.

## Connect for the first time

Opah asks for:

1. your Frigate server address;
2. your Frigate username; and
3. your Frigate password.

Use the same secure Frigate address you normally use in a web browser when
possible. Choose the separate **Live video address** option only when live
video must use a different local host or port. Opah keeps the normal Frigate
address as the recommended default.

After a successful sign-in, Opah can securely remember the connection and sign
in automatically. Choose **Sign out** to remove the saved sign-in information.

## Make Home yours and watch cameras together

Moving focus on Home previews that camera in the large picture. Press and hold
the remote's center button on a camera or view to pin it as a favorite, move a
favorite earlier or later, or hide a camera from Home. Choose **Restore
cameras** to bring hidden cameras back.

Open a camera or view from **Home**. To make a new multi-camera view, open the
camera catalog from Home, choose two to four cameras, and choose **Watch**.
Opah fills the screen with every camera picture.
On supported TVs, a two-camera group can also pop out over another app. Camera
names hide automatically while you watch.

Choose **Save view** to name a group you use often. Saved views stay on that
device. Camera groups already set up in Frigate also appear here. If a group has
more than four cameras, Opah asks you which cameras to watch.

## Find and review activity

Open **Activity** to see alerts and other recorded activity:

- **Recent** shows new items and which ones still need review.
- **History** lets you move continuously through saved video with recording,
  motion, detection, and alert markers.
- **Search** finds saved activity from a short description when search is set
  up in Frigate.
- **Motion** appears on supported Frigate 0.18 servers and finds movement in a
  selected part of the picture.

Open an item to watch it, mark it reviewed or not reviewed, save its recording,
or find activity that looks similar. **Recent** > **Alerts** can also mark all
alerts currently shown as reviewed after a warning. Frigate may provide a short
summary, a recognized name, or a license plate.

While a saved Activity video is playing, choose **Next activity** to continue
through the same list without returning to the grid. At the end, the control
shows **Caught up** for a review queue or **No next activity** for another list.
Pressing Back returns to the original item, filters, and position in the
Activity list.

## Stay aware without watching constantly

Opah 0.5 adds three optional ways to keep up with activity:

- **TV alerts** can notify you about important Frigate activity while another
  TV app is open. You choose the cameras, kinds of activity, quiet times, and
  how much detail a notification may show.
- **Monitor Mode** keeps a saved View or Frigate camera group on screen and can
  bring one active camera forward when something important happens. Audio is
  off unless you turn it on.
- **Since you last watched** adds a short factual briefing to Home when there
  is unseen activity. You can play highlights, open a Review queue, or dismiss
  only the items currently shown.

Shared TVs can use a local PIN to protect selected app areas, administrative
actions, destructive actions, and private cameras. For the strongest
protection, also use a restricted Frigate account for the TV.

## Save important recordings

Choose **Keep clip** while reviewing recorded activity. You can also keep a
selected part of **History**. Open **Clips** to see preview pictures, play a
clip, or share a local copy. Frigate administrators can also rename a clip or
delete it after a confirmation warning.

With a supported Frigate 0.18 server, **Save all angles** keeps the same moment
from several authorized cameras. Frigate administrators can group clips into
simple **Incidents**.

Clips remain on the Frigate server. They are not copied into a
separate Opah cloud service or stored as a second recording archive on the TV.

## Control and size cameras

If Frigate reports compatible camera controls, a **Controls** choice appears
for that camera. In control mode, the D-pad moves the camera, center stops it,
and a compact shelf contains supported zoom, focus, and saved positions.

While watching a camera or Birdseye, use the screen-size control to choose
whether the whole picture fits on the television or fills the screen. Opah
remembers that choice for each camera and for Birdseye on the device. Choose
**Video only** if an audio track prevents a camera from playing.

## Get updates

Opah normally checks the official GitHub release page shortly after it starts.
A small dot inside the Settings gear means an update is available. Open
**Settings** > **Update** to read the release notes, download the verified APK,
and open Android's installer. You can turn automatic checks off on the same
page. Opah never downloads or installs an update without your action.

## Optional camera shortcuts

Some Android TV button-mapping and home-automation apps can send Android
commands. They can use this command to open a chosen camera directly:

```text
am start -a android.intent.action.VIEW -d "opah://live/front_door" app.opah.tv/.MainActivity
```

Replace `front_door` with the camera's exact name in Frigate. Capital letters
and underscores must match. Opah must already have a saved, working connection,
and it will open only cameras available to that Frigate account.

Home Assistant's Android TV ADB integration can send the same `am start`
command with its `androidtv.adb_command` action. A button-mapping app can call a
Home Assistant webhook, which then sends the command to the TV.

Tools that use an Android extra instead of a link can use this equivalent
command:

```text
am start -n app.opah.tv/.MainActivity --es camera "front_door"
```

## Important limitations

- Opah needs an existing Frigate server; it does not record cameras by itself.
- Camera audio plays from the TV, but speaking through a camera or doorbell is
  not supported.
- Picture-in-picture depends on support from the TV and Android version.
- Camera groups are muted and use lower-bandwidth streams when available. Some
  TVs may not be able to play four cameras at once.
- PTZ camera controls depend on support from Frigate and the camera.
- TV alerts depend on Android notification and background-service settings.
  A TV may delay alerts or restart after reboot when Android restricts the app.
- Monitor Mode depends on the TV's video hardware. Opah promotes only one live
  camera at a time, but long-running behavior still varies by device.
- Update downloads and installation are manual. Automatic availability checks
  can be turned off in Settings.

## Privacy and safety

- Your Frigate password and saved sign-in are encrypted on the device.
- Opah does not send camera data through an Opah cloud service.
- Opah does not keep its own archive of camera recordings.
- Local PIN and private-camera settings stay on the TV and do not change
  Frigate accounts or permissions.
- Alert rules and briefing progress are stored without camera images or a copy
  of your recordings.
- The Diagnostic data page is designed to leave out passwords, private addresses,
  and camera images.
- Use a secure `https://` Frigate address when one is available.
- Keep the live-video connection inside your trusted home or business network.

Read [Privacy and security](docs/security-model.md) for a fuller explanation.
Report a security concern through GitHub's
[private reporting form](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/security/advisories/new).

## Help and feedback

When reporting a problem, include the Opah version, Frigate version, Android or Google TV device model,
and a short description of what happened. Do not post your password, server
address, camera names or images, or unedited logs.

Developers who want to build or improve Opah should start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Opah is available under the [Apache License 2.0](LICENSE). Release history is in
[CHANGELOG.md](CHANGELOG.md).

Frigate and Frigate NVR are trademarks of Frigate, Inc. Opah uses those names
only to explain what the app works with.
