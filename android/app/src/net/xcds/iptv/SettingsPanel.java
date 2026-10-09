package net.xcds.iptv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
 * The settings UI, as one construction used by two hosts.
 *
 * WHY TWO HOSTS. Opening settings used to be a separate Activity, which runs the player's onStop -
 * and onStop deliberately stops playback and drops the multicast membership, because that is what
 * stops a forgotten stream flooding the LAN. That invariant is worth more than the overlay, so the
 * overlay is hosted by the player instead: the panel is added to the player's own view tree, over
 * the video, and the player's lifecycle never changes. Playback, the membership and the teletext
 * reader all keep running while the list is edited.
 *
 * The standalone SettingsActivity stays for the cases the overlay cannot serve: a playlist so broken
 * that nothing plays, and a phone. Both hosts call build() and get the same screen.
 *
 * The panel only ever talks back through Host, so it does not care which host it is in.
 */
final class SettingsPanel {

    interface Host {
        /** The playlist or the audio settings changed: reload and reapply them. */
        void settingsChanged();

        /** The panel is finished with; the host decides what that means. */
        void closeSettings();
    }

    private static final String TAG = "iptv";
    private static final String ASSET = "channels.m3u8";

    /** Long enough for a slow link, short enough that a dead URL is not a hang. */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** A playlist bigger than this is not a playlist. Stops a wrong URL filling memory. */
    private static final int MAX_BYTES = 1 << 20;

    /** The gap between rows in a block, in dp: a real gap, not a margin that happens to fit. */
    private static final int GAP_DP = 12;

    private final Activity host;
    private final Host callback;

    private LinearLayout rows;
    /** The control the D-pad should start on. See focusFirst. */
    private View firstControl;
    /** The chip row: one fixed height, scrolls sideways when the profiles do not fit. */
    private HorizontalScrollView chipScroll;
    private LinearLayout chips;
    private EditText profileNameField;
    private TextView status;
    private EditText delayField;
    private EditText trimFirstField;
    private EditText trimSecondField;
    private EditText trimOtherField;
    private EditText urlField;
    private Button importButton;
    private Button deleteButton;
    private boolean deletePending;

    SettingsPanel(Activity host, Host callback) {
        this.host = host;
        this.callback = callback;
    }

