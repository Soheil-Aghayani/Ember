# Windows tunnel: how it works

```
Servers of your subscription link (SubscriptionStore, ShareLinks)
  -> Xray-core (XrayRunner) runs the chosen server as a local SOCKS5 proxy on 127.0.0.1:10808
  -> whole-device mode: hev-socks5-tunnel on a Wintun adapter feeds all traffic into that SOCKS5 port
  -> the helper (helper/, native Rust; ember-tunnel.ps1 is the fallback) sets the routes and firewall rules
```

## Decisions

- **One local SOCKS5 port.** Xray listens on `127.0.0.1:10808`, the same place the tunnel engine expects a proxy, so proxy mode and whole-device mode share the connection code.
- **The server's IP is pinned.** Ember resolves the server's host to an IPv4 address and gives that address to Xray; the helper adds a `/32` route to it through the real gateway, so the tunnel never loops into itself. The kill switch allows exactly that address.
- **Kill switch** is an outbound block rule per active adapter that allows private ranges, the server address and the skip list. A DNS guard blocks port 53 except to the tunnel's resolver, and an IPv6 guard blocks public IPv6.
- **Speed tests run through one Xray process** with a local port per server (`NodeTester`), so all servers are measured in parallel for delay and the quickest are measured one by one for speed. They need Ember to be disconnected, otherwise the traffic would enter the tunnel.
- **No console window.** `xray.exe` is started with `CREATE_NO_WINDOW` (JNA), and a stale copy from Ember's own folder is ended at start-up.
- **The subscription link is a secret.** It is stored in `subscriptions.json` in the user's app data folder and never written to the log.

## Known limits

- IPv4 only; public IPv6 is blocked in whole-device mode.
- Hysteria2, TUIC and subscriptions that serve JSON instead of a link list are not read yet.
- UDP depends on the server: the local proxy accepts UDP and Xray forwards it when the server supports it. This has not been tested.
- Firewall rules made by the native helper survive a reboot; Ember notices and offers **Restore internet**.
