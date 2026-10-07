# The fibre line

The WAN is a trunk with one VLAN per service:

| service | VLAN | tagging |
|---|---|---|
| internet | none | untagged |
| TV | **501** | tagged |
| unknown | 591, 600 | tagged, almost no traffic |

Internet is untagged, so plain `address dhcp` on the WAN works. TV needs its own VLAN
interface. VLAN 501 is well known for the operator; support has confirmed it in forum threads.

## The ISP's router

The ISP router (Inteno here, Icotera i4882 elsewhere) puts its TV ports in VLAN 501:
tagged on the WAN, untagged to the box.

| VLAN | LAN port (to box) | WAN |
|---|---|---|
| 1 | off | off |
| 2 | off | untagged |
| **501** | **untagged** | **tagged** |

## Replacing it

You need a router that can:

1. create a VLAN interface on the WAN,
2. run an IGMP proxy between that interface and the LAN,
3. let the multicast through its firewall ([router.md](router.md)).

The set-top box doesn't need VLAN 501. It works on the normal LAN with a lease from
your router, and its IGMP joins are proxied like any other device's.
