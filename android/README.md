## the operator TV — Android TV app

A single-screen Android TV app that plays the seven channels in
[`../channels.m3u8`](../channels.m3u8). Built for a Chromecast with Google TV;
works on any Android TV or Fire TV device that is on the home LAN.

It joins the multicast group itself with **libVLC** — the same engine, and the
same `rtp://@group:port` URLs, that desktop VLC uses. So there is no relay, no
HTTP hop, no HLS and no transcoding anywhere: `channels.m3u8` is the only source
of truth, and it is staged into the apk at build time.

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
| `1`–`9` | tune that channel directly |
| back | hide the bar, or leave the app once it is hidden |

The bar focuses the *playing* channel when it appears, so the focus highlight is
also the "this one is live" marker, and it hides itself after a few seconds once
focus is no longer in it.

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

### Continuous integration

[`.github/workflows/android.yml`](../.github/workflows/android.yml) builds the apk
on GitHub's runners using the same `build.sh` and the same container — on every
push to `master`, on pull requests touching `android/`, and on demand. The apk is
uploaded as a workflow artifact; push a tag to get a release with it attached,
which *Downloader* on the TV can then install straight from the asset URL:

```sh
git tag v1.1 && git push --tags
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
playing).

`sabrina` is worth knowing about: it has a **32-bit userspace** —
`ro.product.cpu.abilist` is `armeabi-v7a,armeabi` and `abilist64` is empty — so
an arm64-only apk is refused outright with `INSTALL_FAILED_NO_MATCHING_ABIS`.
Both ABIs are therefore packaged, which is why the apk is 42 MB rather than 22.

Still untested: the other channels beyond the ones switched through, DVB
teletext subtitles, and `--network-caching` under a weak WiFi link.
