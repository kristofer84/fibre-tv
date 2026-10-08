## Third-party components in the apk

The app's own code is MIT (see [LICENSE](LICENSE)), but the apk also carries the
following, and their licence texts are packaged inside the apk itself under
`assets/licenses/` so that any copy of the apk carries them.

### libVLC — `libvlc.so`, `libvlcjni.so`

[libVLC](https://www.videolan.org/vlc/libvlc.html) 3.x, taken from the
`org.videolan.android:libvlc-all:3.6.5` aar, is licensed under the
**GNU Lesser General Public License, version 2.1 or later**. Full text:
[`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt).

The aar ships no licence file of its own, which is why it is reproduced here.

The LGPL's relinking requirement is met by *dynamic* linking: the app contains no
libVLC code, only a reference to it, and loads it from `lib/armeabi-v7a/` and
`lib/arm64-v8a/` at runtime. Replacing those two `.so` files inside the apk with
a modified libVLC build is enough to run a modified library — no recompilation of
the app needed. Corresponding source is the aar above, or upstream:

<https://code.videolan.org/videolan/vlc-android>

### LLVM libc++ — `libc++_shared.so`

Bundled inside the same aar as libVLC's C++ runtime, licensed under the
**Apache License 2.0 with LLVM Exceptions**. Full text:
[`licenses/LLVM.txt`](licenses/LLVM.txt). Source: <https://llvm.org/>.

### Literata — the subtitle font

The reading font for the subtitle overlay, from
[Literata](https://github.com/googlefonts/literata), licensed under the **SIL Open
Font License 1.1**. Full text: [`licenses/OFL.txt`](licenses/OFL.txt). The OFL wants
the licence to travel with the font, so it is copied into the apk beside the others.

### Deliberately not bundled

The aar also carries VLC's `assets/lua/` and `assets/hrtfs/` trees. Neither is
extracted when building (`build-apk.sh` unpacks only `classes.jar` and the two
ABIs' `.so` files), because this app plays a bare MPEG-TS stream over RTP and
needs neither the Lua scripts nor the spatial-audio data.
