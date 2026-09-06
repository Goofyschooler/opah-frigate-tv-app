# Privacy and security

This page explains what Opah saves, how it communicates with Frigate, and what
you can do to keep the connection safe.

## In everyday terms

- Your Frigate username, password, and saved sign-in are encrypted on the
  device.
- Opah connects directly from the TV to the Frigate addresses you provide. It
  does not send your camera data through an Opah cloud service.
- Opah contacts the official GitHub release service to check for updates. It
  does not send camera images, recordings, or Frigate sign-in information to
  GitHub.
- Opah does not create its own camera recording archive.
- The Diagnostic data page is designed to leave out passwords, private addresses,
  camera images, and detailed server responses.
- Signing out removes the saved sign-in. Choosing **Forget server** also removes
  the saved server information and its local briefing progress.
- A local PIN can protect selected app areas, actions, and private cameras on a
  shared TV. It adds protection on the TV but does not replace Frigate account
  permissions.

## Information kept on the TV

Opah keeps only the information needed to remember your preferences and connect
to Frigate:

- the server connection settings;
- display and playback preferences, including saved camera groups;
- alert choices, snoozes, and notification history needed to prevent repeated
  alerts;
- successful camera playback choices, stored under opaque local identifiers;
- local PIN verification and lockout records, never the PIN itself;
- bounded briefing activity IDs and acknowledgement progress, without preview
  images or recordings;
- the current signed-in session; and
- your username and password if automatic sign-in is enabled.

Camera previews are held temporarily while they are displayed. Opah does not
keep a separate long-term copy of Frigate recordings. Recordings saved through
Opah remain on the Frigate server.

Notification preview images are temporary and are removed when an alert is
dismissed, expires, or its privacy scope changes. Text-only alerts do not fetch
a preview. Opah's background awareness connection goes directly from the TV to
the signed-in Frigate server; there is no Opah notification relay.

Opah remembers the latest official release information so it does not need to
check GitHub every time it opens. An update APK is downloaded only after you
choose to download it. Opah verifies the file before asking Android to open the
installer.

## Keeping the connection safe

- Use a secure `https://` Frigate address when one is available.
- Keep Frigate's live-video service inside a trusted home or business network.
- Do not expose the live-video port directly to the internet just to use Opah.
- Keep the TV and Frigate server updated and limit physical access to them.
- A modified or rooted TV may be able to bypass normal Android protections.

Frigate decides which cameras an account may use. Opah also filters information
to the cameras available to the signed-in account, but it cannot protect against
a compromised Frigate server.

Use a dedicated restricted Frigate account on a shared TV when possible. Opah's
local PIN cannot hide information that the Frigate account or another app on
the TV already exposes.

## Technical details for security reviewers

This section is optional and is intended for developers and security reviewers.

- Saved credentials and sessions use Android Keystore-backed AES-256-GCM
  encryption and are excluded from Android backup.
- The local PIN uses a salted, deliberately slow verifier protected by Android
  Keystore material. Failed attempts use a persisted lockout delay.
- Structured alert, playback, and briefing records use Room transactions.
  Profile, camera, authorization, and audience keys are locally keyed opaque
  digests rather than raw private URLs.
- Every notification, Monitor, briefing, deep-link, and playback action checks
  current Frigate camera authorization and local privacy again before use.
- Local briefing dismissal changes only Opah's bounded acknowledgement state;
  it does not mark the item reviewed in Frigate.
- Web requests reject redirects so a password or signed-in session is not
  automatically resent to a different address.
- Secure connections use Android's normal certificate and hostname checks.
- Diagnostics use safe error categories rather than raw responses, cookies,
  addresses, or stack traces.
- Before opening a downloaded update, Opah checks its Android package, version,
  publisher identity, and checksum.

No application can prevent someone from photographing the television, using an
HDMI capture device, or taking an Android screenshot when the device allows it.