    /**
     * A row of the panel: full width, one fixed height, and a gap above it.
     *
     * Every row goes through this, because a row whose height depends on its own text is a row whose
     * height changes with density, font scale or language - and that is how the profile chips ended
     * up underneath the field below them on a phone, at a density the 14dp margin they had been given
     * was never right for.
     */
    private LinearLayout.LayoutParams rowParams(int gapDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) host.getResources().getDimension(R.dimen.settings_field_height));
        // dp() on the way in: the caller passes dp and this converts. Passing 12 straight to
        // setMargins is 12 *pixels*, which is a 4dp gap at 480dpi - the kind of mistake that looks
        // fine on the device it was written for and thin everywhere else.
        params.setMargins(0, dp(gapDp), 0, 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * host.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int insetPx() {
        return (int) host.getResources().getDimension(R.dimen.screen_inset);
    }

    /** A dimension resource, as sp. Named so a raw sp value cannot be passed by mistake. */
    private float spOf(int dimen) {
        return host.getResources().getDimension(dimen)
                / host.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ the view

    /**
     * The panel: a centred column inside the screen inset, grouped into blocks with headings, so it
     * reads as a settings list rather than a wall of rows. Each host wraps this in whatever it needs
     * - the overlay adds the scrim, the Activity just shows it.
     */
    View build() {
        // The card is centred and never reaches a screen edge. It takes the height that is left
        // after its margins and the content scrolls inside it, rather than hugging the content: a
        // panel that hugged its content would put its own edge back on the screen edge as soon as
        // the list was long, which is every list with a full channel count. The content is centred
        // within the card, so a short list sits in the middle and a long one starts at the top and
        // scrolls.
        LinearLayout centring = new LinearLayout(host);
        centring.setOrientation(LinearLayout.VERTICAL);
        centring.setGravity(Gravity.CENTER_HORIZONTAL);

        // The panel carries its own surface. On a television the video can be a hardware plane that
        // the app's own scrim cannot dim, so legibility cannot depend on the overlay behind it: the
        // picture stays visible around the column, and the column reads over anything.
        FrameLayout card = new FrameLayout(host);
        card.setBackgroundResource(R.drawable.card_bg);

        ScrollView scroll = new ScrollView(host);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(insetPx(), dp(24), insetPx(), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // ---- profiles block
        // Two fixed rows, not one wrapping row. The chips scroll sideways in the first; the buttons
        // that change the list sit in the second. So adding or removing a profile moves nothing and
        // changes no height - on a phone the single row reflowed, and a label that wrapped at a
        // different density pushed the chips underneath the field below them. Every row in this panel
        // has an explicit height for that same reason.
        heading(root, "Profiles");

        chipScroll = new HorizontalScrollView(host);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(host);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chips, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(chipScroll, rowParams(0));

        LinearLayout profileActions = new LinearLayout(host);
        profileActions.setOrientation(LinearLayout.HORIZONTAL);
        profileActions.setGravity(Gravity.CENTER_VERTICAL);
        profileActions.addView(pill("New profile", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelDelete();
                String name = "Profile " + (Playlist.profileIds(host).size() + 1);
                Playlist.createProfile(host, name, Playlist.buildM3U(rowsAsChannels()));
                refresh();
                callback.settingsChanged();
                say("created " + name + " as a copy of the list that was on screen");
            }
        }));
        deleteButton = pill("Delete this profile", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        profileActions.addView(deleteButton);
        root.addView(profileActions, rowParams(GAP_DP));

        LinearLayout nameRow = new LinearLayout(host);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        label(nameRow, "Profile name");
        profileNameField = control(nameRow, "profile name", null);
        root.addView(nameRow, rowParams(GAP_DP));

        // ---- channels block
        heading(root, "Channels");
        rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(pill("Add channel", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText name = channelRow("", "");
                name.requestFocus();
                say("add a name and an address, then Save");
            }
        }));
        actions.addView(pill("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }));
        actions.addView(pill("Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callback.closeSettings();
            }
        }));
        root.addView(actions, rowParams(GAP_DP));

        // ---- audio block
        heading(root, "Audio");
        note(root, "Delay shifts audio against picture. Trims are percentages of normal: the first"
                + " audio track is the MP2 and the second is the AC-3, and they are rarely the same"
                + " loudness.");
        delayField = labelledField(root, "Delay (ms)", "audio delay in milliseconds");
        trimFirstField = labelledField(root, "MP2 (1st) %", "first audio track volume percent");
        trimSecondField = labelledField(root, "AC-3 (2nd) %", "second audio track volume percent");
        trimOtherField = labelledField(root, "Other %", "other tracks volume percent");

        // ---- import block
        heading(root, "Import a playlist");
        note(root, "An m3u from a URL: point this profile at your own LAN or relay list.");
        LinearLayout importRow = new LinearLayout(host);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setGravity(Gravity.CENTER_VERTICAL);
        urlField = control(importRow, "https://example/playlist.m3u", null);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        importButton = pill("Import", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromUrl();
            }
        });
        importRow.addView(importButton);
        root.addView(importRow, rowParams(GAP_DP));

        // ---- about and status
        LinearLayout aboutRow = new LinearLayout(host);
        aboutRow.setOrientation(LinearLayout.HORIZONTAL);
        aboutRow.addView(pill("Reset to the built-in list", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelDelete();
                Playlist.reset(host);
                refresh();
                callback.settingsChanged();
                say("reset: the built-in list is active again, and is shown above");
            }
        }));
        aboutRow.addView(pill("About and licences", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.startActivity(new Intent(host, AboutActivity.class));
            }
        }));
        root.addView(aboutRow, rowParams(GAP_DP));

        status = new TextView(host);
        status.setTextColor(0xFF9E9E9E);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, spOf(R.dimen.settings_text_size));
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        card.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                columnPx(), 0, 1f);
        cardParams.setMargins(dp(8), dp(32), dp(8), dp(32));
        centring.addView(card, cardParams);

        return centring;
    }

    /** A block heading: the grouping is the whole difference between a list and a form. */
    private void heading(LinearLayout parent, String text) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setPadding(0, dp(20), 0, dp(6));
        parent.addView(view);
    }

    private void note(LinearLayout parent, String text) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextColor(0xFF9E9E9E);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, spOf(R.dimen.settings_text_size));
        view.setPadding(0, 0, 0, dp(4));
        parent.addView(view);
    }

    private void label(LinearLayout parent, String text) {
        TextView view = new TextView(host);
        view.setText(text);
        view.setTextColor(0xFF9E9E9E);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, spOf(R.dimen.settings_text_size));
        view.setSingleLine(true);                   // a label that wraps moves everything below it
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMinWidth(dp(120));
        parent.addView(view);
    }

    private int columnPx() {
        int available = host.getResources().getDisplayMetrics().widthPixels - 2 * insetPx();
        return Math.min(available, (int) host.getResources().getDimension(R.dimen.content_column));
    }

    // ------------------------------------------------------------------ widgets

    /** The TV pill: flat until focused, filled accent when focused, mixed case. */
    private Button pill(String label, View.OnClickListener listener) {
        Button button = new Button(host);
        button.setText(label);
        button.setOnClickListener(listener);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.button_bg);
        button.setTextColor(host.getResources().getColorStateList(R.color.button_text));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, spOf(R.dimen.settings_text_size));
        button.setPadding(dp(14), 0, dp(14), 0);
        // One line, always: a pill that wraps is a pill of a different height, which is what moved
        // things around at another density. A long label ellipsises instead of growing.
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) host.getResources().getDimension(R.dimen.settings_field_height));
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    /**
     * A field sized for a television. Single line and ellipsised: an address is reference material,
     * and a long one should truncate deliberately rather than push the row around.
     */
    private void field(EditText field, String hint, float textSp) {
        field.setHint(hint);
        field.setTextColor(host.getResources().getColorStateList(R.color.field_text));
        field.setHintTextColor(host.getResources().getColorStateList(R.color.field_text));
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        field.setPadding(dp(10), dp(6), dp(10), dp(6));
        field.setBackgroundResource(R.drawable.button_bg);
    }

    /**
     * A control that is meant to be typed into, at the height the field resource asks for. These
     * keep a visible pill surface, unlike the channel rows: a name and an address are read, but a
     * delay in milliseconds is a control.
     */
    private EditText control(LinearLayout row, String hint, String description) {
        EditText field = new EditText(host);
        field(field, hint, spOf(R.dimen.settings_text_size));
        if (description != null) {
            field.setContentDescription(description);
        }
        row.addView(field, new LinearLayout.LayoutParams(
                0, (int) host.getResources().getDimension(R.dimen.settings_field_height), 1f));
        return field;
    }

    private EditText labelledField(LinearLayout parent, String labelText, String description) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        label(row, labelText);
        parent.addView(row, rowParams(GAP_DP));
        return control(row, "", description);
    }

    /**
     * One channel, as two lines: the name prominent and the address secondary and dimmer, with
     * Remove in its own column so every row lines up. The name and the address are both editable,
     * which is why they are fields rather than text.
     */
    private EditText channelRow(String name, String address) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        LinearLayout lines = new LinearLayout(host);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Explicit heights for the two lines: left to wrap, a field's own height plus the pill
        // padding made rows collide, which is what the first version of this looked like.
        final EditText nameField = new EditText(host);
        field(nameField, "name", 16f);
        nameField.setBackgroundResource(R.drawable.field_bg);
        nameField.setText(name);
        lines.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        final EditText addressField = new EditText(host);
        field(addressField, "rtp://@233.x.x.x:5500 or http://host:port/path", 12f);
        addressField.setBackgroundResource(R.drawable.field_bg);
        addressField.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addressField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setText(address);
        lines.addView(addressField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        row.addView(lines);

        Button remove = pill("Remove", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rows.removeView(row);
                say("removed a channel - press Save to keep it");
            }
        });
        // A fixed column width, so Remove lines up down the list instead of drifting with the text.
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                dp(120), (int) host.getResources().getDimension(R.dimen.settings_field_height));
        removeParams.setMargins(dp(8), 0, 0, 0);
        remove.setLayoutParams(removeParams);
        row.addView(remove);

        rows.addView(row);
        return nameField;
    }

    // --------------------------------------------------------------- the state

    /** Reload every control from the stored settings: used on build and after a reset. */
    void refresh() {
        Playlist.ensureProfile(host);
        loadRows(Playlist.load(host, ASSET));
        fillProfileChips();
        profileNameField.setText(Playlist.activeName(host));
        delayField.setText(String.valueOf(AudioTuning.delayMs(host)));
        trimFirstField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.FIRST)));
        trimSecondField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.SECOND)));
        trimOtherField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.OTHER)));
        String source = Playlist.source(host);
        say("List in use: " + source
                + (Playlist.savedText(host) == null ? "" : "  (saved on this device)"));
    }

    private void loadRows(List<Channels.Channel> channels) {
        rows.removeAllViews();
        for (Channels.Channel channel : channels) {
            channelRow(channel.name, channel.url);
        }
    }

    /**
     * One chip per profile, the active one marked with the accent chip - a view state rather than a
     * character in the label, so it styles with everything else and is announced properly.
     */
    /**
     * The chip row, rebuilt whenever the set of profiles changes - and only then. The buttons that
     * change the list are built once, in their own row, so adding or removing a profile cannot move
     * them; the row's height is fixed, so it cannot reflow either. The scroll position is put back
     * afterwards, because the viewer should keep looking at what they were looking at.
     */
    private void fillProfileChips() {
        final int scrollX = chipScroll.getScrollX();
        chips.removeAllViews();
        firstControl = null;
        for (final String id : Playlist.profileIds(host)) {
            String name = Playlist.profileName(host, id);
            boolean active = id.equals(Playlist.activeId(host));
            Button chip = pill(name, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (id.equals(Playlist.activeId(host))) {
                        return;
                    }
                    cancelDelete();
                    Playlist.switchTo(host, id);
                    refresh();
                    callback.settingsChanged();
                    say("switched to " + Playlist.activeName(host));
                }
            });
            chip.setSelected(active);
            if (active) {
                chip.setContentDescription(name + ", current profile");
                firstControl = chip;
            } else if (firstControl == null) {
                firstControl = chip;
            }
            chips.addView(chip);
        }
        // Posted: the row has to be measured before it can be scrolled.
        chipScroll.post(new Runnable() {
            @Override
            public void run() {
                chipScroll.scrollTo(scrollX, 0);
            }
        });
    }

    /**
     * Put the D-pad on the first control, not on the panel itself. Focusing the container looks the
     * same in a screenshot and behaves differently on a remote: its focus rectangle is the whole
     * screen, so the first Down is a focus search from an impossible position and lands on whatever
     * FocusFinder picks - on the device that was a Remove button at the bottom of the list.
     */
    void focusFirst() {
        if (firstControl != null) {
            firstControl.requestFocus();
        }
    }

    private void say(String message) {
        status.setText(message);
        Log.i(TAG, "settings: " + message);
    }

    // --------------------------------------------------------------- the actions

    private List<Channels.Channel> rowsAsChannels() {
        List<Channels.Channel> out = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                continue;
            }
            LinearLayout row = (LinearLayout) child;
            if (!(row.getChildAt(0) instanceof LinearLayout)) {
                continue;
            }
            LinearLayout lines = (LinearLayout) row.getChildAt(0);
            EditText nameField = (EditText) lines.getChildAt(0);
            EditText addressField = (EditText) lines.getChildAt(1);
            String address = addressField.getText().toString().trim();
            if (address.isEmpty()) {
                continue;
            }
            out.add(new Channels.Channel(nameField.getText().toString().trim(), address));
        }
        return out;
    }

    private void save() {
        cancelDelete();
        saveAudio();
        Playlist.rename(host, profileNameField.getText().toString());
        String text = Playlist.buildM3U(rowsAsChannels());
        if (Playlist.save(host, text, Playlist.EDITED)) {
            Log.i(TAG, "settings: saved");
            callback.settingsChanged();
            callback.closeSettings();
        } else {
            // A list with no addresses is not a list, and saving it would leave nothing to play.
            say("not saved: every channel needs an address");
        }
    }

    /**
     * Milliseconds here, converted to microseconds in exactly one place in the player, and trims
     * clamped so a typo cannot ask for a volume of 20000%.
     */
    private void saveAudio() {
        AudioTuning.setDelayMs(host, AudioTuning.clamp(
                AudioTuning.parse(delayField.getText().toString(), AudioTuning.delayMs(host)),
                -AudioTuning.DELAY_LIMIT_MS, AudioTuning.DELAY_LIMIT_MS));
        AudioTuning.setTrim(host, AudioTuning.FIRST, AudioTuning.parse(
                trimFirstField.getText().toString(), AudioTuning.TRIM_DEFAULT));
        AudioTuning.setTrim(host, AudioTuning.SECOND, AudioTuning.parse(
                trimSecondField.getText().toString(), AudioTuning.TRIM_DEFAULT));
        AudioTuning.setTrim(host, AudioTuning.OTHER, AudioTuning.parse(
                trimOtherField.getText().toString(), AudioTuning.TRIM_DEFAULT));
        delayField.setText(String.valueOf(AudioTuning.delayMs(host)));
        trimFirstField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.FIRST)));
        trimSecondField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.SECOND)));
        trimOtherField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.OTHER)));
        Log.i(TAG, "settings: audio delay " + AudioTuning.delayMs(host) + " ms, trims MP2 "
                + AudioTuning.trim(host, AudioTuning.FIRST) + "% AC-3 "
                + AudioTuning.trim(host, AudioTuning.SECOND) + "% other "
                + AudioTuning.trim(host, AudioTuning.OTHER) + "%");
    }

    /**
     * Deleting a list is the one action that loses something, so it asks - in place rather than with
     * a dialog, because a dialog on a remote is awkward and a button that says what the next tap will
     * do is just as clear. Any other action cancels it.
     */
    private void confirmDelete() {
        if (!deletePending) {
            deletePending = true;
            // Short, because the button's width must not change under the viewer's finger: the
            // profile being deleted is named in the line below the list instead.
            deleteButton.setText("Tap again to delete");
            say("that will forget this profile's list: tap Delete again, or anything else to cancel");
            return;
        }
        deletePending = false;
        String name = Playlist.activeName(host);
        if (Playlist.deleteActive(host)) {
            refresh();
            callback.settingsChanged();
            say("deleted " + name + "; now on " + Playlist.activeName(host));
        } else {
            deleteButton.setText("Delete this profile");
            say("that is the only profile, so it was not deleted");
        }
    }

    private void cancelDelete() {
        if (deletePending) {
            deletePending = false;
            if (deleteButton != null) {
                deleteButton.setText("Delete this profile");
            }
        }
    }

    /**
     * Fetch a playlist and show it before keeping it. The HTTP status is checked, the body is capped,
     * and the text has to parse to at least one channel - which is what catches a URL that returns
     * an HTML error page with a 200. A failure changes nothing at all.
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
                final String body = fetch(url);
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        importButton.setEnabled(true);
                        applyImport(url, body);
                    }
                });
            }
        }, "playlist-import").start();
    }

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
            say("that URL is not an m3u with channels in it - nothing has changed");
            return;
        }
        loadRows(parsed);
        String source = "imported from " + url;
        if (!Playlist.save(host, Playlist.buildM3U(parsed), source)) {
            say("imported list had nothing playable - nothing has changed");
            return;
        }
        callback.settingsChanged();
        say("imported " + Playlist.describe(parsed.size()) + "; they are active now");
    }
}
