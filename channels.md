# The channels

Seven channels, all RTP-encapsulated MPEG-TS on port 5500, all unencrypted:

| group | channel |
|---|---|
| `233.171.129.211:5500` | SVT1 |
| `233.171.129.212:5500` | SVT2 |
| `233.171.129.213:5500` | TV4 |
| `233.184.48.28:5500` | TV6 |
| `233.184.48.101:5500` | Kunskapskanalen |
| `233.184.48.100:5500` | SVT Barn / SVT24 |
| `234.213.112.43:5500` | Lokal kanal (Öppna kanalen) |

I couldn't find these addresses published anywhere. They may differ on other lines.

The addresses are GLOP (RFC 3180), where the middle octets are the operator's ASN:
`233.171.129.x` is AS43905 and `233.184.48.x` is AS47152. Lists online using `239.x`
addresses are for other operators.

## Finding them yourself

The set-top box sends an IGMP report each time it changes channel, so capture those.

1. On the router, capture IGMP on the LAN interface and step through every channel:

   ```sh
   tcpdump -i <lan-iface> -nn -e -tttt igmp
   ```

   Each `igmp v2 report <group>` is one channel.

2. The streams contain no channel names, so grab a few frames per group and identify
   the channel from its logo:

   ```sh
   ffmpeg -v error -i "rtp://@<group>:5500" -vf fps=1/5 -frames:v 6 -y /tmp/ch-%02d.jpg
   ```

3. Check that the number of groups matches the box's channel menu.

## Notes

- Bitrates are about 12 Mbit/s for 720p and 18 Mbit/s for 1080p.
- If the operator reorganises, redo the list using the steps above.
