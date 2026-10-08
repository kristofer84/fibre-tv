package net.xcds.iptv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The whole app: one activity that joins the channel's multicast group with libVLC
 * and plays it. There is no relay and no transcoding - this is the same engine and
 * the same rtp://@group:port URLs that VLC uses on a desktop, which is what makes
 * the AC3 and MP2 audio work.
 *
 * The streams carry two audio tracks (MPEG-1 Layer II and AC-3, both Swedish) and
 * three teletext subtitle pages, so both are selectable from the channel bar. The
 * audio tracks are handed to libVLC; the subtitle pages are not, because libVLC
 * draws teletext as a 40x25 grid upscaled to the panel. Teletext decodes those
 * pages here and they are drawn as text with a reading font instead.
 *
 * The things that make it behave on Android rather than just on a desktop:
 *
 *   - a WifiManager.MulticastLock, without which the WiFi stack drops the group
 *     (libVLC does not take one for you);
 *   - playback is stopped in onStop, so leaving the app actually leaves the group
 *     instead of pulling 12-18 Mbit/s forever while nobody is watching;
 *   - the teletext reader joins that same group and is stopped for the same reason:
 *     on OFF, on a retune and in onStop, so it is never left pulling the group
 *     after the picture has gone;
 *   - attachViews/detachViews are made idempotent, because the system can call
 *     onStart on a vout that is already attached right after an in-place update,
 *     and libVLC throws IllegalStateException for that.
 */
public class TvActivity extends Activity implements IVLCVout.Callback {

    /** How long the channel bar and the status card stay up on their own. */
    private static final long IDLE_HIDE_MS = 4000;

    private static final String ASSET = "channels.m3u8";

    private static final String TAG = "iptv";

    /**
     * libvlc_media_track_type_t: audio is 0, video is 1, text is 2. Confirmed on
     * device - the audio entries in the media track table report type=0 and the
     * teletext ones type=2.
     */
    private static final int TRACK_TYPE_AUDIO = 0;

    /**
     * How long to look for a channel's subtitle pages when it is tuned, and how
     * often to check whether they have turned up. The pages are listed in the
     * stream's PMT, so they cannot be known without reading the transport stream
     * once - but the reader is only worth running while someone wants subtitles, so
     * this is one short look per channel rather than a permanent second socket.
     */
    private static final long SUBTITLE_PROBE_MS = 5000;
    private static final long SUBTITLE_POLL_MS = 250;

    /**
     * Worth recording because it looks like it should work: libVLC does populate a
     * media title for these streams (meta id 0), but the value is the MRL -
     * "rtp://233.171.129.211:5500" - and not the SDT service name. An earlier
     * version of this app used it as a channel name and put that URL on the bar.
     * The stream's own name is read from Sdt instead.
     */

    private LibVLC libVLC;
    private MediaPlayer player;
    private IVLCVout vout;

    private SurfaceView surface;
    private SurfaceView subtitlesSurface;
    private TextView card;
    private HorizontalScrollView barScroll;
    private LinearLayout bar;
    private LinearLayout controls;
    private HorizontalScrollView controlsScroll;
    private Button audioButton;
    private Button subsButton;
    private TextView subtitleView;

    private final List<Channels.Channel> channels = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();

    /**
     * Service names taken from each stream's own SDT, by channel index, once seen.
     * Empty until a channel has been tuned at least once. The m3u name is the
     * fallback, so the bar shows something immediately and improves when the
     * stream names itself.
     */
    private String[] sdtNames;

    /**
     * The subtitle pages each channel offers, by channel index, once discovered.
     * Null per channel until the probe has read that channel's PMT, which is why
     * the button says "scanning" rather than "off" while it is still looking.
     */
    private List<List<Teletext.Page>> teletextPages;

    /** 0 is off; otherwise 1 + the index into this channel's page list. */
    private int subsWanted;

    /**
     * The reader for the current channel, or null. Invariant: it is never left
     * running for a channel that is no longer tuned, because a reader joins that
     * channel's group.
     */
    private Teletext reader;
    private Thread readerThread;

