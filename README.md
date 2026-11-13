# Free VPN (Android)

A lightweight Android WireGuard client with a simple server-profile manager.

## Current scope

- Import WireGuard `.conf` profiles from a VPN provider.
- Validate a profile before saving it in the app's private storage.
- Connect and disconnect through Android's VPN permission dialog.
- Build a debug APK automatically with GitHub Actions.

This repository does not include VPN servers, private keys, or a bundled provider. A VPN app is only a client: each location needs a real WireGuard server and a valid client configuration issued by its operator. The app lists locations represented by imported profiles; it does not claim that every country is available.

## Build

GitHub Actions builds `app-debug.apk` on each push. Open the repository's **Actions** tab, select the latest **Android APK** run, and download the `free-vpn-debug-apk` artifact.

For local builds, use JDK 17 and Gradle 8.13:

```sh
gradle assembleDebug
```

## Import a server

In the app, tap **Add configuration** and select a WireGuard `.conf` file supplied by your VPN provider. Then select the profile and tap **Connect**. The first connection requires Android's VPN permission.

Only import configurations from providers you trust. The operator of the selected VPN server can observe traffic metadata and may be able to inspect unencrypted traffic. Use HTTPS and review the provider's privacy policy.

## Technology

- Kotlin / Android SDK
- Official WireGuard Android tunnel library
- Android `VpnService`
