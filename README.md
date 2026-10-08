## the operator IPTV notes

Notes on running an **the operator** (the operator Sverige) fibre TV subscription on my own
hardware (EdgeRouter X, Raspberry Pi, VLC) instead of the ISP's router and set-top box.
Includes the channel list, which isn't published anywhere.

The most common pitfall: the router's IGMP proxy needs a VLAN interface as upstream,
otherwise it fails silently. See [router.md](router.md).

There is also a small **Android TV app** in [`android/`](android/README.md) that plays
the same channels on a Chromecast with Google TV, joining the multicast group with
libVLC instead of relaying anything.

| file | contents |
|---|---|
| [isp.md](isp.md) | the fibre line and which VLAN carries TV |
| [stb.md](stb.md) | the set-top box and how it gets its channel list |
| [channels.md](channels.md) | the seven channels and how to find them |
| [router.md](router.md) | EdgeOS configuration and common problems |
| [watching.md](watching.md) | playlists for the LAN and over a VPN |
| [measurements.md](measurements.md) | bandwidth, LAN and WiFi impact, measuring it |
| [android/README.md](android/README.md) | the Android TV app: what it does, and how it is built and installed |
| [android/THIRD-PARTY.md](android/THIRD-PARTY.md) | the licences of the code inside the apk |
| [CHANGELOG.md](CHANGELOG.md) | what changed in each version of the app |

### Scope

- Only for a subscription you already pay for. The basic tier here is seven
  free-to-air, unencrypted channels.
- Doesn't cover querying the operator's portal. That is the operator's infrastructure, not
  the subscriber's line.

### Caveats

- One line, Stockholm, 2026. VLAN IDs and addresses vary by operator and over time.
- Unofficial and unsupported by the ISP. Check your terms.
- Channel addresses will change if the operator reorganises. The method in
  [channels.md](channels.md) still applies.

### License

[CC BY 4.0](LICENSE) — use it, adapt it, credit it.
