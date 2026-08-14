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

## DPI bypass (Zapret mode)

| Component | License | Source |
|---|---|---|
| [zapret](https://github.com/bol-van/zapret) — `winws.exe` | see the note below | the engine itself |
| [zapret-discord-youtube](https://github.com/flowseal/zapret-discord-youtube) | MIT | the Windows build we ship, and its strategies |
| [WinDivert](https://github.com/basil00/Divert) | LGPL-3.0 or GPL-2.0, at your option | packet capture driver zapret needs |
| [Cygwin runtime](https://cygwin.com/) — `cygwin1.dll` | LGPL-3.0 | `winws.exe` is built against it |
| [byedpi](https://github.com/hufrea/byedpi) | MIT | the Android engine — zapret needs root, this does not |

MIT requires the copyright notice to travel with the software, so here it is,
as published in the release we redistribute:

```
MIT License

Copyright (c) 2016-2026 bol-van
Copyright (c) 2024-2026 Flowseal
```

```
MIT License

Copyright (c) 2023-2026 hufrea
```

**A note on zapret's own license.** [bol-van/zapret](https://github.com/bol-van/zapret)
publishes no license file. The MIT text above is the one shipped in Flowseal's
distribution, which names bol-van as a copyright holder; we redistribute that
release unmodified and pass its notice along with it. If the upstream author
states different terms, this is the section to correct.

byedpi is **compiled by us** from unmodified upstream sources — see
`android/build-byedpi.ps1` — and shipped as `libbyedpi.so`. It is an executable,
not a library: Android only makes files under `jniLibs` runnable, so a binary
that has to be run has to be named that way.

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
