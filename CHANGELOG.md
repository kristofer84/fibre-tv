## Changelog — the Android TV app

The apk is attached to each [release](../../releases). The tag, the `versionName` in
`android/app/AndroidManifest.xml` and this file agree.

### 1.10

The Info line appears when it is asked for, not when the chrome happens to hide.

- The line was deliberately suppressed while the chrome was up, to keep it clear of the bars. That made
  the Info *button* - a control that lives in the chrome - appear to do nothing for four seconds, which
  is long enough to press it again and toggle it straight back off. It is shown immediately now, and
  brought to the front so the bars cannot cover it. The key and the toggle were never broken; the
  feedback was.
- Worth recording with the instrument rules: that diagnosis needed an **unfiltered** log with the buffer
  cleared first. The first attempt found nothing at all - not even the app's own lines - because the
  platform's `AWindowHandler` audit spam rotates the buffer within seconds, and a narrow filter on top
  of a rotated buffer answers a question about neither.

### 1.9

Two faults in the 1.7 profile rows, both reported from use rather than found by reading.

- **A profile row is now the gesture that activates that profile.** The rows replaced clickable chips,
  and the activation went with them: a tap landed in the name field and started editing it. Tapping
  the row - or selecting it with the D-pad and pressing centre - switches to that profile, and the row
  is the first focus stop, so Down still moves one profile at a time.
- **Renaming works.** `save()` looked for the name field one level too shallow: since the rows became
  name-over-provenance, child 0 is the two-line container rather than the field, so the loop skipped
  every row and a rename silently did nothing. A rename is now committed when Done is pressed, not
  only on Save, because a name is not the list.
- **Edit is the only way into a name.** A collapsed profile field is inert to touch as well as to the
  D-pad, so a tap on a profile row activates instead of editing. Collapsed *channel* fields keep
  tap-to-edit, because a phone needs that.
- Saving a profile no longer relabels an imported list as "edited on this device".

### 1.8

The Info line's resolution is real now. It read a dash because libVLC reports nothing here -
`getCurrentVideoTrack()` stays empty through a Vout view, and the media track table lists only the
first audio elementary stream - so the number comes from the stream itself.

- **A reader for the H.264 sequence parameter set**, beside the SDT and teletext readers and reading
  the same transport stream through the same `Ts` seam. Additive: no new connection type, and the
  device-verified video path is not involved.
- **The cropped size is the displayed size.** A 1080p stream is coded as 1920x1088 and cropped by
  eight lines, so ignoring `frame_cropping` reports 1920x1088 and is wrong - which is why TV4 is the
  control worth having, alongside SVT1 at 1280x720.
- **A dash until the probe lands, and a dash again on every channel change**, because showing the
  previous channel's size would be the guess the dash exists to prevent. A parse that produces an
  implausible size (outside 160-4096 by 120-2160) is discarded rather than displayed.
- The value is **pixels** and is only ever formatted into a string. It must not reach a text size, a
  padding or a layout parameter, where dp or sp would be meant - the units rule, on the one number
  most likely to invite the mistake.

### 1.7

One Down moves one channel. Every channel row held two D-pad focusable fields, so Down dropped into
text editing, where Down moves the caret - and the fields were unlabelled, which is why it read as "a
strange text edit". The same trap sat one row up in the profile list.

- **An Edit button per row**, in the same fixed-width action column the profile rows already used,
  with Remove beside it at the same height. The row geometry does not change: Edit is 48dp and Remove
  64dp inside the column Remove had to itself, and the dp conversion happens in one place.
- **The fields are touch-only** while a row is collapsed - `setFocusable(false)` with
  `setFocusableInTouchMode(true)`, deliberately not `setEnabled(false)`, which would grey the text and
  kill tap-to-edit on a phone. So Down moves between rows, Edit is the row's only vertical focus stop,
  and Right from Edit reaches Remove precisely because the fields are not focusable.
- **Edit becomes Done at the same bounds**, that row's fields become focusable and labelled (`Name`,
  `Stream URL`) and the focus moves into the name field; Done puts it all back. Same in-place
  two-state pattern as the delete confirmation, so nothing moves under the finger.
- **Remove now asks twice**: `Remove` -> `Tap again`. It is one press to the right of a focus stop and
  destructive, so without the confirmation this change would have made an accidental deletion easier,
  not only navigation faster.
