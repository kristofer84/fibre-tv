# Router configuration (EdgeOS / EdgeRouter X)

EdgeOS v3.0.1. `eth0` is the WAN, `switch0` the LAN bridge.

```
interfaces {
    ethernet eth0 {
        vif 501 {
            description "IPTV VLAN"
            mac 02:xx:xx:xx:xx:xx        # see problem 4 - do not skip this
            address dhcp
            dhcp-options {
                default-route no-update  # never take a default route from the TV VLAN
                name-server no-update    # nor a resolver
            }
            firewall {
                in    { name IPTV_IN }
                local { name IPTV_LOCAL }
            }
        }
    }
}
protocols {
    igmp-proxy {
        interface eth0.501 { role upstream   alt-subnet 0.0.0.0/0 threshold 1 }
        interface switch0  { role downstream alt-subnet 0.0.0.0/0 threshold 1 }
    }
}
firewall {
    name IPTV_IN    { default-action drop  rule 10 { action accept state established enable state related enable }
                                           rule 20 { action accept protocol igmp }
                                           rule 30 { action accept protocol udp destination address 224.0.0.0/4 } }
    name IPTV_LOCAL { ...same as IPTV_IN... }
}
```

`hwnat` stays enabled. Nothing else needs changing.

## Common problems

1. **The IGMP proxy upstream must be `eth0.501`, not `eth0`.** Otherwise igmpproxy
   fails at every boot with `There must be at least 1 Vif as upstream.` and nothing
   works. Check `systemctl is-active igmpproxy` first.

2. **Give the VLAN interface its own firewall ruleset.** Using the WAN ruleset drops
   the multicast. Symptom: `tcpdump` sees the frames but the socket receives nothing.

3. **Don't `save` while experimenting.** Saved test config comes back after a reboot.
   Use `commit`, test, then `save` only when done.

4. **Give the VLAN interface its own MAC (`02:…`).** It otherwise shares the WAN's MAC,
   and two DHCP clients with the same client ID can cost you the internet lease.

5. **`hwnat` can stay on.** Contrary to common advice, multicast works with hardware
   offload on this firmware, so there's no need to lose WAN throughput.

6. **The LAN gets flooded while someone watches.** The switch can't do IGMP snooping,
   so every wired port gets the stream (12 to 18 Mbit/s). See
   [measurements.md](measurements.md).

## Checking

```sh
show configuration commands | grep igmp        # upstream should read eth0.501
systemctl is-active igmpproxy                  # must be "active"
ip -4 addr show eth0.501                       # should hold a lease
```

End-to-end test from any LAN host:

```sh
ffprobe -v error -show_entries stream=codec_name,width,height \
    -i "rtp://@233.171.129.213:5500"
```
