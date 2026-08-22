# Opah

Opah lets you view cameras and recordings from your
[Frigate security-camera system](https://frigate.video/) on an Android TV or
Google TV using a normal television remote.

Opah is an independent community project and is not affiliated with, sponsored
by, endorsed by, or supported by Frigate, Inc.

## What you can do

- See live views from your cameras
- Watch up to four live cameras together and save favorite groups
- Open Frigate Birdseye in a dedicated full-screen view
- Browse alerts and detections from Frigate Review
- Mark activity as reviewed and see what still needs attention
- Search saved activity and find similar items when Frigate search is enabled
- Browse saved video by camera, day, and time
- Save important recordings and open them from the Saved page
- Play recordings with a timeline and familiar video controls
- Open a chosen camera directly from a compatible shortcut or automation
- Keep a live camera visible in picture-in-picture while using another app
- Hear camera audio and mute it when needed
- View Frigate storage and performance information
- Find project, privacy, and support information on the in-app About page
- Choose a light, dark, system, or custom color theme
- Sign in automatically after the first successful connection

Opah is designed for a television remote. You do not need a mouse or touch
screen.

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

Opah has been tested with Frigate 0.17.2 and the exact Frigate 0.18.0 beta 3
build `344efb6`. See the [compatibility guide](docs/compatibility.md) for camera
video formats, tested devices, and troubleshooting tips.

## Install Opah

Opah is currently downloaded from GitHub rather than Google Play.

1. Open the [Opah releases page](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases)
   and download the APK from the newest release.
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
possible. Most people can leave the advanced connection settings unchanged.
Those settings are available for networks where live video uses a different
local address.

After a successful sign-in, Opah can securely remember the connection and sign
in automatically. Choose **Sign out** to remove the saved sign-in information.

## Watch cameras together

Open **Cameras**, then choose **Watch cameras together**. Pick two to four
cameras and choose **Watch**. Opah fills the screen with every camera picture.
On supported TVs, a two-camera group can also pop out over another app. Camera
names hide automatically while you watch.

Choose **Save view** to name a group you use often. Saved views stay on that
device. Camera groups already set up in Frigate also appear here. If a group has
more than four cameras, Opah asks you which cameras to watch.

## Find and review activity

Open **Activity** to see alerts and other recorded activity:

- **Recent** shows new items and which ones still need review.
- **History** lets you choose a camera, day, and time to browse saved video.
- **Search** finds saved activity from a short description when search is set
  up in Frigate.

Open an item to watch it, mark it reviewed or not reviewed, save its recording,
or find activity that looks similar. Frigate may also provide a short summary,
a recognized name, or a license plate.

## Save important recordings

Choose **Save recording** while watching recorded activity or from its details.
You can also save a selected part of **History**. Open **Saved** to see preview
pictures, play a recording, or open its details. Frigate administrators can
permanently delete a saved recording after a confirmation warning.

Saved recordings remain on the Frigate server. They are not copied into a
separate Opah cloud service or stored as a second recording archive on the TV.

## Control and size cameras

If Frigate reports compatible camera controls, a **Controls** choice appears
under that camera on the **Cameras** page. It can show movement, zoom, focus,
and saved-position controls supported by that camera.

While watching one camera, use the screen-size control to choose whether the
whole picture fits on the television or fills the screen. Opah remembers that
choice for each camera on the device.

## Get updates

Opah checks the official GitHub release page for a newer version. A small dot
inside the Settings gear means an update is available. Open **Settings** >
**Update** to read the release notes, download the verified APK, and open
Android's installer. Opah never installs an update without your action.

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
- Pausing a live camera freezes the picture. It does not let you rewind live
  video.
- Camera audio plays from the TV, but speaking through a camera or doorbell is
  not supported.
- Picture-in-picture depends on support from the TV and Android version.
- Camera groups are muted and use lower-bandwidth streams when available. Some
  TVs may not be able to play four cameras at once.
- Camera controls depend on support from Frigate and the camera. They have not
  yet been tested with a physical PTZ camera.
- Updates are manual. Download and open the newer APK when a new release is
  available.

## Privacy and safety

- Your Frigate password and saved sign-in are encrypted on the device.
- Opah does not send camera data through an Opah cloud service.
- Opah does not keep its own archive of camera recordings.
- The Diagnostics page is designed to leave out passwords, private addresses,
  and camera images.
- Use a secure `https://` Frigate address when one is available.
- Keep the live-video connection inside your trusted home or business network.

Read [Privacy and security](docs/security-model.md) for a fuller explanation.
Report a security concern through GitHub's
[private reporting form](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/security/advisories/new).

## Help and feedback

When reporting a problem, include the Opah version, Frigate version, TV model,
and a short description of what happened. Do not post your password, server
address, camera names or images, or unedited logs.

Developers who want to build or improve Opah should start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Opah is available under the [Apache License 2.0](LICENSE). Release history is in
[CHANGELOG.md](CHANGELOG.md).

Frigate and Frigate NVR are trademarks of Frigate, Inc. Opah uses those names
only to explain what the app works with.
