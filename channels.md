## The channels

Seven channels, all RTP-encapsulated MPEG-TS on port 5500, all unencrypted:

| group | service name, as broadcast | service ID |
|---|---|---|
| `233.171.129.211:5500` | SVT1 Stockholm HD | 3805 |
| `233.171.129.212:5500` | SVT2 Stockholm HD | 647 |
| `233.171.129.213:5500` | TV4 | 4006 |
| `233.184.48.28:5500` | TV6 HD (S) a | 7046 |
| `233.184.48.101:5500` | Kunskapskanalen HD | 7041 |
| `233.184.48.100:5500` | SVTB/SVT24 HD | 7020 |
| `234.213.112.43:5500` | `ÖK Stockholm HD` | 6410 |

Those names and service IDs are read from the stream's own SDT rather than from the
box's menu, so they are what the operator actually broadcasts. `TV6 HD (S) a` is
truncated on air, not here.

The Android TV app in [`android/`](android/) reads these at runtime, so its channel
bar shows the broadcast names rather than the playlist's shorter ones.

The last row is the awkward one: `ffprobe` reports no `service_name` for it at all,
because its service_type is not a standard one. A manual parse of its SDT reads the
name as `ÖK Stockholm HD`, but with a stray leading byte and no second opinion, so
treat that one as unconfirmed. Its provider name is `Intinor`, a
contribution-encoder vendor, so that feed does not come from the same headend as
the other six.

I couldn't find these addresses published anywhere. They may differ on other lines.

The addresses are GLOP (RFC 3180), where the middle octets are the operator's ASN:
`233.171.129.x` is AS43905 and `233.184.48.x` is AS47152. Lists online using `239.x`
addresses are for other operators.

### Finding them yourself

The set-top box sends an IGMP report each time it changes channel, so capture those.

1. On the router, capture IGMP on the LAN interface and step through every channel:

   ```sh
   tcpdump -i <lan-iface> -nn -e -tttt igmp
   ```

   Each `igmp v2 report <group>` is one channel.

2. Take the name from the stream itself. Every channel carries an SDT on PID
   `0x0011`, table `0x42`, with a `service_descriptor` (tag `0x48`), and stock
   ffmpeg surfaces it with no parsing at all:

   ```sh
   ffprobe -v error -show_entries program=program_id:program_tags \
       -of default=noprint_wrappers=1 -i "rtp://@<group>:5500"
   ```

   ```
   program_id=3805
   TAG:service_name=SVT1 Stockholm HD
   TAG:service_provider=
   ```

   `TAG:service_name` is the channel. An earlier version of this file claimed the
   streams carry no names at all — that was a bug in our own SDT parser, not a
   property of the stream. Only `234.213.112.43` fails to show a name this way,
   because its service_type is not a standard one, so that single group still
   needs an eyeball on the picture.

3. Check that the number of groups matches the box's channel menu.

### What else is in the mux

- **Two audio tracks per channel**, an MPEG-1 Layer II track and an AC-3 track,
  both stereo and both tagged `swe`. Nothing needs configuring to play either,
  but it does mean an audio-track choice is worth exposing in a player.
- **Video is H.264, and the line mixes formats.** SVT1 measured 1280x720 here; the
  PMT reports 1920x1080i25 elsewhere. Worth measuring per channel rather than
  assuming, since the bitrate in the table above follows from it.
- **DVB teletext subtitles**, three pages per channel. On SVT1 one of those pages
  is Danish, so a page list can look duplicated by language alone.
- **No EIT anywhere.** Every PID on several channels was scanned: no table
  `0x4E`/`0x4F`/`0x50`, not even present/following. A now/next guide cannot be fed
  from the mux. TDT (`0x70`, PID `0x0014`) appears only on Lokal kanal.
- **A CAT is present, but nothing is scrambled.** PID `0x0001` has a CA_descriptor
  pointing at CA_system_ID `0x0B00`, with an ECM carousel on PID `0x1389` (5001),
  yet no packet sets its transport_scrambling_control bit — the free tier really is
  clear. Anything that assumes a fixed PID list should expect that extra ECM PID.
- **`234.213.112.43` is the odd one out.** Its provider name is `Intinor` and its
  service_type is not a normal the operator one, i.e. it looks like a contribution feed
  rather than something off the headend. That fits it being the only group with no
  service name and the only one carrying a TDT.

### Notes

- Bitrates are about 12 Mbit/s for 720p and 18 Mbit/s for 1080p.
- If the operator reorganises, redo the list using the steps above.
