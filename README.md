<div align="center">
  <img src="branding/yaxc-mark.svg" width="112" height="112" alt="yaxc app icon">
  <h1>yaxc</h1>
  <p><strong>Your Xray profiles. Your routing rules.</strong></p>
  <p>An Android client for Xray-core: manage subscriptions, compare servers and choose how your apps connect.</p>
  <p>
    <a href="https://github.com/derundevu/yaxc/releases/latest"><img src="https://img.shields.io/github/v/release/derundevu/yaxc?label=release&amp;color=7cf4c4" alt="Latest release"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-7cf4c4" alt="Requires Android 8.0 or newer">
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-7cf4c4" alt="MIT license"></a>
  </p>
  <p>
    <a href="https://github.com/derundevu/yaxc/releases"><strong>Download APK</strong></a>
    · <a href="#getting-started">Getting started</a>
    · <a href="#screenshots">Screenshots</a>
    · <a href="#themes">Themes</a>
    · <a href="https://github.com/derundevu/yaxc/issues">Report a bug</a>
  </p>
</div>

---

**yaxc is a client, not a VPN service.** Bring a subscription or a compatible server configuration from a provider you trust.
The standard Android VPN mode does **not** require root.

## Features

### Profiles and subscriptions

- Import subscriptions and profile share links from the clipboard or a QR code.
- Keep multiple sources organized alongside your manually created profiles.
- Refresh subscriptions manually or automatically; see traffic usage and expiry when your provider supplies them.
- Check a single profile or compare a group with batch latency checks. Choose HTTP GET, HTTP HEAD or TCP checks.

### Routing you control

- **Per-app split tunneling:** route all apps through the VPN, exclude selected apps or include only selected apps.
- **Core routing:** build ordered rules in the visual editor or edit routing JSON directly. Send matching traffic through the proxy, directly or to a blocking outbound.
- Match domains, IP ranges, ports, protocols and TCP/UDP transport; import and export routing JSON through the clipboard.
- Download or import GeoIP, GeoSite and Antifilter lists in the app. Choose a bundled provider preset or your own geodata URLs.

### Tools for everyday use and advanced setups

- A full-screen JSON editor with syntax diagnostics, formatting, undo/redo and find/replace.
- Connection details and live runtime logs for troubleshooting.
- English and Russian interfaces, system-following appearance and four theme presets, including Mint Graphite and Light Slate.
- In-app release checks and APK updates.
- A Quick Settings tile for connection control, plus optional start on boot.
- Optional root/transparent-proxy settings for advanced hotspot and tethering setups.

## Screenshots

Captured from the **1.5.3 development build** on an isolated Android emulator. All profile names, endpoints and subscription
usage are fictional demo data. These are real app screens, not mockups; the VPN is not connected.

<table>
  <tr>
    <th>Profiles &amp; subscriptions</th>
    <th>Core routing</th>
    <th>Per-app routing</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/connect.png"><img src="docs/screenshots/connect.png" width="250" alt="Connect screen in Mint Graphite with a fictional demo subscription and server profiles"></a></td>
    <td><a href="docs/screenshots/core-routing.png"><img src="docs/screenshots/core-routing.png" width="250" alt="Visual core routing editor with ordered rules and a domain strategy selector"></a></td>
    <td><a href="docs/screenshots/apps-routing.png"><img src="docs/screenshots/apps-routing.png" width="250" alt="Per-app routing with Off, Exclude and Include modes, search and app selection"></a></td>
  </tr>
  <tr>
    <th>JSON workspace</th>
    <th>Routing resources</th>
    <th>Preferences &amp; themes</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/json-editor.png"><img src="docs/screenshots/json-editor.png" width="250" alt="Full-screen JSON editor showing a credential-free local routing example"></a></td>
    <td><a href="docs/screenshots/resources.png"><img src="docs/screenshots/resources.png" width="250" alt="GeoIP and GeoSite resource management in Mint Graphite with download and file import controls"></a></td>
    <td><a href="docs/screenshots/preferences.png"><img src="docs/screenshots/preferences.png" width="250" alt="Light Slate preferences with the five-choice App Theme dialog open"></a></td>
  </tr>
</table>

Select any screenshot to view it at full resolution.

### Themes

