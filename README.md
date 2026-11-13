# Free VPN for Android

Free VPN is an Android client for the public VPN Gate relay directory. It fetches the live server list, groups available relays by country, and lets the user select an OpenVPN profile without manually downloading configuration files.

## VPN Gate server directory

The app reads the public CSV endpoint maintained by VPN Gate:

- API: https://www.vpngate.net/api/iphone/
- Project: https://www.vpngate.net/en/
- VPN Gate is an academic experiment operated by the University of Tsukuba and uses volunteer-run public relays.

The directory is dynamic. Countries and servers shown in the app are only those currently returned by the upstream API with an OpenVPN configuration. The app does not claim permanent availability or coverage in every country.

## OpenVPN engine requirement

The current app uses the documented AIDL control API of **OpenVPN for Android** (package `de.blinkt.openvpn`) as its OpenVPN engine. Install that free client once from Google Play:

https://play.google.com/store/apps/details?id=de.blinkt.openvpn

After it is installed, Free VPN can ask it to start the selected inline VPN Gate profile and request the normal Android VPN permission. The OpenVPN engine runs the tunnel; Free VPN provides the server directory and selection UI.

This integration uses the public external-app API documented by the OpenVPN for Android project. The API is not the same as bundling the OpenVPN engine into this APK.

## Privacy and safety

VPN Gate relays are operated by volunteers, not by this app. Relay operators may observe traffic metadata and may apply their own logging policy. Never use an unknown public relay for sensitive accounts, banking, or traffic that you would not trust on an open network. Prefer HTTPS even while connected. A server's presence in the directory does not guarantee that it is reachable from your network or that its advertised speed is current.

The app downloads the directory over HTTPS, caches the last successful response, and does not upload user traffic or private keys.

## Build

GitHub Actions builds `app-debug.apk` on every push to `main`.

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin 2.2.20

Build locally with:

```sh
gradle assembleDebug
```

## Attribution

The AIDL interface follows the public OpenVPN for Android external control API and its Apache-2.0-licensed remote example. See https://github.com/schwabe/ics-openvpn. VPN Gate server metadata and OpenVPN configurations are supplied by https://www.vpngate.net/.
