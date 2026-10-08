package net.xcds.iptv;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * The screen version of the settings panel: the route that still works when nothing plays, and the
 * one that suits a phone.
 *
 * It is deliberately thin. The panel is shared with the player's overlay, which is the normal way in,
 * and it is the overlay that matters: this activity runs the player's onStop and therefore stops the
 * stream and drops the multicast membership, which is the behaviour that keeps a forgotten stream off
 * the LAN. That is exactly why settings are an overlay now, and why what remains here is a fallback.
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "iptv";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "settings: opened as an activity - the player stops while this is up");
        SettingsPanel panel = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override
            public void settingsChanged() {
                // Nothing to do: the player notices the revision when it comes back to the
                // foreground and reloads then.
            }

            @Override
            public void closeSettings() {
                finish();
            }
        });
        setContentView(panel.build());
        panel.refresh();
    }
}
