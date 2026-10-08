package net.xcds.iptv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The channel list, editable: a row per channel, add, remove, save, reset to the
 * built-in list, and import an m3u from a URL.
 *
 * The list lives as m3u text (see Playlist), and this screen is just a view of it: the
 * rows are built from whatever Playlist.load returns, Save writes the rows back as m3u,
 * and Reset forgets the saved text. There is no third representation to keep in step.
 *
 * Import exists because typing seven long URLs with a TV remote is not a real option:
 * it fetches the text on a background thread, parses it with the same reader the app
 * uses at startup, and shows what came back in the rows so it can be looked at before
 * being kept.
 *
 * Nothing here can leave the app without channels: Playlist refuses a save that parses
 * to nothing, and a failed fetch changes nothing at all.
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "iptv";
    private static final String ASSET = "channels.m3u8";

    /** Long enough for a slow link, short enough that a dead URL is not a hang. */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** A playlist bigger than this is not a playlist. Stops a wrong URL filling memory. */
    private static final int MAX_BYTES = 1 << 20;

    private LinearLayout rows;
    private TextView sourceLabel;
    private TextView status;
    private EditText urlField;
    private Button importButton;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        showSource();
        loadRows(Playlist.load(this, ASSET));
    }

    // ------------------------------------------------------------------- the UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Channels");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        sourceLabel = new TextView(this);
        sourceLabel.setTextColor(0xFF9E9E9E);
        sourceLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sourceLabel.setPadding(0, dp(4), 0, dp(12));
        root.addView(sourceLabel);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        root.addView(button("Add channel", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText name = addRow("", "", true);
                name.requestFocus();
                say("add a name and an address, then Save");
            }
        }));

        root.addView(button("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }));

        root.addView(button("Reset to the built-in list", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Takes effect immediately: reset is forgetting the saved text, so there
                // is nothing to confirm afterwards. The rows are refreshed to show it.
                Playlist.reset(SettingsActivity.this);
                loadRows(Playlist.load(SettingsActivity.this, ASSET));
                showSource();
                say("reset: the built-in list is active again, and is shown above");
            }
        }));

        TextView importTitle = new TextView(this);
        importTitle.setText("Import a playlist (m3u) from a URL");
        importTitle.setTextColor(Color.WHITE);
        importTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        importTitle.setPadding(0, dp(24), 0, dp(8));
        root.addView(importTitle);

        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setGravity(Gravity.CENTER_VERTICAL);

        urlField = new EditText(this);
        urlField.setHint("https://example/playlist.m3u");
        urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(0xFF757575);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSingleLine(true);
        importRow.addView(urlField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        importButton = new Button(this);
        importButton.setText("Import");
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromUrl();
            }
        });
        importRow.addView(importButton);
        root.addView(importRow);

        status = new TextView(this);
        status.setTextColor(0xFFB0BEC5);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        return scroll;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    /** One channel: name and address, and a way to drop it. */
    private EditText addRow(String name, String address, boolean focusName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText nameField = new EditText(this);
        nameField.setHint("name");
        nameField.setText(name);
        nameField.setTextColor(Color.WHITE);
        nameField.setHintTextColor(0xFF757575);
        nameField.setSingleLine(true);
        row.addView(nameField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));

        EditText addressField = new EditText(this);
        addressField.setHint("rtp://@233.184.48.101:5500  or  http://host:port/path");
        addressField.setText(address);
        addressField.setTextColor(Color.WHITE);
        addressField.setHintTextColor(0xFF757575);
        addressField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setSingleLine(true);
        row.addView(addressField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f));

        Button remove = new Button(this);
        remove.setText("Remove");
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rows.removeView(row);
                say("removed a channel - press Save to keep it");
            }
        });
        row.addView(remove);

        rows.addView(row);
        if (focusName) {
            nameField.requestFocus();
        }
        return nameField;
    }

    private void loadRows(List<Channels.Channel> channels) {
        rows.removeAllViews();
        for (Channels.Channel channel : channels) {
            addRow(channel.name, channel.url, false);
        }
    }

    private void showSource() {
        sourceLabel.setText("List in use: " + Playlist.source(this)
                + (Playlist.savedText(this) == null ? "" : "  (saved on this device)"));
    }

    private void say(String message) {
        status.setText(message);
        Log.i(TAG, "settings: " + message);
    }

    // --------------------------------------------------------------- the actions

    /** The rows as they stand, with blank addresses dropped rather than saved as duds. */
    private List<Channels.Channel> rowsAsChannels() {
        List<Channels.Channel> out = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof LinearLayout) || ((LinearLayout) child).getChildCount() < 2) {
                continue;
            }
            EditText nameField = (EditText) ((LinearLayout) child).getChildAt(0);
            EditText addressField = (EditText) ((LinearLayout) child).getChildAt(1);
            String address = addressField.getText().toString().trim();
            if (address.isEmpty()) {
                continue;
            }
            out.add(new Channels.Channel(nameField.getText().toString().trim(), address));
        }
        return out;
    }

    private void save() {
        String text = Playlist.buildM3U(rowsAsChannels());
        if (Playlist.save(this, text, Playlist.EDITED)) {
            Log.i(TAG, "settings: saved, returning to the player");
            finish();
        } else {
            // The refusal matters: a list with no addresses is not a list, and saving it
            // would leave the app with nothing to play.
            say("not saved: every channel needs an address");
        }
    }

    /**
     * Fetch a playlist and put it in the rows, so it can be looked at before it is kept.
     *
     * All of it mistakes-included: the HTTP status is checked, the body is capped, and the
     * text has to parse to at least one channel - which is what catches a URL that returns
     * an HTML error page with a 200, the case that would otherwise look like success and
     * save a list of nothing. A failure changes nothing at all.
     */
    private void importFromUrl() {
        final String url = urlField.getText().toString().trim();
        if (url.isEmpty()) {
            say("enter a URL first");
            return;
        }
        importButton.setEnabled(false);
        say("fetching " + url + " ...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = fetch(url);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        importButton.setEnabled(true);
                        applyImport(url, result);
                    }
                });
            }
        }, "playlist-import").start();
    }

    /** Returns the body, or null with the reason already logged and shown. */
    private String fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "settings: import failed, HTTP " + code + " from " + url);
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BYTES) {
                    Log.i(TAG, "settings: import failed, more than " + MAX_BYTES + " bytes");
                    return null;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.i(TAG, "settings: import failed, " + e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Caller is on the UI thread. */
    private void applyImport(String url, String text) {
        if (text == null) {
            say("could not fetch that URL - nothing has changed (see the log)");
            return;
        }
        List<Channels.Channel> parsed = Playlist.parseText(text);
        if (parsed.isEmpty()) {
            // Covers a 404 page that came back as 200, an HTML index, and a playlist whose
            // entries this reader does not understand.
            say("that URL is not an m3u with channels in it - nothing has changed");
            return;
        }
        loadRows(parsed);
        String source = "imported from " + url;
        if (!Playlist.save(this, Playlist.buildM3U(parsed), source)) {
            say("imported list had nothing playable - nothing has changed");
            return;
        }
        showSource();
        say("imported " + Playlist.describe(parsed.size()) + "; they are active now");
    }
}
