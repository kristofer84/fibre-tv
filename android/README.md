## the operator TV — Android TV app

A single-screen Android TV app that plays the seven channels in
[`../channels.m3u8`](../channels.m3u8). Built for a Chromecast with Google TV;
works on any Android TV or Fire TV device that is on the home LAN.

The list is editable in the app and can be imported from a URL, several profiles can
be kept side by side, and a profile may point at an HTTP relay instead of the
multicast group. `channels.m3u8` is the list it starts from, not the only one it can
play - see [Settings](#settings).

It joins the multicast group itself with **libVLC** — the same engine, and the
same `rtp://@group:port` URLs, that desktop VLC uses. So there is no relay, no
HTTP hop, no HLS and no transcoding anywhere: `channels.m3u8` is the only source
of truth, and it is staged into the apk at build time. A relay playlist (`http://…`)
is played by the same libVLC path.

That is also what makes the audio work. The streams carry MP2 and AC3, which
browsers and cast receivers cannot decode — every browser-based approach needs
ffmpeg to transcode the audio to AAC first. libVLC decodes both natively, so the
app plays the stream exactly as it arrives.

### Using it

| remote | |
|---|---|
| D-pad up/down | show or hide the channel bar (also reveals it, then focuses the current channel) |
| D-pad left/right | move along the channel bar |
| D-pad centre | tune the highlighted channel |
| `1`–`9` | tune that channel directly; digits typed within 1.5 s tune together, so `12` is channel 12 |
| channel up/down | previous and next channel |
| subtitles | teletext subtitles, if the channel offers any |
| settings | open the settings panel over the playing picture |
| back | hide the bar, or leave the app once it is hidden |

The bar focuses the *playing* channel when it appears, so the focus highlight is
also the "this one is live" marker, and it hides itself after a few seconds once
focus is no longer in it.

### Settings

The Settings control in the chrome opens the panel **over the playing picture**. The
stream, the multicast membership and the subtitle reader all keep running while the
list is edited, and that is the point rather than a nicety: a separate settings
*activity* runs the player's `onStop`, which stops playback and drops the group - the
behaviour described below that keeps a forgotten stream off the LAN. So the panel is
hosted by the player, added to its own view tree, and the player's lifecycle never
changes. The standalone settings screen still exists and is still the route to use
when nothing plays at all, or on a phone; both hosts build the same panel.

In the panel:

- **Profiles.** A name plus a saved m3u list. `LAN` is created on first run from the
  built-in list; `New profile` copies the list that is on screen; switching saves and
  reloads around the switch. The active one is the filled chip.
- **Channels.** A name over its address, both editable, with `Remove` in its own
  column. Nothing is applied until `Save`, deliberately: a half-typed address should
  not retune the picture.
- **Import a playlist.** Fetches an m3u from a URL. The status code is checked, the
  body is capped at 1 MB, and the text has to parse to at least one channel - which is
  what catches a URL that returns an HTML error page with a 200. A failure changes
  nothing at all.
- **Reset to the built-in list** goes back to `channels.m3u8`; **About and licences**
  lists the third-party code inside the apk, read from its own assets.

### Audio delay, per-track trim, and a stall watchdog

The streams carry MP2 and AC3, and the two are rarely the same loudness - on this line
the AC-3 arrives several dB below the MP2 it is paired with. So:

- **Delay** shifts audio against the picture, in milliseconds. libVLC takes
  microseconds; the conversion happens in one place.
- **Per-track trim** is a percentage of libVLC's own volume, captured once as the 100%
  baseline. It is expressed that way because `setVolume`'s range is not documented
  anywhere reliable, and a percentage of a known baseline behaves predictably without
  needing to know it. Tracks are matched by **order**, not by codec name, because the
  media track table only lists the first audio elementary stream - worth knowing
  before changing it.
- **The watchdog.** Ten seconds with no traffic on the input re-tunes, up to three
  times, then says so on screen instead of looping. The signal is
  `TrafficStats.getUidRxBytes`, and it is worth recording why, because the obvious
  candidates do not work: `getTime()` and `getPosition()` both report 0 for a live
  multicast, `IMedia.getStats()` freezes at one value while the picture is perfect,
  and `/proc/net/dev` is not readable by an app. What the counter cannot see is bytes
  still arriving while nothing decodes - an operator re-mux - and there is no working
  picture counter to fall back on (`displayedPictures` stays 0 even while playing).
  That limit is documented rather than papered over.

### Two Android-specific things

Both are the difference between "works like VLC on a desktop" and "mysteriously
never gets any packets":

- **`WifiManager.MulticastLock`.** Without one, the WiFi stack drops the group.
  libVLC does not take one for you — VLC for Android takes it in its own UI code,
  which is not part of `libvlc-all`.
- **Playback is stopped in `onStop`.** Leaving the app actually leaves the group,
  rather than pulling 12–18 Mbit/s forever with nobody watching.

### Building

```sh
./build.sh            # -> app/build/iptv-tv.apk (~22 MB, arm64-v8a)
./build.sh clean      # drop build output and the toolchain cache
```

Everything compiles **inside a container** (`Dockerfile`); the host is only used
to `curl` the pinned toolchain into `toolchain/` and to run `docker`. That is
deliberate, because Android's build tools are mostly x86-64 binaries and this
builds on an arm64 host:

| need | how it is met |
|---|---|
| `aapt2`, `zipalign` | **unused** — both are x86-64 only in build-tools. Debian's native `aapt` (v1) and `zipalign` stand in. |
| `d8` | R8 from Google's Maven — a plain jar, so architecture-independent |
| `javac`, `keytool`, `apksigner` | Java, so architecture-independent |

Four traps are worth knowing about if you bump a version:

1. **Compile against API 34, not 35.** Debian's `aapt` is built from Android 14
   sources and cannot read the compact resource entries that API 35's
   `android.jar` uses. It reports `Entry offset at index N points outside the
   Type's boundaries` for every entry, then fails to resolve every `android:`
   attribute in the manifest. API 34 links cleanly.
2. **`d8` must come from R8 8.3 or newer.** build-tools 34 ships R8 8.2.2, which
   predates JDK 21 and dies on *any* class file — including a trivial hello
   world — with a null-name `NullPointerException`. Debian trixie has no JDK 17
   to fall back to. Skipping build-tools also drops a 61 MB download.
3. **libVLC's `classes.jar` must be a d8 *input*, never `--classpath`.** As a
   classpath it means "these types live elsewhere" and they are left out of the
   apk — the activity then cannot load at all, failing with
   `ClassNotFoundException` because its interface `IVLCVout$Callback` does not
   resolve. The build prints a `dex contains:` line to catch this.
4. **`d8` warns about `androidx.lifecycle.Observer` in libVLC's jar.** Expected, and
   not worth chasing. It comes from libVLC's `DisplayManager`, a class this app
   never touches: it attaches video through `IVLCVout.setVideoView`, which avoids
   `DisplayManager` and therefore its androidx dependency altogether. The class is
   never loaded, so the missing type is never resolved at runtime. It cannot be
   pruned out either — libVLC's JNI looks its Java classes up by name, so
   tree-shaking the jar would break playback.

### Where the channel names come from

The bar shows what each stream calls itself rather than what the playlist calls it:
`SVT1 Stockholm HD` instead of `SVT1 HD`, and `TV6 HD (S) a` with the operator's own
truncation. The playlist name stays as the fallback, so an untuned channel, or one
whose descriptor the parser will not accept, still shows something sensible.

Two things here are worth knowing before changing it:

- **libVLC cannot supply this.** Its media title for these streams *is* populated,
  but the value is the MRL - `rtp://233.171.129.211:5500`. An earlier version of
  this app used it as a channel name and put that URL on the bar. `Sdt` parses PID
  `0x0011` directly instead: table `0x42`, `service_descriptor` tag `0x48`.
- **Byte reads must be masked.** The RTP header check started as
  `(data[0] >> 6) == 2`, which is correct in Python but never true in Java: `0x80`
  is a *signed* byte, so `-128 >> 6` is `-2`. It silently found 81 transport
  packets in 4479 datagrams - no SDT, no names. `rtpHeaderLength` masks with `0xc0`
  now, and that was the only unmasked byte read in the file.

The probe joins the group briefly, once per channel, and leaves immediately. It is
best-effort and logged, so a channel that stops naming itself shows up in logcat.

Both sniffers read their bytes through one seam, `Ts`, so a channel that is an HTTP
relay gets its name and its subtitles too - the difference between working on the LAN
and working over the VPN as well. Multicast is the original path and is unchanged:
join the group, read RTP datagrams. HTTP is additive: one GET and a packetiser that
finds packet alignment from the sync byte rather than assuming it, so neither parser
had to change. Cost was the constraint: the probe is one short connection that closes
immediately, and the subtitle reader exists only while subtitles are on - measured
with `ss` on the relay, one connection with subtitles off and two with them on.
`234.213.112.43` (Lokal kanal) is the one that never does: it is the odd feed out,
with a non-standard service_type and an `Intinor` provider, and `ffprobe` cannot
read a name from it either.

### Continuous integration

[`.github/workflows/android.yml`](../.github/workflows/android.yml) builds the apk
on GitHub's runners using the same `build.sh` and the same container — on every
push to `master`, on pull requests touching `android/`, and on demand. The apk is
uploaded as a workflow artifact; push a tag to get a release with it attached,
which *Downloader* on the TV can then install straight from the asset URL:

```sh
git tag v1.4 && git push --tags
```

The toolchain is cached between runs, keyed on `build.sh`, since that is where the
versions are pinned.

**Signing.** Set these repository secrets and CI signs with the *same* key as your
local builds:

```sh
base64 -w0 android/keys/iptv-tv.jks   # -> ANDROID_KEYSTORE_BASE64
```

plus `KEYSTORE_PASSWORD` and `KEY_PASSWORD`. Without them each run generates a
throwaway key: the apk still installs, but Android will refuse to upgrade an
existing install signed by a different key, so a release wants the secret.

### Comparing two builds

**Compare `classes.dex`'s md5, never the apk's.** Four independent build paths -
native arm64, emulated amd64, native x86_64 and GitHub Actions - produce a
byte-identical dex, and the build prints that hash, so a CI log can be checked
against a local build without downloading anything.

The apk hash will never match, and the reason is worth knowing because "zip
timestamps" is only half of it. Every entry's data, size and CRC-32 are identical
and the concatenated decompressed payloads hash the same; the differences are 589
bytes of metadata - DOS timestamps in each entry header and in the central
directory, plus a UT extra field. But apksigner's v2/v3 signing block covers the
whole archive, so those timestamps propagate into the signature itself, which is
why the file hash moves while the contents do not.

Byte-identical apks would need normalised mtimes (`SOURCE_DATE_EPOCH`, or
`touch -t 198001010000`) and `zip -X` to drop the extra fields; PKCS#1 v1.5
signing is deterministic, so the signature would settle too. Not worth doing here -
the dex is the number that identifies the code.

### Installing

The apk is signed with a local key in `keys/` (created on first build). Keep it:
losing it means uninstalling and reinstalling to upgrade.

Over the network, with developer options → network debugging enabled on the TV:

```sh
adb pair <tv-ip>:<pair-port>          # code shown on the TV
adb connect <tv-ip>:<port>
adb install -r android/app/build/iptv-tv.apk
```

Without adb, upload the apk somewhere the TV can reach (a GitHub release works)
and use the *Downloader* app to fetch and install it. The app appears in the TV
launcher as **the operator TV**.

Two things about installing on a real device:

- **Use `--no-incremental`.** Leave it off and adb uses an incremental install,
  which on the Chromecast produced an apk whose dex would not load — the app
  crashed with the exact same `ClassNotFoundException` as a genuinely missing
  class, which is a confusing hour to spend. Note also that each failed
  `adb connect` leaves an `offline` transport behind, and `adb install` then
  fails with "more than one device/emulator"; `adb disconnect` them.
- **The wireless-debugging pair port is not the connect port.** Pairing happens
  on a random port shown only in the pairing dialog; the connect port is a
  *different* random port, advertised over mDNS as `_adb-tls-connect._tcp`.
  `adb pair <ip>:<pair-port> <code>` then `adb connect <ip>:<connect-port>`.

### Licences

The app is **MIT** ([LICENSE](LICENSE)); the notes in the rest of this repository
stay under Creative Commons Attribution 4.0 ([../LICENSE](../LICENSE)).

The apk bundles third-party code with its own terms, and because it is a binary
that gets handed around, those texts are packaged *inside* it under
`assets/licenses/` rather than merely mentioned here:

| component | licence |
|---|---|
| libVLC — `libvlc.so`, `libvlcjni.so` | LGPL-2.1-or-later |
| Literata — the subtitle font | SIL OFL 1.1 |
| LLVM libc++ — `libc++_shared.so` | Apache-2.0 with LLVM exceptions |
| this app | MIT |

libVLC's aar ships no licence file of its own, which is why the text lives here
and is copied in at build time. [THIRD-PARTY.md](THIRD-PARTY.md) covers the rest,
including why dynamic linking satisfies the LGPL's relinking requirement.

### Verified on a Chromecast with Google TV (4K)

Built, installed and run on a **Chromecast with Google TV (4K)**, Android 14,
product `sabrina`. Confirmed working: video plays, channel switching from the
remote reaches the right multicast group, and leaving the app drops the
membership (1 stray packet after HOME, against ~181 in the same window while
playing). Confirmed since: DVB teletext subtitles decoded and drawn over both
multicast and a relay; channel names read from the stream over both; the settings
panel holding the remote while the stream keeps running behind it; audio delay and
per-track trims; and the watchdog re-tuning a stalled input three times and then
saying so.

`sabrina` is worth knowing about: it has a **32-bit userspace** —
`ro.product.cpu.abilist` is `armeabi-v7a,armeabi` and `abilist64` is empty — so
an arm64-only apk is refused outright with `INSTALL_FAILED_NO_MATCHING_ABIS`.
Both ABIs are therefore packaged, which is why the apk is 42 MB rather than 22.

Still untested: the channels beyond the ones switched through, and
`--network-caching` under a weak WiFi link.
