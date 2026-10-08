## The set-top box

The key point: **the channel list isn't sent over the network.** The box fetches it
from a portal.

### The box

**Arris/Motorola VIP2853** (CommScope, OUI `f8:a0:97`). Its DHCP request uses vendor
class `the operator_VIP2853` and asks for option 43. On a normal LAN your router answers
instead, which is harmless.

Errors when provisioning fails:

```
Fel 2: DHCP-servern svarar inte.         <- no lease on the TV VLAN
Fel 4: Mjukvaran kunde inte startas.     <- lease ok, portal unreachable
```

### Bootstrap: Infocast

On VLAN 501 the operator multicasts a small config document to
`234.213.112.72:11111`, about 9 times a second (~0.18 Mbit/s, ~166 B/packet). The
protocol is **Infocast**:

```xml
<Infocast>
  <Channel address="234.213.112.72" port="11111" connection="Persistent">
    <Object name="cfg.portal.whitelisturls" fetch="Prefetch"/>
    <Object name="apps.portal.url.main" fetch="Prefetch"/>
    <Object name="apps.portal.url.test" fetch="Prefetch"/>
    <Object name="config.timezone" fetch="Prefetch"/>
    <Object name="sysconf.utctime" fetch="Prefetch"/>
    <Object name="notification.reboot" fetch="Prefetch"/>
```

It also includes a `<PortalURLs>` block naming the portal the box should use.

### Channel lineup

The box fetches its channel list from the portal over HTTPS, per device model
(`…/device/motorola_4/`) and probably per device. So there's nothing to capture on the
network, and without a box you have to work out the list by observation
([channels.md](channels.md)). It also explains reports of IPTV being tied to the box's
MAC address.

I don't cover querying the portal. It's the operator's infrastructure and answers per device.

### the operator Sweden vs Norway

The portal is `portal-stb.tv.telenor.se`, so this is the operator's platform. the operator
**Norway** uses VLAN 46 and IGMPv3; this line uses VLAN 501 and IGMPv2. Search results
often mix them up.
