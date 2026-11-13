# Free VPN for Android — standalone APK

This repository now builds a **single standalone Android VPN app**. The OpenVPN engine is included inside the APK; users do **not** need to install OpenVPN for Android or another VPN client separately.

## What it includes

- Embedded OpenVPN tunnel engine and Android `VpnService`.
- Live directory of public VPN Gate relay servers.
- Automatic country/location discovery from the current directory.
- Search, filters, server ranking, ping/speed/session information.
- Connect, disconnect, pause/resume, connection statistics and diagnostics.

The current server supply comes from the public VPN Gate volunteer relay network. The app does not operate its own global server infrastructure. Locations shown in the app are only locations currently present in the upstream directory; coverage and availability can change, and no country is guaranteed to be online at all times.

## How to install

1. Download the latest `free-vpn-standalone-apk` artifact from the repository's **Actions** tab.
2. Extract `app-debug.apk` from the downloaded ZIP.
3. Install the APK on Android and approve Android's standard VPN permission when asked.

No separate OpenVPN app is required.

## Build source and reproducibility

The standalone client and bundled VPN engine are pinned as the `standalone-vpn` Git submodule at:

- Upstream project: https://github.com/IyedAmiri/OpenGateVPN
- Pinned source commit: `12f8ea97976f1bac0307a013b40712098b865d43`
- The GitHub Actions workflow rebrands its application package to `com.freevpn.client`, the app label to **Free VPN**, and the version to `3.0.0`, then builds a debug APK.

Clone with submodules enabled:

```sh
git clone --recurse-submodules https://github.com/white444ramazan-commits/Free_vpn.git
cd Free_vpn/standalone-vpn
./gradlew assembleDebug
```

The distributable contains the corresponding source in the pinned submodule. The upstream project and its bundled engine are GPL-licensed; preserve the GPL notices and source when redistributing modified builds. See `standalone-vpn/LICENSE` and `standalone-vpn/vpnLib/LICENSE`.

## Privacy and safety

VPN Gate relays are run by volunteers and are not controlled by this app. A relay operator may observe connection metadata and may handle traffic according to its own logging practices. Do not use public relays for banking, sensitive accounts, or unencrypted confidential traffic. Prefer HTTPS. A directory listing, ping, or advertised throughput is not proof that a complete VPN handshake will succeed.

The app retrieves the server directory over HTTPS. VPN Gate server addresses and availability are dynamic.