    /**
     * Bumped whenever the reader is started or stopped. The probe polls on the UI
     * thread, and without this a poll that was already queued could stop a reader
     * the viewer had started in the meantime - which would look like subtitles
     * switching themselves off.
     */
    private int readerGeneration;
    private boolean subtitleProbeRunning;

    /** Set when the button is pressed before this channel's pages are known. */
    private boolean selectFirstWhenFound;

    // Built in onCreate for the same reason as idleHide below: javac emits no
    // enclosing-method for anonymous classes declared in a field initialiser, and
    // d8 (R8 8.2.2, from build-tools 34) crashes on that with a null-name NPE.
    private Teletext.Listener subtitleListener;

    private int current = -1;

    /**
     * Track choice, remembered across channel changes. Both are ordinals rather
     * than track ids, because the ids are per-stream (they are transport PIDs) but
     * the ordering is the same on every channel here.
     */
    private int audioWanted;
    private boolean applyWanted;

    /** Set once Playing has fired for the current tuning, cleared on every retune. */
    private boolean started;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Created in onCreate rather than as a field initialiser on purpose: javac
    // emits no enclosing-method for anonymous classes declared in an initialiser,
    // and d8 (R8 8.2.2, from build-tools 34) crashes on that with a null-name NPE.
    private Runnable idleHide;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        idleHide = new Runnable() {
            @Override
            public void run() {
                if (barScroll.hasFocus() || bar.hasFocus()
                        || controlsScroll.hasFocus() || controls.hasFocus()) {
                    return;                 // in use; hiding it would kill the D-pad
                }
                hideChrome();
            }
        };

