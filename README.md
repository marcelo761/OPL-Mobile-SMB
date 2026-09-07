# OPL Mobile SMB

Android app that turns the phone itself into an SMBv1 game server for **Open PS2 Loader (OPL)**.

## What it does

- Creates an app-managed `PS2SMB/DVD` and `PS2SMB/CD` library on the phone.
- Lets you pick a local `.iso` with Android's file picker.
- Streams/copies the ISO into the selected OPL folder without loading the whole image into RAM.
- Runs an SMBv1 server directly on the phone.
- Uses TCP port **4450**, so root is not required.
- Exposes the share as **PS2SMB** with guest access.
- Keeps a foreground service, CPU wake lock and Wi-Fi lock while the server is active.
- Lists and deletes local ISOs from the app.

## OPL configuration

Connect the PS2 and Android device to the same local network. Start the server in the app and note the phone IP.

In OPL network/SMB settings use:

```text
Server / IP: <phone IP shown in app>
Port:        4450
Share:       PS2SMB
User:        (empty)
Password:    (empty)
```

Enable/start ETH/network games and refresh the game list.

The OPL project currently documents SMBv1 shares and direct ISO support in `DVD/` and `CD/`.

## Build

Open this folder with a recent Android Studio.

Requirements:

- Android SDK 36 installed
- JDK 17+
- Internet access during the first Gradle sync (dependencies are fetched from Google/Maven Central/JitPack)

Then build the `debug` APK from Android Studio, or with an installed Gradle:

```bash
./build-apk.sh
```

A GitHub Actions workflow is also included at `.github/workflows/android.yml`; after pushing the project to GitHub, run **Build Android APK** and download the generated artifact.

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Technical notes

This proof-of-concept uses the Android-compatible JFileServer fork published under the `com.github.buttercookie42:jfileserver` JitPack coordinate. The server is configured for native SMB-over-TCP on port 4450 and guest access.

The SMB approach and Android-specific JFileServer compatibility choices were informed by the open-source SimbaDroid project. SimbaDroid is MPL-2.0 licensed; this project does not bundle SimbaDroid itself.

JFileServer is LGPL-3.0 licensed. See the upstream projects for their complete license terms.

## Security

SMBv1 is an obsolete protocol. This app is intentionally designed for a trusted home LAN and OPL compatibility. Do not port-forward TCP 4450 or expose the server to the public Internet.

## Current status

Source-complete proof of concept. It still needs to be built and tested on a physical Android device + PS2/OPL combination. In particular, OPL/JFileServer negotiation should be verified on the exact OPL build you use before treating it as production-ready.
