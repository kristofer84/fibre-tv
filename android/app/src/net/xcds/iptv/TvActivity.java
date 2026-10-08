package net.xcds.iptv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
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
import java.util.List;

/**
 * The whole app: one activity that joins the channel's multicast group with libVLC
 * and plays it. There is no relay and no transcoding - this is the same engine and
 * the same rtp://@group:port URLs that VLC uses on a desktop, which is what makes
 * the AC3 and MP2 audio work.
 *
 * The streams carry two audio tracks (MPEG-1 Layer II and AC-3, both Swedish) and
 * three teletext subtitle pages, so both are selectable from the channel bar.
 *
 * The things that make it behave on Android rather than just on a desktop:
 *
 *   - a WifiManager.MulticastLock, without which the WiFi stack drops the group
 *     (libVLC does not take one for you);
 *   - playback is stopped in onStop, so leaving the app actually leaves the group
 *     instead of pulling 12-18 Mbit/s forever while nobody is watching;
 *   - attachViews/detachViews are made idempotent, because the system can call
 *     onStart on a vout that is already attached right after an in-place update,
 *     and libVLC throws IllegalStateException for that.
 */
public class TvActivity extends Activity implements IVLCVout.Callback {

    /** How long the channel bar and the status card stay up on their own. */
    private static final long IDLE_HIDE_MS = 4000;

    private static final String ASSET = "channels.m3u8";

    private static final String TAG = "iptv";

    private LibVLC libVLC;
    private MediaPlayer player;
    private IVLCVout vout;

    private SurfaceView surface;
    private TextView card;
    private HorizontalScrollView barScroll;
    private LinearLayout bar;
    private LinearLayout controls;
    private HorizontalScrollView controlsScroll;
    private Button audioButton;
    private Button subsButton;

    private final List<Channels.Channel> channels = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private int current = -1;

    /**
     * Track choice, remembered across channel changes. Both are ordinals rather
     * than track ids, because the ids are per-stream (they are transport PIDs) but
     * the ordering is the same on every channel here.
     */
    private int audioWanted;
    private int spuWanted;
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

        buildUi();

