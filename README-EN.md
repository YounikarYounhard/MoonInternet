<div align="center">

<img src="src/MoonInternet.App/Assets/moon.png" width="96" alt="">

# Moon Internet

A client for VLESS, VMess, Trojan, Shadowsocks, Hysteria2 and WireGuard.
Windows and Android.

[![Release](https://img.shields.io/github/v/release/YounikarYounhard/MoonInternet?include_prereleases&label=release&color=9D7BFF)](../../releases/latest)
[![MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[![Download for Windows](https://img.shields.io/badge/Download_for-Windows-9D7BFF?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/YounikarYounhard/MoonInternet/releases/latest/download/MoonInternet-Setup.exe)
[![Download for Android](https://img.shields.io/badge/Download_for-Android-9D7BFF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/YounikarYounhard/MoonInternet/releases/latest/download/MoonInternet.apk)

[All releases](../../releases) · [Русский](README.md)

</div>

---

An ordinary client: paste a subscription link, get a server list, tap the moon.
No accounts, no sign-up, and no servers of ours — they are yours.

Version **0.9.5 beta**. Usable every day, with rough edges left.
The interface is in Russian and English and switches on the fly.

| | | |
|---|---|---|
| **Windows** | 10/11, x64 | `MoonInternet-Setup.exe` |
| **Android** | 7.0+, arm64 | `MoonInternet.apk` |

## What it does

Subscriptions refresh on the interval the panel actually sends, not one we invented.
The server list and keys sit in a cache, so servers are there without a connection.
Traffic and expiry show the way you prefer — numbers, a bar or dots — and the app says something
when less than ten percent of the quota is left or the plan is running out.

Routing is Direct, Proxy and Block by domain, IP and `geosite:` / `geoip:` tags. Take a profile
from HAPP or INCY, or build your own with a search over the geo database. Both platforms do split
tunnelling: chosen apps around the VPN, or only those through it.

The ping is honest. The usual methods answer one question — is someone listening on the port —
and that is not the same as "the protocol works": a CDN, your ISP's middlebox or an expired key
will finish the handshake too, and a dead server will report a healthy 30 ms. So there is a
Stability method that brings up a real connection and makes a request through it. Slower than the
rest, and it does not lie.

Updates come from GitHub. The download button does not hand you to a browser: it fetches the
installer and runs it, and the file is deleted at the next start.

## Installing

On Windows, run the installer from [releases](../../releases/latest). It puts the app in Program
Files, registers the helper the TUN mode needs and makes the shortcuts. .NET is inside the app,
which is where the size comes from.

On Android it is a plain APK from the same place. It installs over the previous version and keeps
your data.

The builds are not code-signed, so Windows shows SmartScreen (*More info → Run anyway*), and a
couple of antivirus engines out of sixty may flag the heuristics — an unsigned app that carries VPN
cores and edits the routing table looks odd to them. If that bothers you, build from source; you
get exactly the same thing.

## Building

```powershell
git clone https://github.com/YounikarYounhard/MoonInternet.git
cd MoonInternet

# the cores are not in the repo — that is ~120 MB of other people's binaries
powershell -ExecutionPolicy Bypass -File build\get-cores.ps1

dotnet publish src\MoonInternet.App        -c Release -r win-x64 --self-contained true -o dist\app
dotnet publish src\MoonInternet.TunService -c Release -r win-x64 --self-contained true -o dist\app

# the app looks for the cores beside its exe, and the installer packs only dist\app
Copy-Item cores dist\app\cores -Recurse -Force

makensis build\installer.nsi   # if you want the installer too
```

You need the .NET 9 SDK. For Android run `android\build-xray.ps1` (it downloads Go if you have
none), then `gradlew assembleRelease`; you will need the Android SDK, NDK and JDK 17, with
`MOON_TOOLCHAIN` pointing at them. arm64 only.

## How it is put together

We do not reimplement protocols. C# and Kotlin do the orchestration — parsing links and
subscriptions, generating configs, routing, the interface — and proven cores move the traffic:
xray-core, sing-box and tun2socks. Happ, Nekoray and v2rayN are built the same way.

```
src/MoonInternet.App          WPF interface
src/MoonInternet.Core         models, parsers, config generators
src/MoonInternet.Services     connection, cores, subscriptions, ping, geo
src/MoonInternet.TunService   privileged helper (SYSTEM) — TUN adapter and routes

android/app/.../core          port of Core
android/app/.../data          subscriptions, storage, geo
android/app/.../vpn           VpnService, xray, quick-settings tile
android/app/.../ui            Compose screens
```

The separate helper on Windows exists for a dull reason: bringing up a TUN means creating a network
adapter and editing the routing table, and neither is possible without elevation. The helper runs as
a SYSTEM scheduled task, so the app itself stays ordinary and UAC does not ask on every launch. It
listens on `127.0.0.1:35555` only and understands its own small set of commands.

Android needs none of that: `VpnService` hands over the tunnel descriptor and it goes straight into
xray's built-in TUN inbound.

## About your data

No accounts, no telemetry, no calls home. Subscriptions, servers and keys stay on the device: in the
`save\` folder next to the app on Windows, in the app's own storage on Android. The app reaches
exactly two kinds of address — your subscription links, and the geo-rule sources on GitHub, and the
second only when routing is on. Logs are local.

## Licences

Moon Internet's code is [MIT](LICENSE). The cores and libraries come under their own, listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md); sing-box and tun2socks are GPL-3.0 and ship
unmodified.

---

*This is a proxy tool, not a VPN service: the servers are yours to add. How you use it, and staying
within your country's laws, is on you.*