        // The reader calls this from its own thread, so the overlay is only ever
        // touched on the UI thread. Lines are joined here rather than in the
        // decoder because the TextView is the only thing that knows how they are
        // laid out; an empty list means there is nothing to show, which is also
        // what a subtitle page with no dialogue produces.
        subtitleListener = new Teletext.Listener() {
            @Override
            public void onSubtitles(List<String> lines, String label) {
                final List<String> copy = new ArrayList<>(lines);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showSubtitles(copy);
                    }
                });
            }
        };

        buildUi();

        channels.addAll(Channels.fromAssets(this, ASSET));
        if (channels.isEmpty()) {
            card.setText("no channels in assets/" + ASSET);
            return;
        }
        buildChannelBar();
        sdtNames = new String[channels.size()];
        teletextPages = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            teletextPages.add(null);       // filled in by the probe, per channel
        }

        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=800");
        options.add("--no-video-title-show");
        libVLC = new LibVLC(this, options);

        player = new MediaPlayer(libVLC);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                onPlayerEvent(event);
            }
        });

        vout = player.getVLCVout();
        vout.setVideoView(surface);
        vout.setSubtitlesView(subtitlesSurface);
        vout.addCallback(this);

        surface.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                reportWindowSize();
            }
        });

        current = 0;
        updateBar();
        showCard(title() + "\nready");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (vout == null) {
            return;                     // channel list failed to load; nothing to play
        }
        // Re-set the view and only attach if not already attached. Without the
        // guard, an in-place apk update makes the next launch die in onStart with
        // "already attached or video view not configured" from AWindow.
        vout.setVideoView(surface);
        vout.setSubtitlesView(subtitlesSurface);
        if (!vout.areViewsAttached()) {
            vout.attachViews();
        } else {
            Log.i(TAG, "onStart: views were already attached");
        }
        tune(current);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player == null || vout == null) {
            return;
        }
        // Stop rather than pause: this is what drops the multicast membership.
        player.stop();
        // ...and the same for the teletext reader, which joins the same group.
        stopReader();
        if (vout.areViewsAttached()) {
            vout.detachViews();
        }
        releaseLocks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(idleHide);
        stopReader();
        if (vout != null) {
            vout.removeCallback(this);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }

    // ------------------------------------------------------------------ the UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Teletext renders into a layer of its own, above the video. VLC's own
        // layout does the same two things, and both matter:
        //   setZOrderMediaOverlay - otherwise the subtitles layer can sit behind the
        //     video surface and show nothing;
        //   setFormat(TRANSLUCENT) - a SurfaceView's surface is opaque by default,
        //     so this full-screen layer would otherwise black out the video.
        // Being a media overlay also keeps it below the window, so the channel bar
        // and the status card still draw over it.
        // Nothing draws here any more: the app no longer selects a libVLC SPU track,
        // because the subtitles are decoded by Teletext and drawn as text in the
        // overlay below. Left in place rather than removed - it costs an empty
        // transparent view, and taking it out would mean touching the attach/detach
        // path that was just fixed.
        subtitlesSurface = new SurfaceView(this);
        subtitlesSurface.setZOrderMediaOverlay(true);
        subtitlesSurface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        root.addView(subtitlesSurface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // The subtitle overlay. Added after the video surfaces so it draws over
        // them, and before the card and the bar so those still draw over it: the
        // chrome has to stay readable while subtitles are on.
        subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        subtitleView.setLineSpacing(dp(4), 1.0f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setPadding(dp(20), dp(10), dp(20), dp(10));
        // Near-opaque rather than solid: white on black is what was asked for, and
        // a hint of picture through the box keeps it from looking like a hole.
        subtitleView.setBackgroundColor(0xE0000000);
        subtitleView.setMaxWidth(dp(900));
        applyReadingFont(subtitleView);
        subtitleView.setVisibility(View.GONE);
        FrameLayout.LayoutParams subtitleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // Clears both rows of chrome so the text never sits under the channel bar
        // while it is up. The chrome hides itself after four seconds; this is the
        // position that works while it is showing, which is the case that matters.
        subtitleParams.setMargins(dp(32), 0, dp(32), dp(140));
        root.addView(subtitleView, subtitleParams);

        card = new TextView(this);
        card.setTextColor(Color.WHITE);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.setBackgroundColor(0xC0000000);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.TOP | Gravity.START;
        cardParams.setMargins(dp(32), dp(32), 0, 0);
        root.addView(card, cardParams);

        // Two rows at the bottom: the track controls, then the channels. Keeping
        // them in separate rows means the D-pad can move between them and a long
        // channel name cannot scroll the controls out of reach.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x80000000);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        audioButton = new Button(this);
        audioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAudio();
            }
        });
        audioButton.setOnFocusChangeListener(focusWatcher);
        subsButton = new Button(this);
        subsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSubs();
            }
        });
        subsButton.setOnFocusChangeListener(focusWatcher);
        controls.addView(audioButton, controlParams());
        controls.addView(subsButton, controlParams());

        controlsScroll = new HorizontalScrollView(this);
        controlsScroll.setHorizontalScrollBarEnabled(false);
        // Not focusable on purpose: HorizontalScrollView turns focusability on in
        // its own constructor, and as a focusable wrapper it swallows the D-pad, so
        // focus search never reaches the buttons inside it.
        controlsScroll.setFocusable(false);
        controlsScroll.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setFocusable(false);
        barScroll.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        bottom.addView(controlsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bottom.addView(barScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(dp(32), 0, dp(32), dp(32));
        root.addView(bottom, bottomParams);

        setContentView(root);
        surface.requestFocus();
    }

    private final View.OnFocusChangeListener focusWatcher = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                restartIdleTimer();
            }
        }
    };

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        return p;
    }

    private void buildChannelBar() {
        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            Button button = new Button(this);
            // No custom background anywhere: the theme's focus highlight is the
            // only thing telling a viewer with a remote what is selected.
            button.setText((i + 1) + ".  " + channels.get(i).name);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tune(index);
                }
            });
            button.setOnFocusChangeListener(focusWatcher);
            bar.addView(button, controlParams());
            buttons.add(button);
        }
    }

    private void updateBar() {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setSelected(i == current);
            buttons.get(i).setText((i + 1) + ".  " + nameOf(i));
        }
    }

    /** What the stream calls itself once known, otherwise what the playlist calls it. */
    private String nameOf(int index) {
        if (sdtNames != null && index >= 0 && index < sdtNames.length && sdtNames[index] != null) {
            return sdtNames[index];
        }
        return index >= 0 && index < channels.size() ? channels.get(index).name : "?";
    }

    /**
     * Ask the channel what it calls itself, off the UI thread and once per channel.
     * Best-effort by design: any failure leaves the playlist's name in place, so the
     * bar can neither go empty nor show something wrong.
     */
    private void probeServiceName(final int index) {
        if (sdtNames == null || index < 0 || index >= sdtNames.length || sdtNames[index] != null) {
            return;
        }
        final Channels.Channel channel = channels.get(index);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String name = Sdt.serviceName(channel.group(), channel.port(), 4000);
                if (name == null || name.isEmpty()) {
                    Log.i(TAG, "sdt: channel " + (index + 1) + " gave no service name");
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        sdtNames[index] = name;
                        Log.i(TAG, "channel " + (index + 1) + " names itself '" + name + "'");
                        if (index == current) {
                            updateBar();
                            showCard(title());
                        }
                    }
                });
            }
        }, "sdt-" + (index + 1)).start();
    }

    private void showChrome() {
        card.setVisibility(View.VISIBLE);
        controlsScroll.setVisibility(View.VISIBLE);
        barScroll.setVisibility(View.VISIBLE);
        restartIdleTimer();
    }

    private void showChromeFocused() {
        showChrome();
        if (current >= 0 && current < buttons.size()) {
            // Focusing the playing channel doubles as the "this is live" marker,
            // and the scroll view brings it into view by itself.
            buttons.get(current).requestFocus();
        }
    }

    private void hideChrome() {
        ui.removeCallbacks(idleHide);
        controlsScroll.setVisibility(View.GONE);
        barScroll.setVisibility(View.GONE);
        card.setVisibility(View.GONE);
        surface.requestFocus();
    }

    private void restartIdleTimer() {
        ui.removeCallbacks(idleHide);
        ui.postDelayed(idleHide, IDLE_HIDE_MS);
    }

    private void showCard(String text) {
        card.setText(text);
        card.setVisibility(View.VISIBLE);
    }

    private boolean chromeVisible() {
        return barScroll.getVisibility() == View.VISIBLE;
    }

    private boolean focusInControls() {
        View focused = getCurrentFocus();
        return focused == audioButton || focused == subsButton;
    }

    // ------------------------------------------------------------- tuning it in

    /** Tune to a channel by index, wrapping around. Safe to call for the current one. */
    private void tune(int index) {
        if (channels.isEmpty()) {
            return;
        }
        int count = channels.size();
        index = ((index % count) + count) % count;
        current = index;
        Channels.Channel channel = channels.get(index);

        showCard(title() + "\njoining " + channel.group() + ", please wait");
        updateBar();
        applyWanted = true;
        started = false;
        // Subtitles start off on every channel, and any reader for the previous
        // channel stops here. The audio choice is remembered, because AC-3 is a
        // preference; a subtitle page is something you turn on for one programme,
        // and the reader is joined to the group of the channel it was started for.
        // The pages this channel offers are looked up in the background so the
        // button can name them when it is pressed.
        subsWanted = 0;
        selectFirstWhenFound = false;
        stopReader();
        showSubtitles(Collections.<String>emptyList());
        updateTrackButtons();
        probeServiceName(index);
        discoverSubtitlePages(index, false);
        updateSubsButton();

        Media media = new Media(libVLC, Uri.parse(channel.url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=800");
        player.setMedia(media);
        media.release();

        acquireLocks();
        player.play();
    }

    private String title() {
        if (current < 0 || current >= channels.size()) {
            return "the operator TV";
        }
        return (current + 1) + "   " + nameOf(current);
    }

    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                started = true;
                showCard(title());
                restartIdleTimer();
                onTracksAvailable("playing");
                break;
            case MediaPlayer.Event.Opening:
                showCard(title() + "\njoining, please wait");
                break;
            case MediaPlayer.Event.Buffering:
                // libVLC fires this repeatedly, including at 100% during ordinary
                // playback, so it is only worth showing before the stream starts.
                // Shown unconditionally it pins the card to "buffering" forever,
                // because each event re-shows it and nothing hides it again.
                if (!started) {
                    showCard(title() + "\nbuffering");
                }
                break;
            case MediaPlayer.Event.EncounteredError:
                // Most likely the group is not being delivered: no IGMP proxy, a
                // re-organised line, or a WiFi link that dropped the membership.
                showCard(title() + "\ncannot join " + channels.get(current).group()
                        + "\n(press the channel number to retry)");
                ui.removeCallbacks(idleHide);
                break;
            case MediaPlayer.Event.EndReached:
                showCard(title() + "\nstream ended");
                break;
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESSelected:
            case MediaPlayer.Event.ESDeleted:
                onTracksAvailable("es");
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- the tracks

    /**
     * Called whenever the track lists may have changed. Applies a remembered audio
     * choice once per tuning, then refreshes the control buttons.
     *
     * There is no subtitle half any more: the teletext pages are decoded by Teletext
     * rather than by libVLC, so there is no SPU track to select - and selecting one
     * as well would draw libVLC's teletext bitmap underneath our own text.
     */
    private void onTracksAvailable(String when) {
        List<MediaPlayer.TrackDescription> audio = audioTracks();
        if (audio.isEmpty()) {
            return;                     // nothing parsed yet
        }

        if (applyWanted) {
            applyWanted = false;
            if (audioWanted > 0) {
                player.setAudioTrack(audio.get(audioWanted % audio.size()).id);
                Log.i(TAG, "applied audio ordinal " + audioWanted);
            }
        }

        updateTrackButtons();
    }

    /** The selectable audio tracks: libVLC always offers "Disable" as id -1 first. */
    private List<MediaPlayer.TrackDescription> audioTracks() {
        return withoutDisable(player.getAudioTracks());
    }

    private static List<MediaPlayer.TrackDescription> withoutDisable(
            MediaPlayer.TrackDescription[] tracks) {
        List<MediaPlayer.TrackDescription> out = new ArrayList<>();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription track : tracks) {
                if (track != null && track.id >= 0) {
                    out.add(track);
                }
            }
        }
        return out;
    }

    private void cycleAudio() {
        List<MediaPlayer.TrackDescription> audio = audioTracks();
        if (audio.isEmpty()) {
            return;
        }
        audioWanted = (audioWanted + 1) % audio.size();
        player.setAudioTrack(audio.get(audioWanted).id);
        Log.i(TAG, "audio -> ordinal " + audioWanted + " id " + audio.get(audioWanted).id);
        updateTrackButtons();
        restartIdleTimer();
    }

    private void updateTrackButtons() {
        if (audioButton == null) {
            return;
        }
        List<MediaPlayer.TrackDescription> audio = audioTracks();
        if (audio.isEmpty()) {
            audioButton.setText("Audio: \u2014");
        } else {
            int index = 0;
            int id = player.getAudioTrack();
            for (int i = 0; i < audio.size(); i++) {
                if (audio.get(i).id == id) {
                    index = i;
                    break;
                }
            }
            String label = audioLabelFor(index);
            audioButton.setText("Audio: " + (label != null ? label : "Track " + (index + 1))
                    + (audio.size() > 1 ? "  (" + (index + 1) + "/" + audio.size() + ")" : ""));
        }

        // The subtitle button is not derived from libVLC's tracks: the pages come
        // from the stream via Teletext, so it is refreshed from there.
        updateSubsButton();
    }

    /**
     * Friendly name for the nth audio track, from the media's own track table.
     *
     * The matchup is by ORDINAL, not by id: MediaPlayer's track ids are transport
     * PIDs (3024, 4144) while the media track table numbers its entries 0..6, so
     * an id comparison never matches anything - which is exactly how the first
     * version of this managed to label both tracks "Track 1 (1/2)". Both lists are
     * built in PMT order, so position is the meaningful correspondence, and it is
     * checkable: this line's first audio track reports 'MPEG Audio layer 1/2' at
     * 192 kbit/s, which is what the PMT says comes first.
     */
    private String audioLabelFor(int ordinal) {
        IMedia media = player == null ? null : player.getMedia();
        if (media == null) {
            return null;
        }
        int seen = 0;
        for (int i = 0; i < media.getTrackCount(); i++) {
            IMedia.Track track = media.getTrack(i);
            if (track == null || track.type != TRACK_TYPE_AUDIO) {
                continue;
            }
            if (seen++ == ordinal) {
                return labelForCodec(track.codec, track.fourcc);
            }
        }
        return null;
    }

    /**
     * VLC's descriptive codec string is the primary source ('MPEG Audio layer 1/2',
     * 'A52 Audio (aka AC3)'); the fourcc is the fallback for when it is empty.
     */
    private static String labelForCodec(String codec, int fourcc) {
        String text = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
        String code = fourccString(fourcc).trim().toLowerCase(Locale.ROOT);
        if (text.contains("mpeg audio") || code.equals("mpga") || code.equals("mp1")
                || code.equals("mp2") || code.equals("mp3")) {
            return "MP2";
        }
        if (text.contains("a52") || text.contains("ac3") || text.contains("ac-3")
                || code.equals("a52")) {
            return "AC-3";
        }
        if (text.contains("eac3") || text.contains("e-ac3") || code.equals("eac3")) {
            return "E-AC-3";
        }
        if (text.contains("aac") || code.equals("mp4a")) {
            return "AAC";
        }
        return null;
    }

    /**
     * A four-character code packed into an int. VLC packs these little-endian via
     * its VLC_FOURCC macro - 'm' is the LOW byte - so 'mpga' arrives as 0x6167706d.
     * Reading it big-endian yields 'agpm', which is how the first version of this
     * managed to render 'Audio: AGPM'. The string is returned untrimmed because
     * VLC_CODEC_A52 is "a52 " with a trailing space.
     */
    private static String fourccString(int fourcc) {
        char[] out = new char[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (char) ((fourcc >> (8 * i)) & 0xff);
        }
        return new String(out);
    }

    // -------------------------------------------------------------- subtitles

    /**
     * The subtitle overlay: the text the viewer reads.
     *
     * libVLC's own teletext decoder renders a 40x25 character grid scaled up to the
     * panel, which is legible but rough; Teletext decodes the same pages so they can
     * be drawn as text in a reading font instead.
     *
     * The reader joins the group the player is already playing, so it adds no
     * bandwidth - but it is a second socket and a second thread, and it is therefore
     * only running while a page is selected, or for the short probe that finds out
     * which pages exist. Stopped on OFF, on a retune and in onStop, for the same
     * reason the player is stopped there: nothing should be left pulling the group.
     */
    private void showSubtitles(List<String> lines) {
        if (subtitleView == null) {
            return;
        }
        // A reader that is being stopped can still deliver one last page, and
        // subtitles the viewer switched off must not come back because of it. An
        // empty list is also the normal case for a page whose programme has no
        // dialogue, where the correct picture is no subtitles at all.
        if (subsWanted == 0 || lines == null || lines.isEmpty()) {
            subtitleView.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line);
        }
        subtitleView.setText(text.toString());
        subtitleView.setVisibility(View.VISIBLE);
    }

    /**
     * Literata, from assets/fonts/literata.ttf. It is a variable font (opsz,wght) and
     * createFromAsset uses its default instance, which is Regular - the weight
     * wanted for subtitles.
     *
     * Guarded because it is an asset lookup: a font that failed to be staged into
     * the apk should cost the reading font, not the app.
     */
    private void applyReadingFont(TextView view) {
        try {
            Typeface literata = Typeface.createFromAsset(getAssets(), "fonts/literata.ttf");
            if (literata != null) {
                view.setTypeface(literata);
            }
        } catch (Exception e) {
            Log.i(TAG, "literata not staged, using the system font (" + e + ")");
        }
    }

    private List<Teletext.Page> pagesFor(int index) {
        if (teletextPages == null || index < 0 || index >= teletextPages.size()) {
            return Collections.emptyList();
        }
        List<Teletext.Page> pages = teletextPages.get(index);
        return pages == null ? Collections.emptyList() : pages;
    }

    /** Starts a reader for one channel, following a page straight away unless null. */
    private void startReader(int index, Teletext.Page page) {
        stopReader();
        Channels.Channel channel = channels.get(index);
        Teletext teletext = new Teletext(channel.group(), channel.port(), subtitleListener);
        if (page != null) {
            teletext.select(page);
        }
        reader = teletext;
        readerGeneration++;
        readerThread = new Thread(teletext, "teletext-" + (index + 1));
        readerThread.start();
    }

    private void stopReader() {
        readerGeneration++;             // any poll still queued from it is now stale
        subtitleProbeRunning = false;
        selectFirstWhenFound = false;
        if (reader != null) {
            reader.stop();
            reader = null;
            readerThread = null;
        }
    }

    /**
     * Finds out which subtitle pages this channel offers, and optionally starts
     * following the first of them.
     *
     * The pages are listed in the stream's PMT, so finding them means reading the
     * transport stream once. Doing that only on demand would leave the button unable
     * to name anything on the first press, so this runs once per channel while it is
     * tuned and then stops. A page selected while it is still running reuses the same
     * reader, which is already parsing the tables it needs.
     *
     * Best effort by design: a channel that offers no subtitle pages simply leaves
     * the button saying off, which is what that channel deserves. A later press looks
     * again, so a channel that gains subtitle pages mid-session is picked up without
     * restarting the app.
     */
    private void discoverSubtitlePages(final int index, final boolean selectFirst) {
        if (teletextPages == null || index < 0 || index >= teletextPages.size()) {
            return;
        }
        if (teletextPages.get(index) != null) {
            return;                     // this channel's pages are already known
        }
        if (subtitleProbeRunning && index == current) {
            // Already looking. Remember the request, so the poll that is already
            // queued follows the first page when it finds the list.
            selectFirstWhenFound |= selectFirst;
            return;
        }

        startReader(index, null);
        // Set after startReader, not before: starting a reader stops whatever was
        // running, and stopping is what clears this flag. Set first, it would be
        // false again by the time the poll reads it, and the first press after a
        // retune would do nothing at all - which is the whole point of the flag.
        selectFirstWhenFound = selectFirst;
        subtitleProbeRunning = true;
        final Teletext probe = reader;
        final int generation = readerGeneration;
        final long deadline = System.currentTimeMillis() + SUBTITLE_PROBE_MS;

        ui.post(new Runnable() {
            @Override
            public void run() {
                if (generation != readerGeneration || probe != reader) {
                    return;             // superseded by a selection, a retune or onStop
                }
                List<Teletext.Page> found = probe.pages();
                if (!found.isEmpty()) {
                    teletextPages.set(index, new ArrayList<>(found));
                    Log.i(TAG, "teletext: channel " + (index + 1) + " offers "
                            + found.size() + " subtitle pages");
                    if (index != current) {
                        stopReader();   // retuned away while this was in flight
                        updateSubsButton();
                        return;
                    }
                    boolean follow = selectFirstWhenFound;
                    selectFirstWhenFound = false;
                    subtitleProbeRunning = false;
                    if (follow) {
                        subsWanted = 1;
                        probe.select(found.get(0));
                        Log.i(TAG, "teletext: page " + found.get(0).full()
                                + " (" + found.get(0).label() + ")");
                    } else {
                        stopReader();
                    }
                    updateSubsButton();
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    Log.i(TAG, "teletext: channel " + (index + 1) + " gave no subtitle pages in "
                            + (SUBTITLE_PROBE_MS / 1000) + "s");
                    stopReader();
                    updateSubsButton();
                    return;
                }
                ui.postDelayed(this, SUBTITLE_POLL_MS);
            }
        });
    }

    /**
     * OFF, then each subtitle page this channel offers, then OFF again.
     *
     * Selecting a page is what starts the reader; OFF stops it and hides the
     * overlay. Pressing before the probe has finished is handled rather than
     * ignored: the first page is followed as soon as the stream names its pages.
     */
    private void cycleSubs() {
        List<Teletext.Page> pages = pagesFor(current);
        if (pages.isEmpty()) {
            discoverSubtitlePages(current, true);
            restartIdleTimer();
            return;
        }
        subsWanted = (subsWanted + 1) % (pages.size() + 1);
        if (subsWanted == 0) {
            stopReader();
            showSubtitles(Collections.<String>emptyList());
            Log.i(TAG, "teletext: off");
        } else {
            Teletext.Page page = pages.get(subsWanted - 1);
            if (reader != null) {
                // A reader for this channel is already up - either the probe or one
                // already following a page - so this only changes its page.
                reader.select(page);
            } else {
                startReader(current, page);
            }
            Log.i(TAG, "teletext: page " + page.full() + " (" + page.label() + ")");
        }
        updateSubsButton();
        restartIdleTimer();
    }

    /** Names the page the way the Audio button names its track. */
    private void updateSubsButton() {
        if (subsButton == null) {
            return;
        }
        List<Teletext.Page> pages = pagesFor(current);
        String label;
        if (pages.isEmpty()) {
            label = subtitleProbeRunning ? "off  (scanning)" : "off";
        } else if (subsWanted <= 0 || subsWanted > pages.size()) {
            label = "off  (0/" + pages.size() + ")";
        } else {
            label = pages.get(subsWanted - 1).label()
                    + "  (" + subsWanted + "/" + pages.size() + ")";
        }
        subsButton.setText("Subs: " + label);
    }

    // --------------------------------------------------------------- multicast

    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return;                     // wired-only device; nothing to hold open
        }
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("ownit-iptv");
            multicastLock.setReferenceCounted(false);
        }
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
        if (wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ownit-iptv");
            wifiLock.setReferenceCounted(false);
        }
        if (!wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // ------------------------------------------------------------- IVLCVout

    @Override
    public void onSurfacesCreated(IVLCVout vlcVout) {
        reportWindowSize();
    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vlcVout) {
        // nothing to do; onStop detaches
    }

    /** libVLC needs the render size to pick a scaler and to letterbox correctly. */
    private void reportWindowSize() {
        int width = surface.getWidth();
        int height = surface.getHeight();
        if (width > 0 && height > 0 && vout != null) {
            vout.setWindowSize(width, height);
        }
    }

    // ------------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                tune(current + 1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                tune(current - 1);
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                // Move between the two rows explicitly rather than trusting focus
                // search, which does not find the way up out of the channel row.
                if (!chromeVisible()) {
                    showChromeFocused();
                } else if (focusInControls()) {
                    hideChrome();               // already on the top row
                } else {
                    audioButton.requestFocus();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!chromeVisible()) {
                    showChromeFocused();
                } else if (focusInControls()) {
                    if (current >= 0 && current < buttons.size()) {
                        buttons.get(current).requestFocus();
                    }
                } else {
                    hideChrome();               // already on the bottom row
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Across the two track buttons explicitly, for the same reason as
                // up and down: focus search does not cross the controls row either.
                // Falling through to showChromeFocused() moved focus to a channel
                // button, so the next press retuned a channel instead of changing
                // track - which is how a test run ended up on a different channel.
                if (chromeVisible() && focusInControls()) {
                    (audioButton.hasFocus() ? subsButton : audioButton).requestFocus();
                    restartIdleTimer();
                    return true;
                }
                showChromeFocused();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                // Reached only while the bar is hidden (a focused button eats
                // these first), so use them to bring it up.
                showChromeFocused();
                return true;

            case KeyEvent.KEYCODE_CAPTIONS:
                // Present on some remotes; toggles subtitles without the bar.
                cycleSubs();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (chromeVisible()) {
                    hideChrome();
                    return true;
                }
                return super.onKeyDown(keyCode, event);

            default:
                break;
        }

        // Digits tune directly. There are seven channels, so one digit is enough.
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
            tune(keyCode - KeyEvent.KEYCODE_1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ utils

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
