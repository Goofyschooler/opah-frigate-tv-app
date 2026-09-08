# Install Opah on Android TV or Google TV

Opah is currently installed from GitHub rather than Google Play. The download
is an APK, which is simply an Android installation file.

## Before you begin

You need:

- an Android TV or Google TV running Android 7.0 or newer;
- a working Frigate server that the TV can reach;
- a Frigate username and password for the recommended authenticated connection;
  and
- a way to send the downloaded APK to the TV, such as a USB drive or a trusted
  file-transfer app.

## Download the correct file

Use the [permanent latest Opah download](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases/latest/download/opah-latest.apk).
This link always downloads `opah-latest.apk` from the newest published release.

You can also open the project's [GitHub Releases page](https://github.com/VibeCodingAntagonist/opah-frigate-tv-app/releases),
choose the newest release, and download the versioned file named like
`opah-vX.Y.Z.apk`. The letters stand for the release version; for example,
version 0.4.0 is named `opah-v0.4.0.apk`. Both APK names in that release contain
the same app.

Do not download Opah from an unofficial mirror or APK website.

## Install it on the TV

1. Copy the APK to the TV using a USB drive or a trusted file-transfer method.
2. Open the APK with the TV's file manager.
3. If Android asks for permission to install apps from that file manager, open
   the displayed settings page and allow it. This is Android's normal warning
   for apps installed outside Google Play.
4. Return to the APK and choose **Install**.
5. When installation finishes, open **Opah** from the Apps row.
6. For extra safety, you may turn off the file manager's install permission
   after Opah is installed.

The exact wording and location of these options varies by television.

## Connect to Frigate

Enter your Frigate server address, username, and password. Use the same secure
`https://` address you normally use in a browser when possible.

Keep **Live video address** set to **Same as Frigate (recommended)** unless live
video must use a different local host or port. If you choose a separate
address, Opah shows the additional fields and explains what they affect.

Select an address or sign-in field to open the on-screen keyboard. Opah asks
Android to show it immediately and retries if the TV is slow to respond. After
the first successful sign-in, Opah can remember the connection and sign in
automatically.

If the TV and Frigate server are on the same trusted private network, you can
instead enter Frigate's direct port `5000` address, such as
`http://frigate.local:5000`. Leave the username and password blank. This option
does not use an account and gives the TV full Frigate access. Never expose port
`5000` to the internet; use Frigate's authenticated address for ordinary or
remote connections.

## Optional TV alerts

TV alerts are off until you enable them in **Settings** > **TV alerts**. On
Android 13 or newer, Android asks for notification permission when you turn
them on. If you decline, Opah continues to work normally without TV alerts.

Some TVs restrict apps in the background or after a reboot. The TV alerts page
shows when Android has blocked notifications and includes a shortcut to the
app's notification settings. Android may still delay restarting alerts until
you open Opah again.

## Update Opah

When Opah finds a newer release, a small dot appears inside the Settings gear.
Open **Settings** > **Update** to:

1. read what is new;
2. choose **Download update**; and
3. choose **Install now** after the download is checked.

Android may ask you to let Opah open installation files. This permission lets
Android show its normal installer; Opah still cannot install anything without
your action.

You can also download the newer APK from the Releases page above, send it to the
TV, and open it yourself. Android should offer to update the existing app. Your
saved server, sign-in, camera groups, and display choices should remain in
place.

### One-time step when moving from 0.2.0 to 0.2.1

Versions `0.2.0` and `0.2.1` use different security keys, so Android cannot
install one over the other. Uninstall `0.2.0`, install `0.2.1`, and connect to
Frigate again. Later updates should install normally and keep the saved
connection.

If Android says the update is not compatible with the installed copy, stop and
make sure both copies came from this official repository. Do not uninstall the
working app unless you are prepared to enter your connection details again.

Opah checks for updates through the official GitHub release page. It does not
download or install an update automatically.

## Remove Opah

Uninstall Opah through the TV's normal app settings. Uninstalling removes the
saved Frigate connection and sign-in information from that TV.

## Optional: check the download

Each release includes a small `.sha256` file. Advanced users can compare it
with the APK to confirm the download was not damaged or changed.

On Windows PowerShell:

```powershell
Get-FileHash .\opah-vX.Y.Z.apk -Algorithm SHA256
Get-Content .\opah-vX.Y.Z.apk.sha256
```

On macOS or Linux:

```bash
sha256sum opah-vX.Y.Z.apk
cat opah-vX.Y.Z.apk.sha256
```

Replace `X.Y.Z` with the release version. The long strings of letters and
numbers should match exactly.

## Optional: install with ADB

ADB is an Android developer tool. You do not need it for a normal installation.
If you already use ADB, connect to the intended TV and run:

```text
adb install opah-vX.Y.Z.apk
```

Replace `X.Y.Z` with the release version you downloaded.

Turn off wireless debugging when you finish.

## About Android's installation rules

Android's rules for apps installed outside Google Play are changing over time
and can vary by country and device. Opah will continue to document the current
GitHub installation method. Google's current explanation is available in its
[Android developer verification guide](https://developer.android.com/developer-verification/guides).
