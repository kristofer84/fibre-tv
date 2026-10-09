package net.xcds.iptv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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
    /** libvlc_media_track_type_t: video is 1. See the note on TRACK_TYPE_AUDIO. */
    private static final int TRACK_TYPE_VIDEO = 1;

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
     * Where the subtitle overlay sits. Two margins for two states: a normal TV
     * subtitle position while the chrome is hidden, and raised clear of the two
     * rows of chrome while it is showing, so the channel bar never covers the text.
     * The chrome hides itself again after four seconds.
     *
     * Measured on the TV at density 320: the chrome's top edge is 292px above the
     * bottom, so 140dp leaves the overlay 12px clear of it while the bar is up. With
     * the bar away the overlay drops to 24dp, which is where subtitles belong.
     */
    private static final int SUBTITLE_MARGIN_IDLE_DP = 48;
    private static final int SUBTITLE_MARGIN_CHROME_DP = 190;

    /** Subtitle text size. 26sp is 85% of the 30sp this started at. */
    private static final int SUBTITLE_TEXT_SP = 26;

    /**
     * Padding inside the subtitle box, and why it is separately tunable from the
     * margin: the margin is where the box sits on screen, the padding is how much
     * black surrounds the text. Kristofer wanted the box tighter around the words
     * without the box moving up the screen, which is this and not the margin.
     */
    private static final int SUBTITLE_PADDING_H_DP = 10;
    private static final int SUBTITLE_PADDING_V_DP = 5;

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
    private FrameLayout root;
    private LinearLayout bottom;
    /** The settings overlay, or null when it is not up. See openSettings. */
    private View settingsOverlay;
    private SettingsPanel settingsPanel;
    private HorizontalScrollView controlsScroll;
    /** Bumped on every show and hide so a finishing fade cannot hide a freshly shown bar. */
    private int chromeGeneration;
    private static final long CHROME_ANIM_MS = 150;
    private Button audioButton;
    private Button subsButton;
    private Button settingsButton;
    private Button infoButton;
    /** The on-demand line: channel, resolution, bandwidth. Hidden until asked for. */
    private TextView infoView;
    private boolean infoShown;
    /** The picture size as the stream reports it, or null until the probe has read it. */
    private String videoSize;
    /** Bytes per second, from the watchdog's own samples - the one place this app measures traffic. */
    private long rxBytesPerSecond = -1;
    private long lastRx;
    private long lastRxAt;

    /**
     * The controls row, in order. Left and right step along this rather than between two
     * named buttons, so adding Settings to the row did not need a new key - and the
     * AUDIO -> SUBS adjacency that was verified on the device is unchanged.
     */
    private final List<Button> controlButtons = new ArrayList<>();
    private TextView subtitleView;
    private FrameLayout.LayoutParams subtitleParams;

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

    // Built in onCreate for the same reason as idleHide below: javac emits no
    // enclosing-method for anonymous classes declared in a field initialiser, and
    // d8 (R8 8.2.2, from build-tools 34) crashes on that with a null-name NPE.
    private Teletext.Listener subtitleListener;

    private int current = -1;

    /**
     * The playlist revision this screen loaded. Settings edits the list and bumps this, so
     * coming back to the foreground is enough to notice - the two activities share nothing
     * else.
     */
    private int loadedRevision;

    /**
     * Track choice, remembered across channel changes. Both are ordinals rather
     * than track ids, because the ids are per-stream (they are transport PIDs) but
     * the ordering is the same on every channel here.
     */
    private int audioWanted;
    private boolean applyWanted;

    /** Set once Playing has fired for the current tuning, cleared on every retune. */
    private boolean started;

    /** Watchdog: how often progress is checked, how long a stall has to last, and how many
     *  times it will re-tune before saying so instead of looping. */
    private static final long WATCHDOG_TICK_MS = 2000;

    /** How long the input has to stand still before the watchdog re-tunes. */
    private static final long STALL_LIMIT_MS = 10000;
    private static final int STALL_MAX_RETRIES = 3;

    /** How long a typed channel number waits for another digit before it is tuned. */
    private static final long DIGIT_TIMEOUT_MS = 1500;

    /** libVLC's own volume, captured once, used as the 100% baseline for the per-track trims. */
    private int defaultVolume;

    /** What the last applied trim was, so identical ES events do not repeat it. */
    private int lastAppliedVolume = -1;
    private String lastAppliedLabel = "";

    /**
     * Watchdog state. Progress is libVLC's own demuxer counters, not the running time:
     * getTime() reports 0 and keeps reporting 0 for a live multicast, which the first version
     * of this found on the device. lastProgress <= 0 means nothing has been read yet.
     */
    private Runnable watchdog;
    private long lastProgress;
    private long lastProgressAt;
    private int stallRetries;
    private boolean watchdogLoggedNotArmed;
    private boolean watchdogLoggedArmed;

    /** Digits typed so far, tuned when they stop arriving. See digitTyped. */
    private final StringBuilder typing = new StringBuilder();
    private Runnable tuneTyped;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Created in onCreate rather than as a field initialiser on purpose: javac
    // emits no enclosing-method for anonymous classes declared in an initialiser,
    // and d8 (R8 8.2.2, from build-tools 34) crashes on that with a null-name NPE.
    private Runnable idleHide;

    /**
     * Built in onCreate, installed on the two bars and on every button in them. The
     * buttons need it as much as the bars: a Button consumes its own gesture, so the
     * bar's listener never sees a finger resting on one.
     */
    private View.OnTouchListener holdChrome;

    /** True while a finger is down on the chrome. See holdChrome. */
    private boolean touching;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // For phones, where the settings overlay has fields and a keyboard: the panel should be
        // resized rather than covered. No effect on a television, which has no soft keyboard.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        idleHide = new Runnable() {
            @Override
            public void run() {
                // A finger on the bar counts as "in use" just as focus does: a Button
                // tapped in touch mode is clicked without ever being focused, so the
                // focus test below cannot see it, and the bar would otherwise hide
                // from under the finger using it.
                  // The panel counts as in use for the same reason a resting finger does: it holds
                  // the remote, so the chrome hiding underneath it is no reason to take the remote back.
                  if (settingsOverlay != null || touching || barScroll.hasFocus() || bar.hasFocus()
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

        // A typed channel number is tuned once the digits stop arriving, rather than on the
        // first one: the list is editable now and can hold more than nine channels.
        tuneTyped = new Runnable() {
            @Override
            public void run() {
                String typed = typing.toString();
                typing.setLength(0);
                if (typed.isEmpty()) {
                    return;
                }
                int number;
                try {
                    number = Integer.parseInt(typed);
                } catch (NumberFormatException e) {
                    number = -1;
                }
                if (number >= 1 && number <= channels.size()) {
                    Log.i(TAG, "tuned to typed channel " + number);
                    tune(number - 1);
                } else {
                    // Say so rather than wrapping: on a seven channel list "12" must not
                    // quietly tune channel 5.
                    Log.i(TAG, "no channel " + typed + " (the list has "
                            + channels.size() + ")");
                    showCard(title() + "\nno channel " + typed
                            + " (the list has " + channels.size() + ")");
                    restartIdleTimer();
                }
            }
        };

        // A freeze is otherwise unrecoverable: a WiFi hiccup or an operator re-mux leaves the
        // picture stopped with nothing to notice it. This watches libVLC's own running time and
        // re-tunes, which rejoins cleanly. It stays disarmed until the player reports a
        // non-zero time, so a stream that never reports one degrades to no watchdog rather than
        // to a re-tune loop.
        watchdog = new Runnable() {
            @Override
            public void run() {
                watchdogTick();
                if (player != null && started) {
                    ui.postDelayed(watchdog, WATCHDOG_TICK_MS);
                }
            }
        };

        holdChrome = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touching = true;
                        restartIdleTimer();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        touching = false;
                        restartIdleTimer();
                        break;
                    default:
                        break;
                }
                return false;           // never consume: the buttons and scrolling need this
            }
        };

        buildUi();

        // The list is the saved m3u if the viewer has edited or imported one, and the copy
        // staged into assets otherwise. Playlist guarantees the second of those if the
        // first turns out to be unusable, so the list can be empty here only if the asset
        // is missing or unreadable too.
        channels.addAll(Playlist.load(this, ASSET));
        loadedRevision = Playlist.revision(this);
        if (channels.isEmpty()) {
            card.setText("no channels: fix the list in Settings");
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

        current = indexOfLastChannel();
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
        // An edited list is picked up here. Settings bumps a revision when it saves, so
        // returning to the foreground is enough to notice, and reloading before tuning means
        // the tune uses the new list rather than the indices from the old one.
        if (Playlist.revision(this) != loadedRevision) {
            reloadPlaylist();
        }
        tune(current);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player == null || vout == null) {
            return;
        }
        // Stop rather than pause: this is what drops the multicast membership. Logged because this
        // property - leaving the app really does leave the group - matters more than any overlay,
        // and a line in the log is what makes it checkable.
        Log.i(TAG, "onStop: stopping the player, which drops the group membership");
        player.stop();
        // ...and the same for the teletext reader, which joins the same group.
        stopReader();
        ui.removeCallbacks(watchdog);
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
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        tapTogglesChrome(surface);
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
        // The subtitles layer covers the whole screen and sits above the video, so a tap
        // on the picture lands here rather than on the surface below it.
        tapTogglesChrome(subtitlesSurface);
        root.addView(subtitlesSurface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // The subtitle overlay. Added after the video surfaces so it draws over
        // them, and before the card and the bar so those still draw over it: the
        // chrome has to stay readable while subtitles are on.
        subtitleView = new TextView(this);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_TEXT_SP);
        subtitleView.setLineSpacing(dp(4), 1.0f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setPadding(dp(SUBTITLE_PADDING_H_DP), dp(SUBTITLE_PADDING_V_DP),
                dp(SUBTITLE_PADDING_H_DP), dp(SUBTITLE_PADDING_V_DP));
        // Near-opaque rather than solid: white on black is what was asked for, and
        // a hint of picture through the box keeps it from looking like a hole.
        subtitleView.setBackgroundColor(0xE0000000);
        subtitleView.setMaxWidth(dp(900));
        applyReadingFont(subtitleView);
        subtitleView.setVisibility(View.GONE);
        subtitleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        placeSubtitles(false);
        root.addView(subtitleView, subtitleParams);

        card = new TextView(this);
        card.setTextColor(Color.WHITE);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.setBackgroundResource(R.drawable.card_bg);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.TOP | Gravity.START;
        cardParams.setMargins(insetPx(), insetPx(), 0, 0);
        root.addView(card, cardParams);

        // The on-demand line, in the same shape as the card so the two read as one family. Above the
        // subtitle area, and hidden while the chrome is up, so it competes with neither.
        infoView = new TextView(this);
        infoView.setTextColor(0xFFE8EAF0);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        infoView.setBackgroundResource(R.drawable.card_bg);
        infoView.setPadding(dp(14), dp(8), dp(14), dp(8));
        infoView.setVisibility(View.GONE);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        infoParams.bottomMargin = dp(120);
        root.addView(infoView, infoParams);

        // Two rows at the bottom: the track controls, then the channels. Keeping
        // them in separate rows means the D-pad can move between them and a long
        // channel name cannot scroll the controls out of reach.
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        // A gradient scrim rather than a hard-edged panel, and full bleed with the rows inset
        // by the TV safe area: the chrome sits on the picture instead of in a box.
        bottom.setBackgroundResource(R.drawable.scrim);
        bottom.setPadding(insetPx(), dp(24), insetPx(), insetPx());

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        audioButton = new Button(this);
        audioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleAudio();
            }
        });
        stylePill(audioButton, R.drawable.ic_audio);
        audioButton.setOnFocusChangeListener(focusWatcher);
        // The listener has to be on the BUTTONS, not only on the bars around them: a
        // Button consumes the gesture itself, so a finger resting on one never reaches
        // its parent's listener - which is precisely the case this is here to protect,
        // and the first version of this got it wrong.
        audioButton.setOnTouchListener(holdChrome);
        subsButton = new Button(this);
        subsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSubs();
            }
        });
        stylePill(subsButton, R.drawable.ic_subs);
        subsButton.setOnFocusChangeListener(focusWatcher);
        subsButton.setOnTouchListener(holdChrome);
        settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The player stops while Settings is open, because onStop drops the group
                // membership, and tunes again on the way back - which is also when an
                // edited list is picked up. See reloadPlaylist.
                openSettings();
            }
        });
        stylePill(settingsButton, R.drawable.ic_settings);
        settingsButton.setOnFocusChangeListener(focusWatcher);
        settingsButton.setOnTouchListener(holdChrome);
        controls.addView(audioButton, controlParams());
        controls.addView(subsButton, controlParams());
        infoButton = new Button(this);
        infoButton.setText("Info");
        infoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleInfo();
                restartIdleTimer();
            }
        });
        stylePill(infoButton, R.drawable.ic_info);
        infoButton.setOnFocusChangeListener(focusWatcher);
        infoButton.setOnTouchListener(holdChrome);
        controls.addView(settingsButton, controlParams());
        controls.addView(infoButton, controlParams());
        controlButtons.add(audioButton);
        controlButtons.add(subsButton);
        controlButtons.add(settingsButton);
        controlButtons.add(infoButton);

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
        // A finger on either bar holds it open, and the buttons get the same listener
        // in buildUi and buildChannelBar. Returning false is deliberate: the scroll views
        // must keep handling their own drags and the buttons their taps, so this only
        // watches - it never consumes.
        controlsScroll.setOnTouchListener(holdChrome);
        barScroll.setOnTouchListener(holdChrome);
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
                    // A tapped button is not a focused one, so the focus listener above
                    // never runs for touch: without this the bar would disappear four
                    // seconds after the tap that used it.
                    restartIdleTimer();
                }
            });
            stylePill(button, 0);
            button.setOnFocusChangeListener(focusWatcher);
            button.setOnTouchListener(holdChrome);
            bar.addView(button, controlParams());
            buttons.add(button);
        }
    }

    /**
     * Pick up a list that Settings has changed.
     *
     * Everything derived from the list is rebuilt here - the channel buttons, and the SDT
     * name and subtitle-page caches that are sized to it - because after an edit the old
     * indices mean nothing. The screen goes back to the first channel, which is the only
     * index certainly valid in any list, and the tune that follows does the rest.
     */
    private void reloadPlaylist() {
        loadedRevision = Playlist.revision(this);
        channels.clear();
        channels.addAll(Playlist.load(this, ASSET));
        sdtNames = new String[channels.size()];
        teletextPages = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            teletextPages.add(null);
        }
        buttons.clear();
        bar.removeAllViews();
        buildChannelBar();
        current = indexOfLastChannel();
        updateBar();
        Log.i(TAG, "playlist: reloaded, " + Playlist.describe(channels.size()));
        if (channels.isEmpty()) {
            card.setText("no channels: fix the list in Settings");
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
                final String name = Sdt.serviceName(channel.url, 4000);
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

    /**
     * A tap on the picture shows or hides the chrome, which is the whole of what a touch
     * device needs: without it there is no way to reach the buttons at all, and the rest
     * of the app is already phone-friendly.
     *
     * An OnTouchListener rather than an OnClickListener, deliberately. A click listener
     * makes the view clickable, and a clickable view that has focus consumes
     * DPAD_CENTER itself - so the remote path, which is verified and in daily use, would
     * quietly change behaviour. Touch events reach an OnTouchListener without that.
     */
    private void tapTogglesChrome(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    toggleChrome();
                }
                return true;            // the picture owns the gesture
            }
        });
    }

    /**
     * Show or hide the chrome from a touch. showChrome rather than showChromeFocused: a
     * finger aims at the button it wants, so moving focus as well would only add a
     * highlight nobody asked for.
     */
    private void toggleChrome() {
        if (chromeVisible()) {
            hideChrome();
        } else {
            showChrome();
        }
    }

    private void showChrome() {
        // Logged because the interesting failures here are about timing - a bar that
        // hides while a viewer is still choosing, or from under a finger - and the
        // timestamps in this log are the only instrument that answers them.
        Log.i(TAG, "chrome up");
        chromeGeneration++;
        card.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        bottom.animate().cancel();
        bottom.setAlpha(0f);
        bottom.setTranslationY(dp(16));
        bottom.animate().alpha(1f).translationY(0f).setDuration(CHROME_ANIM_MS).start();
        controlsScroll.setVisibility(View.VISIBLE);
        barScroll.setVisibility(View.VISIBLE);
        placeSubtitles(true);
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
        if (settingsOverlay != null) {
            // A settings panel is open over the chrome. Hiding the chrome is fine; taking focus back
            // to the surface is not, because the panel would still be on screen and would silently
            // stop answering the remote. It keeps focus until it is closed.
            return;
        }
        if (!chromeVisible()) {
            // The idle timer can fire long after something else hid the bar, and a
            // second "hidden" line would make this log useless for timing anything.
            return;
        }
        Log.i(TAG, "chrome hidden");
        placeSubtitles(false);
        card.setVisibility(View.GONE);
        surface.requestFocus();
        // Fade and slide out, then take the views away - by generation, so that showing the
        // bar again during those 150ms is not undone by this finishing.
        final int generation = ++chromeGeneration;
        bottom.animate().cancel();
        bottom.animate().alpha(0f).translationY(dp(16)).setDuration(CHROME_ANIM_MS).start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation == chromeGeneration) {
                    controlsScroll.setVisibility(View.GONE);
                    barScroll.setVisibility(View.GONE);
                    bottom.setVisibility(View.GONE);
                }
            }
        }, CHROME_ANIM_MS);
    }

    /**
     * Moves the subtitles out of the chrome's way only while the chrome is up. With
     * the bar hidden they sit where a viewer expects subtitles to sit, rather than
     * floating a fifth of the screen above the bottom for no reason.
     */
    private void placeSubtitles(boolean chromeUp) {
        if (subtitleParams == null) {
            return;
        }
        int bottom = chromeUp ? SUBTITLE_MARGIN_CHROME_DP : SUBTITLE_MARGIN_IDLE_DP;
        subtitleParams.setMargins(dp(32), 0, dp(32), dp(bottom));
        if (subtitleView != null) {
            subtitleView.setLayoutParams(subtitleParams);
        }
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
        return controlButtons.contains(getCurrentFocus());
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
        Playlist.setLastChannel(this, channel.url);

        showCard(title() + "\njoining "
                + (channel.multicast() ? channel.group() : channel.url) + ", please wait");
        updateBar();
        applyWanted = true;
        started = false;
        // Subtitles start off on every channel, and any reader for the previous
        // channel stops here. The audio choice is remembered, because AC-3 is a
        // preference; a subtitle page is something you turn on for one programme,
        // and the reader is joined to the group of the channel it was started for.
        // The pages this channel offers are looked up in the background so the
        // button can name them when it is pressed.
        lastProgress = 0;
        lastProgressAt = System.currentTimeMillis();
        stallRetries = 0;
        watchdogLoggedArmed = false;
        watchdogLoggedNotArmed = false;
        ui.removeCallbacks(watchdog);
        subsWanted = 0;
        stopReader();
        showSubtitles(Collections.<String>emptyList());
        updateTrackButtons();
        if (Ts.canSniff(channel.url)) {
            // A group to join or a relay to GET; Ts knows which and the sniffers do not care.
            // A new channel starts from a dash: showing the previous channel's size while this one is
            // being read would be exactly the guess the dash exists to avoid.
            videoSize = null;
            updateInfoLine();
            probeServiceName(index);
            probeVideoSize(index);
            discoverSubtitlePages(index);
        } else {
            // One line per tune, rather than a silence that looks like a bug. Playback is
            // unaffected either way: the bar keeps the playlist name and the Subtitles button
            // finds no pages.
            Log.i(TAG, "nothing to sniff for this address, so no stream name and no teletext: "
                    + channel.url);
        }
        updateSubsButton();

        Media media = new Media(libVLC, Uri.parse(channel.url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=800");
        player.setMedia(media);
        media.release();

        acquireLocks();
        player.play();
    }

    /**
     * The card's headline: which channel, and which list. The profile belongs here because
     * "what am I watching" has two answers once the LAN and the tunnel hold different lists.
     */
    private String title() {
        if (current < 0 || current >= channels.size()) {
            return getString(R.string.app_name);
        }
        return (current + 1) + "   " + nameOf(current)
                + "   (" + Playlist.activeName(this) + ")";
    }

    private void onPlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                started = true;
                showCard(title());
                restartIdleTimer();
                onTracksAvailable("playing");
                applyAudioDelay(0);
                applyAudioTrim();
                lastProgressAt = System.currentTimeMillis();
                ui.removeCallbacks(watchdog);
                ui.postDelayed(watchdog, WATCHDOG_TICK_MS);
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
                ui.removeCallbacks(watchdog);          // nothing to watch; the card says it
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
        // Turn libVLC's own subtitle rendering off rather than merely not choosing a
        // track: left alone it auto-selects one, and its teletext renderer then draws
        // the grid as well as our overlay - two sets of subtitles, one of them the
        // upscaled bitmap this whole rework exists to get rid of.
        //
        // Done before the audio check below, because this method is called on every
        // ES event and the first of those arrives before the audio list is parsed.
        // Disabling only once the audio is known left a window in which libVLC could
        // pick a subtitle track, which is exactly what put its own text on screen
        // under ours.
        if (player.getSpuTrack() != -1) {
            player.setSpuTrack(-1);
            Log.i(TAG, "spu off: libVLC's teletext renderer is not used");
        }

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
        applyAudioTrim();

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
        applyAudioTrim();
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
    /**
     * Ask the stream what size its picture is. Its own short read, beside the SDT probe: the two
     * would otherwise serialise two timeouts on one thread, and on a relay each is a brief
     * connection that closes as soon as it has an answer.
     */
    private void probeVideoSize(final int index) {
        if (index < 0 || index >= channels.size()) {
            return;
        }
        final Channels.Channel channel = channels.get(index);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String size = Sps.probe(channel.url, 4000);
                if (size == null) {
                    return;                                 // the line keeps its dash
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (index == current) {
                            videoSize = size;
                            Log.i(TAG, "video: channel " + (index + 1) + " is " + size);
                            updateInfoLine();
                        }
                    }
                });
            }
        }, "video-" + (index + 1)).start();
    }

    private void startReader(int index, Teletext.Page page) {
        stopReader();
        Channels.Channel channel = channels.get(index);
        Teletext teletext = new Teletext(channel.url, subtitleListener);
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
    private void discoverSubtitlePages(final int index) {
        if (teletextPages == null || index < 0 || index >= teletextPages.size()) {
            return;
        }
        if (teletextPages.get(index) != null) {
            return;                     // this channel's pages are already known
        }
        if (subtitleProbeRunning && index == current) {
            return;                     // already looking; the poll will report
        }

        startReader(index, null);
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
                    subtitleProbeRunning = false;
                    stopReader();
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
            // Nothing to cycle yet. Deliberately does not start following a page: the
            // only thing that may turn subtitles on is a viewer asking for a page that
            // is on offer, and the button already says "(scanning)" until then.
            Log.i(TAG, "teletext: still looking for this channel's pages");
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
            multicastLock = wifi.createMulticastLock("net.xcds.iptv");
            multicastLock.setReferenceCounted(false);
        }
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
        if (wifiLock == null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "net.xcds.iptv");
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
        if (settingsOverlay != null) {
            // The panel has the viewer's attention: the player must not tune or change track behind
            // it. Back closes the overlay; everything else belongs to whatever has focus inside.
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeSettingsOverlay();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
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
                // Along the controls row explicitly, for the same reason as up and down:
                // focus search does not cross that row either. Falling through to
                // showChromeFocused() moved focus to a channel button, so the next press
                // retuned a channel instead of changing track - which is how a test run
                // ended up on a different channel.
                if (chromeVisible() && focusInControls()) {
                    int index = controlButtons.indexOf(getCurrentFocus());
                    int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    int next = (index + step + controlButtons.size()) % controlButtons.size();
                    controlButtons.get(next).requestFocus();
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

            case KeyEvent.KEYCODE_INFO:
                // For remotes that carry an info key; the Info button in the chrome is the route that
                // every remote has, since it is reached with the D-pad like the rest of the chrome.
                toggleInfo();
                restartIdleTimer();
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

        // Digits accumulate into a channel number. One digit was enough while the list was
        // the built-in seven; now that it is editable it can be longer, so the number is
        // tuned when the digits stop arriving.
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            digitTyped(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (typing.length() > 0) {
            // Any other key abandons a half-typed number rather than tuning to something the
            // viewer did not finish typing: a stray press must not change channel later.
            ui.removeCallbacks(tuneTyped);
            typing.setLength(0);
            Log.i(TAG, "channel typing abandoned");
            showCard(title());
            restartIdleTimer();
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ utils

    /**
     * The on-demand line: what is playing, at what resolution, at what rate.
     *
     * Both numbers already exist here. The resolution comes from libVLC's current video track; when it
     * has not been reported yet this shows a dash, because a wrong number is worse than no number. The
     * rate is the watchdog's own sampling of TrafficStats - the only traffic figure that works on these
     * streams - so the number on screen and the number the watchdog acts on cannot disagree.
     */
    private void toggleInfo() {
        infoShown = !infoShown;
        Log.i(TAG, "info line " + (infoShown ? "shown" : "hidden"));
        updateInfoLine();
    }

    private void updateInfoLine() {
        if (infoView == null) {
            return;
        }
        // Shown immediately, chrome or no chrome. It used to wait for the chrome to hide, which
        // meant the Info button - a control that lives *in* the chrome - appeared to do nothing for
        // four seconds, long enough to press it again and toggle it straight back off. The line is
        // brought to the front so the bars cannot cover it while they are up.
        if (!infoShown) {
            infoView.setVisibility(View.GONE);
            return;
        }
        String line = nameOf(current) + " - " + resolution() + " - " + bitrate();
        if (!line.contentEquals(infoView.getText())) {
            // Logged whenever it changes, so the reading can be checked against a known table without
            // reading pixels off a screenshot.
            Log.i(TAG, "info: " + line);
            infoView.setText(line);
        }
        infoView.bringToFront();
        infoView.setVisibility(View.VISIBLE);
    }

    /**
     * What libVLC says it is decoding, or a dash.
     *
     * The size arrives through the Vout callback below, which is the only path that reports it here:
     * getCurrentVideoTrack() stays empty when video goes out through a Vout view, and the media's
     * track table lists only the first audio elementary stream. It is consulted as a second opinion
     * because a wrong number would be worse than a dash.
     */
    /**
     * The picture size the stream reports, or a dash. It comes from Sps, which reads the H.264
     * parameter set out of the same transport stream the SDT and teletext readers use, because
     * libVLC reports nothing here: getCurrentVideoTrack() stays empty through a Vout view, and the
     * media track table lists only the first audio elementary stream.
     *
     * A dash until the probe has landed. The value is pixels and is only ever formatted into this
     * string - it must not reach a text size, a padding or a layout parameter, where dp or sp would
     * be meant.
     */
    private String resolution() {
        return videoSize == null ? Sps.UNKNOWN : videoSize;
    }

    private String bitrate() {
        if (rxBytesPerSecond <= 0) {
            return "\u2014";
        }
        return String.format(java.util.Locale.US, "%.1f Mbit/s", rxBytesPerSecond * 8.0 / 1000000.0);
    }

    /**
     * Settings, as an overlay over the playing picture and hosted by this activity.
     *
     * Hosting it here is the point rather than a convenience: a separate activity would run this
     * activity's onStop, which stops playback and drops the multicast membership - the invariant that
     * keeps a forgotten stream off the LAN. Nothing about this activity's lifecycle changes, so the
     * stream, the membership and the teletext reader all keep running while the list is edited.
     *
     * The standalone SettingsActivity stays for a playlist so broken that nothing plays, and for
     * phones; both hosts build the same panel.
     */
    private void openSettings() {
        if (settingsOverlay != null) {
            return;
        }
        if (player == null) {
            // Nothing is playing to overlay: the activity is the right host in that state.
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        settingsPanel = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override
            public void settingsChanged() {
                // Apply what can be applied live, and pick up a new list if there is one. No stop,
                // no re-tune unless the list itself changed - which is the whole point.
                applyAudioDelay(0);
                applyAudioTrim();
                if (Playlist.revision(TvActivity.this) != loadedRevision) {
                    reloadPlaylist();
                    tune(current);
                }
            }

            @Override
            public void closeSettings() {
                closeSettingsOverlay();
            }
        });

        // Before the overlay exists: hideChrome() moves focus to the surface, and the guard above
        // must not mistake this first hide for a timer firing under an open panel.
        hideChrome();

        FrameLayout overlay = new FrameLayout(this);
        // Dimmed rather than opaque: the picture staying visible is the point of an overlay.
        overlay.setBackgroundColor(0xB0000000);
        overlay.setFocusable(true);
        overlay.addView(settingsPanel.build(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        settingsOverlay = overlay;
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        settingsPanel.refresh();
        // The panel takes focus on its first control rather than as a container: see focusFirst.
        settingsPanel.focusFirst();
        Log.i(TAG, "settings: overlay opened - the player keeps running behind it");
    }

    private void closeSettingsOverlay() {
        if (settingsOverlay == null) {
            return;
        }
        root.removeView(settingsOverlay);
        settingsOverlay = null;
        settingsPanel = null;
        surface.requestFocus();
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(surface.getWindowToken(), 0);
        }
        Log.i(TAG, "settings: overlay closed - the player never stopped");
    }

    /**
     * libVLC takes MICROSECONDS and a person types milliseconds, so the conversion happens here
     * and nowhere else. A wrong unit here is a silent thousandfold error, and the settings
     * screen stores milliseconds precisely so that this is the only line that has to know.
     *
     * The retry exists because the audio output may not be up at the instant Playing fires;
     * one retry half a second later is enough, and both attempts are logged.
     */
    private void applyAudioDelay(final int attempt) {
        long micros = AudioTuning.delayMs(this) * 1000L;
        boolean ok = player.setAudioDelay(micros);
        Log.i(TAG, "audio delay " + (micros / 1000) + " ms -> " + micros + " us (attempt "
                + (attempt + 1) + ", libVLC says " + ok + ", reads back "
                + player.getAudioDelay() + " us)");
        if (!ok && attempt == 0) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (player != null && started) {
                        applyAudioDelay(attempt + 1);
                    }
                }
            }, 500);
        }
    }

    /**
     * The trim is a percentage of libVLC's own volume, captured once per process before any
     * trim is applied. That avoids depending on a volume scale the API we have does not
     * document, and it means setting a trim back to 100 lands exactly where it started rather
     * than drifting by whatever was applied last.
     */
    private void applyAudioTrim() {
        int volume = player.getVolume();
        String label0 = null;
        if (defaultVolume <= 0 && volume > 0) {
            defaultVolume = volume;
            Log.i(TAG, "audio: libVLC's own volume is " + volume + ", taken as the 100% baseline");
        }
        if (defaultVolume <= 0) {
            return;                     // audio not up yet; the next selection tries again
        }
        // Keyed on the track's order, not its codec name: the media track table lists only the
        // first audio ES for these streams, so the AC-3 has no name to look up - measured, not
        // assumed, see AudioTuning.
        int ordinal = selectedAudioOrdinal();
        int percent = AudioTuning.trim(this, AudioTuning.bucket(ordinal));
        int wanted = AudioTuning.clamp(
                Math.round(defaultVolume * percent / 100f), 0, defaultVolume * 2);
        // Only when it changes: this is called on every ES event, and the counters show seven
        // of those per tuning, so acting and logging every time is seven identical lines.
        String bucket = AudioTuning.bucket(ordinal);
        if (wanted == lastAppliedVolume && bucket.equals(lastAppliedLabel)) {
            return;
        }
        lastAppliedVolume = wanted;
        lastAppliedLabel = bucket;
        player.setVolume(wanted);
        Log.i(TAG, "audio trim " + lastAppliedLabel + " " + percent + "% -> volume "
                + wanted + " of a baseline " + defaultVolume);
    }

    /** Which audio track is playing, as an ordinal, which is what the label lookup wants. */
    private int selectedAudioOrdinal() {
        if (player == null) {
            return 0;
        }
        List<MediaPlayer.TrackDescription> audio = audioTracks();
        int id = player.getAudioTrack();
        for (int i = 0; i < audio.size(); i++) {
            if (audio.get(i).id == id) {
                return i;
            }
        }
        return 0;
    }

    /**
     * One watchdog check: has anything actually moved?
     *
     * The signal is Android's own received-bytes counter rather than the running time: getTime()
     * returns 0 for
     * a live multicast and keeps returning 0, which this discovered on the device - the first
     * version was written against it and would never have fired for the case it exists for.
     * demuxReadBytes counts what the demuxer has pulled off the network and displayedPictures
     * counts what reached the screen, so bytes arriving but nothing decoding is also a stall.
     *
     * It stays unarmed until something has moved at least once, so a stream that never reports
     * stats degrades to no watchdog instead of a re-tune loop. Every trip and every recovery is
     * logged: a watchdog that retries silently is indistinguishable from a stream that never
     * broke.
     */
    private void watchdogTick() {
        if (player == null || !started) {
            return;
        }
        long now = System.currentTimeMillis();
        // Measured here and nowhere else: the watchdog already samples this counter, and a second
        // sampling elsewhere is a second answer waiting to disagree with it.
        long received = uidRxBytes();
        if (lastRxAt > 0 && now > lastRxAt) {
            rxBytesPerSecond = Math.max(0, (received - lastRx) * 1000 / (now - lastRxAt));
            updateInfoLine();
        }
        lastRx = received;
        lastRxAt = now;
        // Re-check the trim here as well as on track selection: the media track table, which is
        // where the codec label comes from, is not populated at the instant a track is switched,
        // so the first attempt can fall back to "Other" and never be corrected - which is what
        // the device showed. This is a no-op unless the label or the trim has changed.
        applyAudioTrim();
        IMedia media = player.getMedia();
        IMedia.Stats stats = media == null ? null : media.getStats();
        // The signal is Android's own per-uid receive counter, chosen by measurement:
        //   getTime()  - reports 0 for a live multicast and keeps reporting 0
        //   position   - 0.0, for the same reason
        //   media stats- frozen at one value while the picture was perfect
        //   /proc/net/dev - not readable by an app, returns -1
        //   TrafficStats.getUidRxBytes - advances at the stream's own rate, ~12.4 Mbit/s here
        // It counts the app's whole network use, which for this app is the stream and its
        // probes, so a stalled input stops it. What it cannot see is bytes still arriving while
        // nothing decodes - an operator re-mux, where libVLC reports no picture counters at all
        // (displayedPictures stayed 0 even while playing). That limit is documented rather than
        // papered over.
        long progress = uidRxBytes();
        if (progress != lastProgress) {
            if (lastProgress > 0 && stallRetries > 0) {
                Log.i(TAG, "watchdog: traffic moving again (uidRx=" + progress + ")");
            }
            if (!watchdogLoggedArmed) {
                watchdogLoggedArmed = true;
                Log.i(TAG, "watchdog: armed, watching the network counters");
            }
            lastProgress = progress;
            lastProgressAt = now;
            stallRetries = 0;
            return;
        }
        if (lastProgress <= 0) {
            if (!watchdogLoggedNotArmed) {
                watchdogLoggedNotArmed = true;
                Log.i(TAG, "watchdog: no traffic yet, so it is not armed for this tuning");
            }
            lastProgressAt = now;
            return;
        }
        if (now - lastProgressAt < STALL_LIMIT_MS) {
            return;
        }
        stallRetries++;
        if (stallRetries > STALL_MAX_RETRIES) {
            Log.i(TAG, "watchdog: still no traffic after " + STALL_MAX_RETRIES + " re-tunes (uidRx="
                    + progress + "), stopping; press the channel number");
            showCard(title() + "\nno picture for " + (STALL_LIMIT_MS / 1000) + "s, gave up after "
                    + STALL_MAX_RETRIES + " tries - press the channel number");
            restartIdleTimer();
            ui.removeCallbacks(watchdog);
            return;
        }
        Log.i(TAG, "watchdog: no traffic for " + (STALL_LIMIT_MS / 1000) + "s (uidRx=" + progress
                + "), re-tuning - attempt " + stallRetries + " of " + STALL_MAX_RETRIES);
        showCard(title() + "\nno picture, re-tuning (" + stallRetries + "/"
                + STALL_MAX_RETRIES + ")");
        restartIdleTimer();
        lastProgressAt = now;
        tune(current);
    }

    /**
     * Bytes Android has counted for this app's uid. /proc/net/dev is not readable by an app on
     * Android (it returned -1 in the measurement), but TrafficStats is the platform's own
     * per-uid counter and needs no permission. It is the app's whole network use, which for this
     * app is the stream plus its probes - so it is a liveness signal for the input rather than a
     * counter of decoded pictures.
     */
    private long uidRxBytes() {
        return android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
    }

    /** The counters, for the log. */
    private static String stats(IMedia.Stats s) {
        return "demuxRead=" + s.demuxReadBytes + " read=" + s.readBytes
                + " pictures=" + s.displayedPictures + " audioFrames=" + s.playedAbuffers;
    }

    /**
     * The channel being watched last, found by address. Falls back to the first entry when
     * that address is not in the list any more, which is what an edit can do to it.
     */
    private int indexOfLastChannel() {
        String last = Playlist.lastChannel(this);
        if (last != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (last.equals(channels.get(i).url)) {
                    Log.i(TAG, "starting on the last channel: " + channels.get(i).name);
                    return i;
                }
            }
            Log.i(TAG, "the last channel is not in this list any more, starting at 1");
        }
        return 0;
    }

    private void digitTyped(int digit) {
        if (typing.length() >= 4) {
            typing.setLength(0);            // four digits is already past any real list
        }
        typing.append(digit);
        Log.i(TAG, "channel typing: " + typing);
        showCard(title() + "\nchannel " + typing + " ...");
        restartIdleTimer();
        ui.removeCallbacks(tuneTyped);
        ui.postDelayed(tuneTyped, DIGIT_TIMEOUT_MS);
    }

    /**
     * One button style for the whole chrome: flat and translucent until focused, filled with
     * the accent when focused, mixed case, and an optional icon that follows the same colour
     * states. The minimum sizes are cleared because the theme's touch-sized ones are what made
     * a television interface look like a phone form.
     */
    private void stylePill(Button button, int iconRes) {
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.button_bg);
        button.setTextColor(getResources().getColorStateList(R.color.button_text));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                getResources().getDimension(R.dimen.chrome_text_size)
                        / getResources().getDisplayMetrics().density);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        if (iconRes != 0) {
            button.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(8));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                button.setCompoundDrawableTintList(
                        getResources().getColorStateList(R.color.button_text));
            }
        }
    }

    /** The TV safe area, in pixels. Both screens use it, so overscan cannot eat either. */
    private int insetPx() {
        return (int) getResources().getDimension(R.dimen.screen_inset);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