Choose **Settings → Preferences → Basic → App Theme**, then save. Each preset below shows the same
**Connect** and **Core Routing** screens with identical demo data so you can compare the colors directly.

<table>
  <tr>
    <th>Midnight Blue · Connect</th>
    <th>Midnight Blue · Core Routing</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/themes/midnight-blue-connect.png"><img src="docs/screenshots/themes/midnight-blue-connect.png" width="250" alt="Midnight Blue theme on the Connect screen with fictional demo profiles"></a></td>
    <td><a href="docs/screenshots/themes/midnight-blue-routing.png"><img src="docs/screenshots/themes/midnight-blue-routing.png" width="250" alt="Midnight Blue theme in the visual Core Routing editor"></a></td>
  </tr>
  <tr>
    <th>Graphite · Connect</th>
    <th>Graphite · Core Routing</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/themes/graphite-connect.png"><img src="docs/screenshots/themes/graphite-connect.png" width="250" alt="Graphite theme on the Connect screen with fictional demo profiles"></a></td>
    <td><a href="docs/screenshots/themes/graphite-routing.png"><img src="docs/screenshots/themes/graphite-routing.png" width="250" alt="Graphite theme in the visual Core Routing editor"></a></td>
  </tr>
  <tr>
    <th>Mint Graphite · Connect</th>
    <th>Mint Graphite · Core Routing</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/themes/mint-graphite-connect.png"><img src="docs/screenshots/themes/mint-graphite-connect.png" width="250" alt="Mint Graphite theme on the Connect screen with fictional demo profiles"></a></td>
    <td><a href="docs/screenshots/themes/mint-graphite-routing.png"><img src="docs/screenshots/themes/mint-graphite-routing.png" width="250" alt="Mint Graphite theme in the visual Core Routing editor"></a></td>
  </tr>
  <tr>
    <th>Light Slate · Connect</th>
    <th>Light Slate · Core Routing</th>
  </tr>
  <tr>
    <td><a href="docs/screenshots/themes/light-slate-connect.png"><img src="docs/screenshots/themes/light-slate-connect.png" width="250" alt="Light Slate theme on the Connect screen with fictional demo profiles"></a></td>
    <td><a href="docs/screenshots/themes/light-slate-routing.png"><img src="docs/screenshots/themes/light-slate-routing.png" width="250" alt="Light Slate theme in the visual Core Routing editor"></a></td>
  </tr>
</table>

<details>
  <summary><strong>System default — light and dark previews</strong></summary>
  <p>System default follows Android's appearance: Light Slate in light mode and Midnight Blue in dark mode.
  These captures were taken with <strong>System default</strong> selected and Android switched between the two modes.</p>
  <table>
    <tr>
      <th>System light · Connect</th>
      <th>System light · Core Routing</th>
    </tr>
    <tr>
      <td><a href="docs/screenshots/themes/system-light-connect.png"><img src="docs/screenshots/themes/system-light-connect.png" width="250" alt="System default theme with Android in light mode on the Connect screen"></a></td>
      <td><a href="docs/screenshots/themes/system-light-routing.png"><img src="docs/screenshots/themes/system-light-routing.png" width="250" alt="System default theme with Android in light mode in Core Routing"></a></td>
    </tr>
    <tr>
      <th>System dark · Connect</th>
      <th>System dark · Core Routing</th>
    </tr>
    <tr>
      <td><a href="docs/screenshots/themes/system-dark-connect.png"><img src="docs/screenshots/themes/system-dark-connect.png" width="250" alt="System default theme with Android in dark mode on the Connect screen"></a></td>
      <td><a href="docs/screenshots/themes/system-dark-routing.png"><img src="docs/screenshots/themes/system-dark-routing.png" width="250" alt="System default theme with Android in dark mode in Core Routing"></a></td>
    </tr>
  </table>
</details>


## Download

