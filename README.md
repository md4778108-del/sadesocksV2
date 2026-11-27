# Sade Socks (v3.3)

Android 14 ready SOCKS5 VPN client (VpnService + hev-socks5-tunnel).
All IPv4 + IPv6 traffic and DNS go through your SOCKS5 server.

Build: push to GitHub, Actions tab, run "Build APK", download artifact "SadeSocks-apk".
Tip: paste a line like ip:port:user:pass into the first box and tap Connect.

v3.2 hardening: tunnel uses an ordinary private IPv4 address (no 198.18.x / fc00::1 signature),
no IPv6 address on the tunnel (IPv6 is captured and dropped, no leak), tunnel marked unmetered,
and a watchdog keeps the tunnel open (traffic blocked) if the engine dies.
For a real kill switch also enable Always-on VPN + "Block connections without VPN".

v3.3 UI: red header with Sade logo, green body/buttons, new app icon.
