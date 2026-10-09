package net.xcds.iptv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    /** The built-in list. Defined in Playlist, so the count and the loader cannot disagree. */
    private static final String ASSET = Playlist.ASSET;

    /** Long enough for a slow link, short enough that a dead URL is not a hang. */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** A playlist bigger than this is not a playlist. Stops a wrong URL filling memory. */
    private static final int MAX_BYTES = 1 << 20;

    /** The gap between rows in a block, in dp: a real gap, not a margin that happens to fit. */
    private static final int GAP_DP = 12;

    /** The two per-row actions, sized so their column matches the width Remove had on its own. */
    private static final int EDIT_ACTION_DP = 48;
    private static final int REMOVE_ACTION_DP = 64;
    private static final int ACTION_GAP_DP = 8;

    private static final String NAME_HINT = "name";
    private static final String URL_HINT = "rtp://@233.x.x.x:5500 or http://host:port/path";

    private final Activity host;
    private final Host callback;

    private LinearLayout rows;
    /** The control the D-pad should start on. See focusFirst. */
    private View firstControl;
    /** The profile rows: one per profile, with rename and delete on the row itself. */
    private LinearLayout profileList;
    /** The armed button waiting for its second tap, if any, and the label it had before. */
    private Button armedButton;
    private String armedNormalLabel = "Delete";
    /** The Edit button of the row currently in edit mode, if any. */
    private Button editingRow;
    private EditText[] editingFields;
    private String[] editingCollapsedHints;
    /** The add action, kept so focus can return to it: it is the row that never moves. */
    private Button newProfileButton;
    /** The channel add action, for the same reason: focus has to land somewhere sane. */
    private Button addChannelButton;
    private TextView status;
    private EditText delayField;
    private EditText trimFirstField;
    private EditText trimSecondField;
    private EditText trimOtherField;
    private EditText urlField;
    private Button importButton;

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
    /** A row of a given dp height, for the rows that carry two lines. */
    /** One action button in a row's right-hand column; the dp conversion happens here and nowhere else. */
    private LinearLayout.LayoutParams actionParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(widthDp), (int) host.getResources().getDimension(R.dimen.settings_field_height));
        params.setMargins(dp(ACTION_GAP_DP), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams rowParams(int gapDp, int heightDp) {
        LinearLayout.LayoutParams params = rowParams(gapDp);
        params.height = dp(heightDp);
        return params;
    }

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
        // One row per profile, the active one marked by a view state, with the actions on the row
        // itself. A list rather than a row of chips: rows do not reflow or scroll sideways as
        // profiles come and go, renaming happens where the name is, and it keeps working past the
        // number of chips that would fit across a screen.
        heading(root, "Profiles");

        // The add action sits above the list, not below it. Below, every add would push it one row
        // further down, and a second tap in the same place would land on the new row's Delete -
        // which on a phone is a tap away from arming a delete the viewer never aimed at. Above, it
        // never moves, and the new row appears directly beneath it.
        LinearLayout profileActions = new LinearLayout(host);
        profileActions.setOrientation(LinearLayout.HORIZONTAL);
        profileActions.setGravity(Gravity.CENTER_VERTICAL);
        newProfileButton = pill("New profile", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelArmed();
                String name = "Profile " + (Playlist.profileIds(host).size() + 1);
                Playlist.createProfile(host, name, Playlist.buildM3U(rowsAsChannels()));
                refresh();
                callback.settingsChanged();
                say("created " + name + " as a copy of the list that was on screen");
                // The rows were rebuilt, so whatever inside them had focus is gone. This button is
                // the row that did not move, so it is where the focus belongs - on a remote and on a
                // phone alike, it is what the viewer was pointing at.
                newProfileButton.requestFocus();
            }
        });
        profileActions.addView(newProfileButton);
        root.addView(profileActions, rowParams(GAP_DP));

        profileList = new LinearLayout(host);
        profileList.setOrientation(LinearLayout.VERTICAL);
        root.addView(profileList);

        // ---- channels block
        heading(root, "Channels");
        rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addChannelButton = pill("Add channel", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The fields are touch-only when collapsed and this is the D-pad path, so the way
                // into a new row is its own Edit button - the same one a viewer would press.
                channelRow("", "").performClick();
                say("add a name and an address, then Save");
            }
        });
        actions.addView(addChannelButton);
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
                cancelArmed();
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
    private Button channelRow(String name, String address) {
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
        field(nameField, NAME_HINT, 16f);
        nameField.setBackgroundResource(R.drawable.field_bg);
        nameField.setText(name);
        lines.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        final EditText addressField = new EditText(host);
        field(addressField, URL_HINT, 12f);
        addressField.setBackgroundResource(R.drawable.field_bg);
        addressField.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addressField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setText(address);
        lines.addView(addressField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        row.addView(lines);

        // The row's vertical focus stop is Edit. Right from it reaches Remove, and that is only true
        // because the fields themselves are not focusable until this row is being edited - which is
        // also what makes Down move one channel instead of dropping into a text field.
        final Button edit = pill("Edit", null);
        edit.setContentDescription("edit this channel");
        edit.setLayoutParams(actionParams(EDIT_ACTION_DP));
        row.addView(edit);

        final Button remove = pill("Remove", null);
        remove.setContentDescription("remove this channel");
        remove.setLayoutParams(actionParams(REMOVE_ACTION_DP));
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (armedButton != remove) {
                    // One press to the right and destructive: without a confirmation this change
                    // would make an accidental deletion easier, not merely navigation faster.
                    armButton(remove, "Remove", "Tap again",
                            "that will remove this channel from the list: "
                                    + "tap again, or anything else to cancel");
                    return;
                }
                cancelArmed();
                rows.removeView(row);
                say("removed a channel - press Save to keep it");
                // The removed row held the focus, so it has to land somewhere deliberate rather than
                // on the panel: this is the row that never moves, the same rule as the profile list.
                addChannelButton.requestFocus();
            }
        });
        row.addView(remove);

        wireEditRow(edit, new EditText[] {nameField, addressField},
                new String[] {NAME_HINT, URL_HINT}, new String[] {"Name", "Stream URL"});

        rows.addView(row);
        return edit;
    }

    // --------------------------------------------------------------- the state

    /** Reload every control from the stored settings: used on build and after a reset. */
    void refresh() {
        Playlist.ensureProfile(host);
        loadRows(Playlist.load(host, ASSET));
        fillProfileRows();
        delayField.setText(String.valueOf(AudioTuning.delayMs(host)));
        trimFirstField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.FIRST)));
        trimSecondField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.SECOND)));
        trimOtherField.setText(String.valueOf(AudioTuning.trim(host, AudioTuning.OTHER)));
        sayListInUse();
    }

    /** The panel's resting message, which the edit heading borrows and then gives back. */
    private void sayListInUse() {
        String source = Playlist.source(host);
        say("List in use: " + source
                + (Playlist.savedText(host) == null ? "" : "  (saved on this device)"));
    }

    private void loadRows(List<Channels.Channel> channels) {
        forgetEditRow();
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
     * One row per profile: the name, editable where it is, and Delete in the row's own column.
     *
     * The active profile is marked with setSelected on the row, which draws the same accent chip the
     * playing channel uses in the chrome - a view state rather than a character in a label, so it is
     * visible before anyone presses a key and cannot be confused with a name.
     *
     * The rows are rebuilt when the set of profiles changes, and that is the only thing that can
     * move them: every row owns its own actions, so an add or a remove cannot shuffle another row's.
     */
    private void fillProfileRows() {
        profileList.removeAllViews();
        firstControl = null;
        forgetEditRow();
        cancelArmed();
        for (final String id : Playlist.profileIds(host)) {
            final boolean active = id.equals(Playlist.activeId(host));

            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0);
            row.setTag(id);
            row.setSelected(active);                    // the active marker, as a state
            row.setBackgroundResource(R.drawable.field_bg);

            LinearLayout lines = new LinearLayout(host);
            lines.setOrientation(LinearLayout.VERTICAL);

            EditText name = new EditText(host);
            field(name, "profile name", spOf(R.dimen.settings_text_size));
            name.setBackgroundResource(R.drawable.field_bg);
            name.setText(Playlist.profileName(host, id));
            name.setContentDescription(active ? "profile name, current profile" : "profile name");
            lines.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

            // Provenance, per row, now that there is a row to put it in: the single "List in use"
            // line could only ever describe one profile, and with a list the obvious question about
            // a profile you are not using is what it holds and where it came from.
            TextView origin = new TextView(host);
            int count = Playlist.channelCount(host, id);
            origin.setText(count + (count == 1 ? " channel  ·  " : " channels  ·  ")
                    + Playlist.sourceOf(host, id));
            origin.setTextColor(0xFF9E9E9E);
            origin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            origin.setSingleLine(true);
            origin.setEllipsize(TextUtils.TruncateAt.END);
            origin.setPadding(dp(10), 0, 0, 0);
            lines.addView(origin, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

            row.addView(lines, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            final Button edit = pill("Edit", null);
            edit.setContentDescription("rename this profile");
            edit.setLayoutParams(actionParams(EDIT_ACTION_DP));
            row.addView(edit);

            final Button delete = pill("Delete", null);
            delete.setContentDescription("delete " + Playlist.profileName(host, id));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (armedButton != delete) {
                        armDelete(delete, id);
                        return;
                    }
                    cancelArmed();
                    String name = Playlist.profileName(host, id);
                    boolean deleted = Playlist.delete(host, id);
                    refresh();
                    callback.settingsChanged();
                    if (deleted) {
                        say("deleted " + name + "; now on " + Playlist.activeName(host));
                        if (firstControl != null) {
                            firstControl.requestFocus();     // a sensible row, not the container
                        }
                    } else {
                        say("that is the only profile, so it was not deleted");
                    }
                }
            });
            row.addView(delete, actionParams(REMOVE_ACTION_DP));

            wireEditRow(edit, new EditText[] {name},
                    new String[] {"profile name"}, new String[] {"Profile name"});

            // Two lines, so a height of its own - still fixed, so nothing can reflow it.
            profileList.addView(row, rowParams(GAP_DP, 64));
            if (active) {
                // The name field is touch-only when collapsed, so the row's focus stop is Edit.
                firstControl = edit;
            }
        }
    }

    /**
     * Collapsed, a row's fields are touch-only: a tap edits them on a phone, but the D-pad cannot
     * land in them, so Down moves between rows instead of dropping into text editing where Down
     * moves the caret. Edit makes that row's fields focusable, puts the focus in the first one, and
     * becomes Done at the same bounds - the same in-place two-state pattern the delete confirmation
     * uses, and the reason the row's geometry never changes.
     */
    /** "Name / Stream URL" from the labels the row shows while editing. */
    private static String joinLabels(String[] labels) {
        StringBuilder out = new StringBuilder();
        for (String label : labels) {
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(label);
        }
        return out.toString();
    }

    private void wireEditRow(final Button editButton, final EditText[] fields,
                             final String[] collapsedHints, final String[] editHints) {
        applyEditState(false, fields, collapsedHints, null);
        // While a row is being edited the fields sit to the left of Done, but a text field swallows
        // Right for the caret, so Done would be unreachable from a remote. Hand Right to the button
        // ourselves, and only while this row is the one being edited.
        final View.OnKeyListener toButton = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || !editButton.isSelected()) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    editButton.requestFocus();
                    return true;
                }
                return false;
            }
        };
        for (EditText field : fields) {
            field.setOnKeyListener(toButton);
        }
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean wasEditing = editButton.isSelected();
                if (!wasEditing && editingRow != null && editingRow != editButton) {
                    collapseEditRow();                     // one editable row at a time
                }
                editButton.setSelected(!wasEditing);
                editButton.setText(wasEditing ? "Edit" : "Done");
                applyEditState(!wasEditing, fields, collapsedHints, editHints);
                if (wasEditing) {
                    editingRow = null;
                    editingFields = null;
                    editingCollapsedHints = null;
                    sayListInUse();
                    editButton.requestFocus();             // never leave the focus nowhere
                } else {
                    editingRow = editButton;
                    editingFields = fields;
                    editingCollapsedHints = collapsedHints;
                    fields[0].requestFocus();
                    // The heading: the panel's own message line, so it names what is being edited
                    // without spending a single pixel of row geometry - which the row cannot afford,
                    // being a fixed height on purpose. The per-field hints still cover a new row.
                    say("Editing " + fields[0].getText() + " - " + joinLabels(editHints));
                }
            }
        });
    }

    /** Focusable only while its row is being edited; touch always. */
    private void applyEditState(boolean editing, EditText[] fields, String[] hints, String[] editHints) {
        for (int i = 0; i < fields.length; i++) {
            fields[i].setFocusable(editing);
            fields[i].setFocusableInTouchMode(true);
            fields[i].setHint(editing && editHints != null ? editHints[i] : hints[i]);
        }
    }

    /** Put the editing row back to collapsed. */
    private void collapseEditRow() {
        if (editingRow == null) {
            return;
        }
        editingRow.setSelected(false);
        editingRow.setText("Edit");
        applyEditState(false, editingFields, editingCollapsedHints, null);
        editingRow = null;
        editingFields = null;
        editingCollapsedHints = null;
        sayListInUse();
    }

    /** Forget an editing row whose views are about to be thrown away. */
    private void forgetEditRow() {
        editingRow = null;
        editingFields = null;
        editingCollapsedHints = null;
    }

    /**
     * Arm a button's second tap: the label swaps rather than grows, in a column of fixed width, so
     * nothing moves under the finger that is about to tap it again.
     */
    private void armButton(Button button, String normalLabel, String armedLabel, String message) {
        cancelArmed();
        armedButton = button;
        armedNormalLabel = normalLabel;
        button.setText(armedLabel);
        button.setSelected(true);                          // armed, drawn as the accent chip
        say(message);
    }

    /**
     * Arm a profile row's delete, naming what happens if it goes through. Deleting the profile in use
     * is the case a list made newly reachable, so it says which one takes over rather than leaving the
     * fallback to be discovered.
     */
    private void armDelete(Button button, String id) {
        boolean active = id.equals(Playlist.activeId(host));
        armButton(button, "Delete", "Tap again", active
                ? "that will forget the list you are using and switch to "
                        + Playlist.fallbackName(host, id) + ": tap again, or anything else to cancel"
                : "that will forget " + Playlist.profileName(host, id)
                        + "'s list: tap again, or anything else to cancel");
    }

    /** Disarm whatever was waiting for a second tap, putting its own label back. */
    private void cancelArmed() {
        if (armedButton != null) {
            armedButton.setText(armedNormalLabel);
            armedButton.setSelected(false);
            armedButton = null;
        }
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
        cancelArmed();
        saveAudio();
        // Every row is renamed, not just the active one: the name is edited in the row.
        for (int i = 0; i < profileList.getChildCount(); i++) {
            View row = profileList.getChildAt(i);
            if (!(row instanceof LinearLayout) || !(row.getTag() instanceof String)
                    || ((LinearLayout) row).getChildCount() == 0) {
                continue;
            }
            View first = ((LinearLayout) row).getChildAt(0);
            if (!(first instanceof EditText)) {
                continue;
            }
            Playlist.rename(host, (String) row.getTag(),
                    ((EditText) first).getText().toString());
        }
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
