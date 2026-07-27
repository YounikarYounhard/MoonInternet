# Changelog

## 0.9.0-beta

First public beta.

### Added
- Subscriptions: auto-update on the panel's own interval, traffic/expiry, welcome banner, HWID header
- **Offline cache** — subscriptions and servers stay visible with no internet
- Routing: own profile builder with a searchable browser over `geosite:` / `geoip:` tags
- Per-server actions: favourites, ping, config viewer, copy link, QR code
- Split tunnel by application (icons, `.lnk` / `.url` / Steam shortcuts resolved to the real `.exe`)
- Settings hub: Appearance, Connection, Routing, Subscriptions, Ping, Auto, Logs, About, Privacy Policy
- Log settings with a retention window
- Tray: server switch, ping, connection check, sorting
- Live speed and session traffic counters
- Tunnel tuning: TLS fragmentation, mux, sniffing, preferred IP, VPN DNS
- Local proxy hardening: SOCKS5 auth, block UDP, HTTP proxy auth

### Fixed
- **"Connected but no internet"** — the SYSTEM TUN helper outlived the app and kept routing into a
  dead xray port after a restart or mode switch. The tunnel is now always torn down first.
- **Conflict with HAPP** — the helper killed *every* `sing-box` / `tun2socks` process on the machine,
  including other clients'. It now only touches processes started from its own `cores\` folder.
- **Speed shown ~10× too high, traffic never reaching GB** — a .NET format-string bug (`:0.1` renders a
  literal `1`, not one decimal place)
- **WireGuard routes** — pointed at the tunnel's own address instead of being on-link, so Windows ignored them
- Engine hang caused by an undrained stdout pipe
- Pixelated app/tray icons; the tray icon now reflects connection state
- Icon cache is cleared on update, so the new icon appears immediately
