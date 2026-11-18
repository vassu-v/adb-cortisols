# ShieldVPN design

Goal: toggle-able, unrooted, device-wide blocking using Brave's adblock-rust.

## Architecture (DNS-sinkhole VPN, low memory, no packet forwarding)
- Kotlin `VpnService` creates a TUN with a fake DNS server address (10.111.222.2) and
  routes ONLY that /32 into the TUN. All other traffic bypasses the VPN untouched.
  The device's DNS is set to the fake address via `addDnsServer`.
- Read IPv4 UDP/53 packets from TUN, parse DNS question (bounds-checked), ask the engine.
  - blocked -> reply NXDOMAIN (A/AAAA) built in-process.
  - allowed -> forward the raw query to an upstream resolver (default 1.1.1.1) over a
    UDP socket protected with `VpnService.protect()`, relay the answer back into the TUN.
- Also route IPv6 fake DNS (fd00::2) if feasible; otherwise drop AAAA via NXDOMAIN-free path.
- Rust crate `core` wraps `adblock` crate (Engine from filter lists), exposes JNI:
  - `Engine.nativeCreate(rules: ByteArray): Long`
  - `Engine.nativeShouldBlock(handle: Long, host: String): Boolean`
  - `Engine.nativeDestroy(handle: Long)`
  Kotlin class: `app.shieldvpn.core.Engine` (package path matters for JNI symbol names).
  Host check = Request::new("https://<host>/", "https://<host>/", "other"); treat as
  third-party-agnostic; honour exceptions (@@) so allow rules win.
- Filter lists (EasyList, EasyPrivacy, uBlock/Brave lists, Peter Lowe) downloaded over HTTPS
  by the app, cached in app-private storage, size-capped, refreshed on demand.
- UI: single Activity with big on/off toggle, list status, last update, blocked counter.
  Persist the on/off state; optional start on boot is OFF by default.

## Constraints
- Device-class: low RAM. Cap list size, never hold unbounded buffers, bounded thread pool,
  per-query timeout, max DNS packet size 4096.
- minSdk 26, target/compile 28 installed locally (platform android-28 only).
- No analytics, no network calls except list download + upstream DNS. No logging of domains
  to disk.

## Review gates
Security reviewer (parsing, JNI, list download, permissions) and memory reviewer
(JNI lifetime, handle double-free, leaks, unbounded growth) must sign off before "done".
