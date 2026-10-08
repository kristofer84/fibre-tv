package net.xcds.iptv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole app: one activity that joins the channel's multicast group with libVLC
 * and plays it. There is no relay and no transcoding - this is the same engine and
 * the same rtp://@group:port URLs that VLC uses on a desktop, which is what makes
 * the AC3 and MP2 audio work.
 *
 * The two things that make it behave on Android rather than just on a desktop:
 *
 *   - a WifiManager.MulticastLock, without which the WiFi stack drops the group
 *     (libVLC does not take one for you);
 *   - playback is stopped in onStop, so leaving the app actually leaves the group
 *     instead of pulling 12-18 Mbit/s forever while nobody is watching.
 */
public class TvActivity extends Activity implements IVLCVout.Callback {

    /** How long the channel bar and the status card stay up on their own. */
    private static final long IDLE_HIDE_MS = 4000;

    private static final String ASSET = "channels.m3u8";

    private LibVLC libVLC;
    private MediaPlayer player;
    private IVLCVout vout;

    private SurfaceView surface;
    private TextView card;
    private HorizontalScrollView barScroll;
    private LinearLayout bar;

    private final List<Channels.Channel> channels = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private int current = -1;

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
                if (barScroll.hasFocus() || bar.hasFocus()) {
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
        vout.attachViews();
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
        vout.detachViews();
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

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setBackgroundColor(0x80000000);
        barScroll.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        barParams.setMargins(dp(32), 0, dp(32), dp(32));
        root.addView(barScroll, barParams);

        setContentView(root);
        surface.requestFocus();
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
            button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        restartIdleTimer();
                    }
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMargins(dp(6), dp(6), dp(6), dp(6));
            bar.addView(button, p);
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
                showCard(title());
                restartIdleTimer();
                break;
            case MediaPlayer.Event.Opening:
                showCard(title() + "\njoining, please wait");
                break;
            case MediaPlayer.Event.Buffering:
                showCard(title() + "\nbuffering");
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
            default:
                break;
        }
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
