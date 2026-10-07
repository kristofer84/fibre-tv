# Bandwidth and measuring it

## Wired LAN

The EdgeRouter X switch (MT7621/MT7530) can't do IGMP snooping, so while a stream is
running it reaches every wired port.

Measured on a wired host with no memberships, promiscuous capture, 5-second samples:

| state | unjoined multicast | throughput |
|---|---|---|
| nothing joined | ~1–3 packets | ~0 |
| only the Infocast stream | ~550 packets | ~0.2 Mbit/s |
| a TV channel being watched | ~6000 packets | ~12–18 Mbit/s |

- It only happens while something has joined a channel. Idle traffic is zero.
- Any LAN device triggers it, not just the set-top box.
- At 1 to 2% of gigabit, it isn't worth fixing.

## WiFi

The TP-Link Deco mesh snoops and converts multicast to unicast per client. So:

- clients that aren't watching are unaffected;
- a viewer receives the stream as unicast at its own data rate;
- a promiscuous WiFi capture won't show the stream. Only the Ethernet destination
  address reveals the conversion, since `tcpdump` shows `233.x.x.x` either way.

## Measurement pitfalls

- **Verify the capture works.** `tcpdump` without `sudo` reported zero traffic, which
  I first took to mean no flooding.
- **Interface counters miss it.** The NIC filters unjoined multicast before counting.
  Use promiscuous `tcpdump`.
- **Don't estimate rates from packet counts.** Assuming video-sized packets gave
  1.2 Mbit/s for the Infocast stream; the real figure is 0.18 Mbit/s.
- **Re-measure odd results.** One reading was 1.8x the others and never reproduced.
- **Leaves can be slow.** A stream sometimes kept flowing for minutes after leaving.
  Check the baseline before and after.
- **Joins must come from the LAN subnet.** Joins from other addresses were ignored.
