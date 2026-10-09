## Changelog — the Android TV app

The apk is attached to each [release](../../releases). The tag, the `versionName` in
`android/app/AndroidManifest.xml` and this file agree.

### 1.5

Three fixes from installing v1.4 on a phone, which lays the panel out at a different scale
than a television does: 2340x1080 at a much higher density, letterboxed, so nothing that was
tuned to 1920x1080 at density 320 can be trusted.

- **One icon.** `SettingsActivity` carried both `LEANBACK_LAUNCHER` and `LAUNCHER`, which put
  a second icon in a phone's app list. It keeps the leanback entry only. The player's tile is
  the entry on both, and the Settings button inside the player is built before the channel
  list is read, so the route that always exists is unaffected.
- **The profile chips can no longer be covered by the field below them.** The row of chips,
  the row of buttons and the name field are now three fixed-height rows with real gaps, every
  row in the panel has an explicit height, and every label is single-line and ellipsised. No
  density, font scale or wrapping label can now change a row's height and produce an overlap.
  The previous fix was a 14dp margin, which was merely enough at one density.
- **Adding or removing a profile no longer moves anything.** The chips scroll sideways in
  their own row. `New profile` and `Delete this profile` live in a second row that is built
  once, so their positions are fixed. After an add or a remove the chip row's scroll position
  is restored, so the viewer keeps looking at what they were looking at. The confirmation
  label was shortened for the same reason: a label that grows changes a button's width under
  the finger.

The in-place two-tap delete was kept rather than replaced by a dialog. It was chosen with a
remote in mind, but it is also just tapping twice on a phone, it is one construction for both
hosts, and with fixed positions nothing moves under the finger any more. A dialog remains the
alternative if the two-tap turns out to be awkward on a phone.

### 1.4

Everything since v1.2, including the 1.3 version bump that was made in the tree but
never published, so it is rolled in here rather than left as a version nobody can
download.

**Editing the channel list.** Channels can be added, renamed, re-addressed and
removed in the app, and an m3u can be imported from a URL. The list is kept as m3u
text in the app's own preferences with the built-in list as the fallback, and
profiles let you keep more than one (a LAN list and a VPN list, say) and switch
between them.

**Settings as an overlay.** Settings used to be a second activity, which runs the
player's `onStop` - and `onStop` deliberately stops playback and drops the multicast
membership, because that is what stops a forgotten stream flooding the LAN. The
panel is now hosted by the player, over the picture, so playback, the membership and
the subtitle reader all keep running while the list is edited. The standalone
settings screen stays as the safety net for a playlist that does not play at all and
as the route that works on a phone; both hosts build the same panel.

**Names and subtitles over a relay, too.** Both came from joining the multicast
group, so a playlist pointing at an HTTP relay got neither. The sniffers now read a
packet source that is either a group or a relay, so channel names and teletext
subtitles work over the VPN as well. Playback over the multicast group is unchanged,
and the relay case opens one extra connection only while subtitles are on.

**Audio.** An audio delay in milliseconds against the picture, and a per-track trim
expressed as a percentage of libVLC's own volume, both remembered across channel
changes.

**A stall watchdog.** Ten seconds with no traffic on the input re-tunes, up to three
times, then says so on screen rather than looping. The signal is Android's own
per-uid receive counter, picked by measuring five candidates on the device.

**Looks.** The chrome was restyled for a television: accent pills with dark text when
focused, a centred settings column with equal margins above and below, two-line
channel rows with the name above the address, blocks with headings, and the active
profile and the playing channel marked with a view state rather than a character in
a label.

**Also.** The last channel is remembered across restarts; multi-digit channel entry
tunes on a 1.5 s pause rather than on the first digit; and an About screen lists the
licences, read out of the apk's own assets.

### 1.2 and earlier

- libVLC playback of the multicast group, with a `MulticastLock` and playback
  stopped in `onStop` so that leaving the app leaves the group
- DVB teletext subtitles, decoded from the transport stream
- channel names read from the stream's own SDT rather than out of the playlist
- touch support, and a chrome that hides itself when idle
