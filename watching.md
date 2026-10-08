## Watching

### On the LAN

Any device on the LAN, wired or WiFi, can play the multicast directly.

```
#EXTM3U
#EXTINF:-1,SVT1 HD
rtp://@233.171.129.211:5500
#EXTINF:-1,SVT2 HD
rtp://@233.171.129.212:5500
#EXTINF:-1,TV4 HD
rtp://@233.171.129.213:5500
#EXTINF:-1,TV6
rtp://@233.184.48.28:5500
#EXTINF:-1,Kunskapskanalen HD
rtp://@233.184.48.101:5500
#EXTINF:-1,SVT Barn / SVT24
rtp://@233.184.48.100:5500
#EXTINF:-1,Lokal kanal (Öppna kanalen)
rtp://@234.213.112.43:5500
```

```sh
vlc channels.m3u8
vlc "rtp://@233.171.129.213:5500"          # one channel
```

### Over a VPN

Multicast doesn't pass through a routed VPN, so convert it to HTTP on a LAN machine.
**`udpxy`** does this (packaged in OpenWrt and Debian up to bookworm).

The relay should join only when a client connects. One that joins at startup pulls
the stream (and floods the LAN) around the clock. For that reason, don't use
`ffmpeg -listen 1`.

Playlist, where `10.0.0.1` is the VPN server's tunnel address:

```
#EXTM3U
#EXTINF:-1,SVT1 HD
http://10.0.0.1:4022/rtp/233.171.129.211:5500
#EXTINF:-1,SVT2 HD
http://10.0.0.1:4022/rtp/233.171.129.212:5500
#EXTINF:-1,TV4 HD
http://10.0.0.1:4022/rtp/233.171.129.213:5500
#EXTINF:-1,TV6
http://10.0.0.1:4022/rtp/233.184.48.28:5500
#EXTINF:-1,Kunskapskanalen HD
http://10.0.0.1:4022/rtp/233.184.48.101:5500
#EXTINF:-1,SVT Barn / SVT24
http://10.0.0.1:4022/rtp/233.184.48.100:5500
#EXTINF:-1,Lokal kanal (Öppna kanalen)
http://10.0.0.1:4022/rtp/234.213.112.43:5500
```

```sh
vlc channels-vpn.m3u8
```

If you write your own relay:

- Strip the 12-byte RTP header (first byte `0x80`) so the player gets plain MPEG-TS.
- Don't expose the port to the internet.

Each viewer gets a separate copy, so two viewers use about 36 Mbit/s of upload.