        channels.addAll(Channels.fromAssets(this, ASSET));
        if (channels.isEmpty()) {
            card.setText("no channels in assets/" + ASSET);
            return;
        }
        buildChannelBar();

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
        if (vout.areViewsAttached()) {
            vout.detachViews();
        }
        releaseLocks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(idleHide);
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
        controlsScroll.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
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
        }
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
        updateTrackButtons();

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
        return (current + 1) + "   " + channels.get(current).name;
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
     * Called whenever the track lists may have changed. Applies a remembered
     * choice once per tuning, then refreshes the two control buttons.
     */
    private void onTracksAvailable(String when) {
        List<MediaPlayer.TrackDescription> audio = audioTracks();
        List<MediaPlayer.TrackDescription> spu = spuTracks();
        if (audio.isEmpty() && spu.isEmpty()) {
            return;                     // nothing parsed yet
        }

        if (applyWanted) {
            applyWanted = false;
            if (!audio.isEmpty() && audioWanted > 0) {
                player.setAudioTrack(audio.get(audioWanted % audio.size()).id);
                Log.i(TAG, "applied audio ordinal " + audioWanted);
            }
            if (!spu.isEmpty() && spuWanted > 0) {
                player.setSpuTrack(spu.get(spuWanted % spu.size()).id);
                Log.i(TAG, "applied spu ordinal " + spuWanted);
            }
        }

        updateTrackButtons();
        Log.i(TAG, when + ": audio " + describe(player.getAudioTracks())
                + " current=" + player.getAudioTrack()
                + " | spu " + describe(player.getSpuTracks())
                + " current=" + player.getSpuTrack());
    }

    /** The selectable audio tracks: libVLC always offers "Disable" as id -1 first. */
    private List<MediaPlayer.TrackDescription> audioTracks() {
        return withoutDisable(player.getAudioTracks());
    }

    /** Subtitle tracks including "Disable", which is what "off" means here. */
    private List<MediaPlayer.TrackDescription> spuTracks() {
        List<MediaPlayer.TrackDescription> out = new ArrayList<>();
        MediaPlayer.TrackDescription[] tracks = player.getSpuTracks();
        if (tracks != null) {
            for (MediaPlayer.TrackDescription track : tracks) {
                if (track != null) {
                    out.add(track);
                }
            }
        }
        return out;
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

    private void cycleSubs() {
        List<MediaPlayer.TrackDescription> spu = spuTracks();
        if (spu.isEmpty()) {
            return;
        }
        spuWanted = (spuWanted + 1) % spu.size();
        player.setSpuTrack(spu.get(spuWanted).id);
        Log.i(TAG, "spu -> ordinal " + spuWanted + " id " + spu.get(spuWanted).id);
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
            int index = Math.min(audioWanted, audio.size() - 1);
            int id = player.getAudioTrack();
            String label = null;
            for (int i = 0; i < audio.size(); i++) {
                if (audio.get(i).id == id) {
                    index = i;
                    label = codecLabel(audio.get(i).id);
                    break;
                }
            }
            if (label == null) {
                label = codecLabel(audio.get(index).id);
            }
            audioButton.setText("Audio: " + (label != null ? label : "Track " + (index + 1))
                    + (audio.size() > 1 ? "  (" + (index + 1) + "/" + audio.size() + ")" : ""));
        }

        List<MediaPlayer.TrackDescription> spu = spuTracks();
        int spuId = player.getSpuTrack();
        String spuLabel = "off";
        for (MediaPlayer.TrackDescription track : spu) {
            if (track.id == spuId && track.id >= 0) {
                spuLabel = shortTrackName(track.name);
                break;
            }
        }
        subsButton.setText("Subs: " + spuLabel);
    }

    /**
     * "mp2"/"ac-3" and friends. Comes from the media's own track table, because
     * libVLC names both audio tracks here "Track 1 - [Swedish]" and "Track 2 -
     * [Swedish]" - the language is identical, so the name cannot tell MPEG-1
     * Layer II from AC-3 but the codec can.
     */
    private String codecLabel(int trackId) {
        IMedia media = player == null ? null : player.getMedia();
        if (media == null) {
            return null;
        }
        for (int i = 0; i < media.getTrackCount(); i++) {
            IMedia.Track track = media.getTrack(i);
            if (track == null || track.id != trackId) {
                continue;
            }
            String fourcc = fourcc(track.codec);
            if (fourcc.startsWith("mp2") || fourcc.startsWith("mpga") || fourcc.startsWith("mp3")) {
                return "MP2";
            }
            if (fourcc.startsWith("ac-3") || fourcc.startsWith("ac3")) {
                return "AC-3";
            }
            if (fourcc.startsWith("ec-3")) {
                return "E-AC-3";
            }
            if (fourcc.startsWith("aac")) {
                return "AAC";
            }
            return fourcc.isEmpty() ? null : fourcc.toUpperCase();
        }
        return null;
    }

    /** libVLC hands back a four-character code packed into an int. */
    private static String fourcc(int code) {
        char[] out = new char[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (char) ((code >> (8 * (3 - i))) & 0xff);
        }
        return new String(out).trim();
    }

    /**
     * Shorten what libVLC reports for the teletext pages, e.g.
     * "Teletext subtitles: hearing impaired - [Swedish]" -> "Subs HI (swe)".
     * The pages look near-identical otherwise, and on SVT1 one of them is Danish,
     * so the language has to stay visible.
     */
    private static String shortTrackName(String name) {
        if (name == null || name.isEmpty()) {
            return "(unnamed)";
        }
        String label = name;
        String language = "";
        int bracket = name.lastIndexOf('[');
        if (bracket >= 0) {
            int end = name.indexOf(']', bracket);
            if (end > bracket) {
                language = name.substring(bracket + 1, end).trim();
                label = name.substring(0, bracket).trim();
            }
        }
        if (label.endsWith("-")) {
            label = label.substring(0, label.length() - 1).trim();
        }
        label = label.replace("Teletext subtitles:", "Subs")
                     .replace("Teletext subtitles", "Subs")
                     .replace("Teletext", "Teletext");
        if (label.startsWith("Subs") && !label.equals("Subs")) {
            label = "Subs " + label.substring(4).trim();
        }
        if (!language.isEmpty()) {
            label = label + " (" + shortLanguage(language) + ")";
        }
        return label;
    }

    private static String shortLanguage(String language) {
        if (language.startsWith("Swedish")) {
            return "swe";
        }
        if (language.startsWith("Danish")) {
            return "dan";
        }
        if (language.startsWith("Norwegian")) {
            return "nor";
        }
        if (language.startsWith("Finnish")) {
            return "fin";
        }
        return language;
    }

    private static String describe(MediaPlayer.TrackDescription[] tracks) {
        if (tracks == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("[");
        for (MediaPlayer.TrackDescription track : tracks) {
            if (track == null) {
                continue;
            }
            out.append(track.id).append('=').append(track.name).append(' ');
        }
        return out.append(']').toString();
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
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // The framework only gets here when focus search finds nothing,
                // which is exactly "the bar is not in use" -> toggle it.
                if (chromeVisible()) {
                    hideChrome();
                } else {
                    showChromeFocused();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                // Reached only while the bar is hidden (a focused button eats
                // these first), so use them to bring it up.
                showChromeFocused();
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
