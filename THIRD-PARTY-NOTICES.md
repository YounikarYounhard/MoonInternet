# Third-party notices

Moon Internet itself is MIT-licensed (see `LICENSE`). Release builds bundle the
components below, each under its own license. They run as **separate processes** —
Moon Internet does not link against them.

## Tunnel engines (shipped in `cores/`, not in this repository)

| Component | License | Source |
|---|---|---|
| [xray-core](https://github.com/XTLS/Xray-core) | MPL-2.0 | VLESS / VMess / Trojan / Shadowsocks, routing |
| [sing-box](https://github.com/SagerNet/sing-box) | GPL-3.0 | TUN, Hysteria2, WireGuard |
| [tun2socks](https://github.com/xjasonlyu/tun2socks) | GPL-3.0 | alternative TUN engine |
| [Wintun](https://www.wintun.net/) | Prebuilt, WireGuard LLC | TUN driver used by the engines |

Because sing-box and tun2socks are GPL-3.0, their **unmodified** binaries are
redistributed as an aggregate. Sources are available at the links above; we do not
patch them.

## Geo databases (downloaded at runtime, not bundled)

| Component | License | Source |
|---|---|---|
| [runetfreedom / russia-blocked-geosite](https://github.com/runetfreedom/russia-blocked-geosite) | MIT | domain rules |
| [runetfreedom / russia-blocked-geoip](https://github.com/runetfreedom/russia-blocked-geoip) | MIT | IP rules |

## .NET libraries (NuGet)

| Package | License |
|---|---|
| [CommunityToolkit.Mvvm](https://github.com/CommunityToolkit/dotnet) | MIT |
| [H.NotifyIcon.Wpf](https://github.com/HavenDV/H.NotifyIcon) | MIT |
| [QRCoder](https://github.com/codebude/QRCoder) | MIT |

## Icons

App artwork and UI assets in `assets/` belong to the Moon Internet project.
Interface glyphs come from the **Segoe MDL2 Assets** font shipped with Windows.