- **A heading while a row is being edited**, on the panel's own message line: `Editing SVT1 HD - Name /
  Stream URL`. A hint cannot label a field that already has text, and a visible prefix would spend row
  geometry - so the line that already exists does the job instead, and the hints stay for the empty
  case, which is a freshly added row.
- **Removing a channel parks the focus on Add channel** rather than dropping it onto the panel, the
  same rule the profile list uses when a delete takes the focused row away.

- **An Info line on demand**, over the picture: `SVT1 Stockholm HD - — - 12.4 Mbit/s`.
  Toggled from a new Info button in the chrome - the D-pad route every remote has - and from the
  `KEYCODE_INFO` key where a remote carries one; the same toggle hides it. The **bandwidth** is the
  stall watchdog's own sampling of `TrafficStats`, one source of truth so the figure shown and the
  figure the watchdog acts on cannot disagree; measured against the line, SVT1 reads 12.4 Mbit/s and
  TV4 19.1, matching an independent measurement of the same stream. The **resolution** is read from
  libVLC and shows a dash until it reports one, because a wrong number is worse than no number - and
  on these streams, through this rendering path, it does not report one: `getCurrentVideoTrack()`
  stays empty, and the media track table lists only the first audio elementary stream.

- **Profile rows get the same treatment**, since the profile name field had the same problem one row
  up, and making it non-focusable without an Edit would have left Delete as the row's only stop.
- The panel is still an overlay over the player, and the membership drop in `onStop` is untouched.

### 1.6

The profile chips became a list. This is the answer to "the buttons could use another way to work as
well": not a dialog, and not the chip row made stable, but one row per profile.

- **One row per profile**, the name editable where it is, and `Delete` in the row's own action
  column. Renaming and deleting are actions on the row instead of a field and a button somewhere
  else. Renaming is per profile rather than per active profile, and the API follows: `rename` and
  `delete` take an id.
- **The active profile is marked with a view state** - `setSelected` on the row, which draws the
  same accent chip the playing channel uses in the chrome - so the marker cannot be confused with a
  name, and it is visible before anything is pressed.
- **Focus lands on the active profile's name field** when the panel opens, never on the panel
  itself. A container that fills the screen has a focus rectangle covering everything, so the first
  Down from it is a focus search from an impossible position - and on a list that is exactly how the
  first Down ends up on a Delete.
- **Adding cannot shuffle anything.** `New profile` sits above the list. Below it, every add would
  push it one row further down and the next tap in the same place would land on the new row's
  Delete.
- **Delete is still the in-place two-tap**, now per row, with the label swapping to `Tap again`
  inside a fixed-width column so it cannot grow under the finger.
- Deleting a profile nobody is watching does not re-tune the stream: the revision only changes when
  the active profile is the one that went away.

- **Each row shows what its list is**: the channel count and where it came from - built-in, edited
  on this device, or imported from a URL. The source was already stored per profile; with a list it
  finally had somewhere to live, and it replaces what a single "List in use" line could only say
  about one profile. A profile created from the built-in list stores no text of its own, so its
  count reads the asset - without that it showed "0 channels" while the app played seven.
- **Deleting the profile in use is a deliberate act with a named outcome.** The warning at the
  point of decision names the profile that takes over, and the rule behind it lives in one place
  (`Playlist.fallbackName`) so the warning cannot drift from what the delete does. The fallback is
  the first remaining profile, and the last profile cannot be deleted at all.

Verified on the TV: rows of 64dp for the two-line form with 12dp gaps and no overlap at density 480
with font_scale 1.3; focus on open on the active row's name field; adding two profiles left the add
row's bounds unchanged and kept the focus on it; renaming two rows and saving logged both renames and
survived a restart with the row's name and its delete description agreeing; arming a delete on the
profile in use named the fallback and the second tap logged `deleted Profile 2, now on LAN` with the
marker and the focus both moving to LAN; arming the delete on the last profile logged `refused to
delete the only profile` and left it in place; and a remote reaches the add row with two Ups from the
active row's name, the first being consumed by the text field.

- **Renamed to Fibre TV.** The app and the repository are now Fibre TV / `fibre-tv`. "the operator" stays
  in the prose as a factual descriptor of whose line this was reverse-engineered from - the line, the
  playlist header comments, the notes - and a not-affiliated notice now appears in the app's About
  screen and in the README. The package name `net.xcds.iptv`, the apk file name and the signing key
  are unchanged, so this installs as an update rather than as a different app. The signing
  certificate also keeps its old distinguished name for that reason - a certificate-details view
  is the one place the previous name still appears, and changing it would break every existing
  install's update path.

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