Download APKs from [GitHub Releases](https://github.com/derundevu/yaxc/releases). Requires **Android 8.0 or newer**.

| APK | Choose this for |
| --- | --- |
| `universal` | Any supported architecture; the simplest choice if you are unsure. Available starting with **1.5.3**. |
| `arm64-v8a` | Most current Android phones and tablets; smaller than universal. |
| `armeabi-v7a` | Older devices running 32-bit ARM Android. |
| `x86_64` | Devices and emulators running 64-bit x86 Android. |
| `x86` | Devices and emulators running 32-bit x86 Android. |

Allow your browser or file manager to install apps from this source if Android asks.
When updating, install over the existing app using the same signing source to keep your profiles and preferences.
If Android reports a signing conflict, do not uninstall just to bypass it: uninstalling removes app data.

## Getting started

1. **Install yaxc** using the APK for your device.
2. **Add your configuration.** Copy an HTTPS subscription URL or a supported profile share link, then use **From Clipboard**
   on the Connect screen. You can also use **Scan QR code**, or create a manual group and a JSON profile.
3. **Prepare routing resources.** Open **Settings → Assets** and download `geoip.dat` and `geosite.dat` for the default
   routing rules. Configurations without geodata references do not need these files.
4. **Choose a profile.** Open its group, select a server and optionally run a latency check.
5. **Connect.** Tap the power button and approve Android's VPN request. Use the same button to disconnect.

For split tunneling, open **Routing → Apps Routing**, choose **Off**, **Exclude** or **Include**, select apps and save.
For domain/IP rules, open **Routing → Core Routing**. Rules are evaluated from top to bottom; higher rules take precedence.

## Subscription updates

Automatic refresh is optional. Enable **Settings → Preferences → Auto-refresh subscriptions** and set the refresh interval;
provider-supplied intervals are used when available. Android may defer background work.

Each source is updated as a complete batch: a failed or malformed refresh keeps that source's previous profiles.
Cancellation before commit also keeps them; an update already committing finishes before reporting its result.
An unchanged active configuration keeps the existing connection running. A changed configuration is validated before
replacing the active core; an invalid replacement keeps the previous connection and reports an error.
If an update removes the selected profile, the VPN stops rather than silently using a deleted profile.
When refreshing multiple sources, successful sources can still update; the result reports any partial failures.

## FAQ

### Does yaxc include servers or a subscription?

No. You need your own compatible configuration or an HTTPS subscription from a provider.
Availability, speed and server-side privacy depend on that provider and your network.

### Which configuration formats can I use?

Use Xray JSON configurations or supported profile share links, including VLESS. You can also open a local JSON file with yaxc.
Subscription sources can provide raw JSON or supported share-link lists, including base64-encoded subscriptions.
A feature supported by Xray-core is not necessarily available through every share-link format.

### Why does the connection ask for GeoIP or GeoSite?

A routing rule references `geoip.dat`, `geosite.dat` or a group inside one of those files.
On the error dialog, choose **Resources**, download or import a compatible file, then return and tap **Retry**.
If a group is missing, choose a rule set containing it or change the rule. Recovery does not automatically download files or connect.

### Do I need root? Can this replace a kill switch?

Normal VPN use does not require root. Transparent-proxy mode is an advanced, root-dependent path and requires a separately
installed Xray executable in **Assets**. Root/iptables behavior varies by device and is not as broadly verified as VPN mode.

Experimental TUN protection is **not a guaranteed kill switch**. Do not rely on yaxc to prevent every possible traffic leak.

### What information is sent over the network?

Subscription requests go to your provider and can include the configured **User-Agent**, **x-hwid** identifier and Android/device
headers. Review global values in **Preferences** and per-source overrides in **Links**.
Release checks contact GitHub; resource downloads and latency checks contact their configured endpoints.
Treat subscription URLs and exported configurations as secrets, and choose providers you trust.

### What should I include in a bug report?

Open an [issue](https://github.com/derundevu/yaxc/issues) with your app version, Android version, device model,
steps to reproduce and the relevant error or log excerpt. Note whether you use VPN or root mode.
**Redact subscription URLs and tokens, server addresses, UUIDs, passwords, keys and local proxy credentials** before sharing logs,
JSON or screenshots.

## Contributing

Bug reports, reproducible examples and focused pull requests are welcome.
Build instructions, native dependency details and verification workflows live in the
[development guide](docs/DEVELOPMENT.md), not in this user guide.

## Credits and license

yaxc is an independently maintained fork of [SaeedDev94/Xray](https://github.com/SaeedDev94/Xray),
powered by [Xray-core](https://github.com/XTLS/Xray-core) and [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

The app is licensed under [MIT](LICENSE). Bundled third-party components retain their respective licenses.
