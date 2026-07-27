# 🌙 Moon Internet

A customizable VPN/proxy client for Windows — WPF / .NET 9.
Works with HAPP, INCY, V2Ray/Xray and Nekoray-style subscriptions.

**Кастомизируемый VPN/прокси-клиент для Windows.** Подписки HAPP, INCY, V2Ray/Xray и совместимые.

*[Версия на русском](README.md)*

> **Status: 0.9.0 beta.** Usable day to day, but expect rough edges.
> The UI is currently **Russian only**.

---

## Features

- **Protocols** — VLESS (Reality / XHTTP / gRPC / WS / TCP), VMess, Trojan, Shadowsocks, Hysteria2, WireGuard
- **Two modes** — full **TUN** tunnel (via a privileged helper) or **system proxy** (no admin needed)
- **Subscriptions** — auto-update on the panel's own interval, traffic/expiry, the panel's welcome banner, and an offline cache so servers stay visible without internet
- **Routing** — Direct / Proxy / Block by domain, IP or `geosite:` / `geoip:` tags. Import from HAPP/INCY or build your own, with a searchable browser over the geo database
- **Split tunnel** — send selected apps around the VPN (or only them through it)
- **Per-server tools** — favourites, ping, config viewer (highlighted JSON), copy link, QR code
- **Live stats** — real upload/download speed and session traffic, read from the core
- **Tray** — connect, switch server, ping, sorting, transport mode
- **Appearance** — themes, accent/background/text colours, font, window transparency, custom moon artwork

## Install

Grab the installer from [**Releases**](../../releases) and run it.
It installs into `Program Files`, registers the privileged TUN helper and creates shortcuts.

**Nothing else to install** — .NET is bundled inside the app (that's why the installer is ~72 MB).

> Builds are **not code-signed**, so SmartScreen warns on first launch
> (*More info → Run anyway*). See [Security](#security).

## Build from source

```powershell
git clone https://github.com/<you>/moon-internet.git
cd moon-internet

# tunnel engines are not in the repo (~120 MB of third-party binaries)
powershell -ExecutionPolicy Bypass -File build\get-cores.ps1

dotnet publish src\MoonInternet.App        -c Release -r win-x64 --self-contained true -o dist\app
dotnet publish src\MoonInternet.TunService -c Release -r win-x64 --self-contained true -o dist\app

# optional: build the installer (needs NSIS)
makensis build\installer.nsi
```

Needed **to build**, not to run: **.NET 9 SDK**, Windows 10/11 x64. NSIS only if you also want the installer.

## Architecture

Moon Internet **does not reimplement any protocol**. The orchestration is written in C#
(link/subscription parsing, config generation, routing, IPC, TUN lifecycle, UI) while the
actual traffic is handled by proven engines — the same approach Happ, Nekoray and v2rayN take.

```
MoonInternet.App          WPF UI (MVVM)
MoonInternet.Core         models, parsers, config generators
MoonInternet.Services     connection manager, cores, subscriptions, ping, geo
MoonInternet.TunService   privileged helper (SYSTEM) — TUN adapter + routes
```

Why the split: full TUN on Windows needs a network adapter and routing-table edits, which
require elevation. The helper runs as a SYSTEM scheduled task, so the app itself stays
de-elevated and there's no UAC prompt on every launch. Without the helper, TUN falls back
to system-proxy mode.

Engines: **xray-core** (VLESS/VMess/Trojan/SS + router), **sing-box** (TUN, Hysteria2,
WireGuard), **tun2socks** (alternative TUN engine).

## Privacy

- No accounts, no telemetry, no analytics, no phoning home.
- Subscriptions, servers and keys stay **on your machine**, in the app's `save\` folder.
- The app contacts exactly two kinds of endpoint: **your** subscription URLs, and the
  geo-rule sources on GitHub (only when routing is enabled).
- Logs are local and never uploaded.

## Security

Release builds are **unsigned**, which explains two things:

1. **SmartScreen** warns about an unknown publisher.
2. **Heuristic AV hits** (a couple of engines out of ~60). An unsigned .NET binary that
   bundles VPN engines and edits routes looks unusual to ML scanners. If that bothers you,
   build from source — the steps above produce the same application.

The TUN helper listens on **loopback only** (`127.0.0.1:35555`) and accepts only its own
small command set.

## Third-party

Engines and libraries keep their own licenses — see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
sing-box and tun2socks are GPL-3.0 and are shipped **unmodified**.

## License

[MIT](LICENSE) — covers the Moon Internet source code.

---

*This app is a proxy tool. It provides no VPN service of its own — you bring your own
servers. You are responsible for how you use it and for the laws that apply to you.*
