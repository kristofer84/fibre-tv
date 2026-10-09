package net.xcds.iptv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The channel list the app actually plays: an m3u the viewer has edited or imported,
 * or the one staged into assets when there is none.
 *
 * The saved form is m3u TEXT rather than a structured list. That is what makes reset a
 * single forget and import almost nothing - fetch the text, parse it - because
 * Channels.parse already reads the format and buildM3U writes the same format back. It
 * also means the stored form is the same thing the viewer would paste elsewhere, so
 * there is only one shape of the list to reason about.
 *
 * THE RULE THIS CLASS EXISTS TO KEEP: nothing here may leave the app without channels.
 * A save whose text parses to nothing is refused, so the settings screen can say so
 * instead of closing on a save that did not happen; and a saved list that parses to
 * nothing is discarded in favour of the built-in one, with a log line saying why. A
 * settings screen that can brick playback is worse than no settings screen.
 */
final class Playlist {

    private static final String TAG = "iptv";
    private static final String PREFS = "iptv";
    private static final String KEY_TEXT = "playlist_text";
    private static final String KEY_SOURCE = "playlist_source";
    private static final String KEY_REVISION = "playlist_revision";
    private static final String KEY_LAST_CHANNEL = "last_channel";
    private static final String KEY_IDS = "profile_ids";
    private static final String KEY_ACTIVE = "profile_active";

    /** The name a fresh profile gets: the built-in list is the LAN one. */
    static final String DEFAULT_PROFILE_NAME = "LAN";

    private static String keyName(String id) {
        return "profile." + id + ".name";
    }

    private static String keyText(String id) {
        return "profile." + id + ".text";
    }

    private static String keySource(String id) {
        return "profile." + id + ".source";
    }

    /** What the source label says when the list has never been touched. */
    static final String BUILT_IN = "built-in";
    static final String EDITED = "edited on this device";

    private Playlist() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Make sure a profile exists, migrating the single saved list that the first version of
     * the settings screen stored. Called from every entry point, so the player and the
     * settings screen always see the same shape.
     *
     * A profile is a name, an m3u text, and where that text came from. Empty text means the
     * built-in list applies, which is what a fresh LAN profile is - and it is a complete
     * list rather than a template, because the relay host differs between LAN and tunnel.
     */
    static void ensureProfile(Context context) {
        List<String> ids = profileIds(context);
        if (!ids.isEmpty()) {
            if (activeId(context) == null) {
                prefs(context).edit().putString(KEY_ACTIVE, ids.get(0)).apply();
            }
            return;
        }
        String legacyText = prefs(context).getString(KEY_TEXT, null);
        String legacySource = prefs(context).getString(KEY_SOURCE, null);
        String id = "p1";
        prefs(context).edit()
                .putString(KEY_IDS, id)
                .putString(keyName(id), DEFAULT_PROFILE_NAME)
                .putString(keyText(id), legacyText == null ? "" : legacyText)
                .putString(keySource(id), legacySource == null ? BUILT_IN : legacySource)
                .putString(KEY_ACTIVE, id)
                .apply();
        Log.i(TAG, "playlist: profile " + DEFAULT_PROFILE_NAME + " created"
                + (legacyText == null ? " (built-in list)" : " (from the saved list)"));
    }

    static List<String> profileIds(Context context) {
        String ids = prefs(context).getString(KEY_IDS, "");
        List<String> out = new ArrayList<>();
        for (String id : ids.split(",")) {
            if (!id.trim().isEmpty()) {
                out.add(id.trim());
            }
        }
        return out;
    }

    static String profileName(Context context, String id) {
        return prefs(context).getString(keyName(id), id);
    }

    static String activeId(Context context) {
        String id = prefs(context).getString(KEY_ACTIVE, null);
        List<String> ids = profileIds(context);
        return id != null && ids.contains(id) ? id : (ids.isEmpty() ? null : ids.get(0));
    }

    /** The name shown on the status card and in the settings screen. */
    static String activeName(Context context) {
        ensureProfile(context);
        String id = activeId(context);
        return id == null ? DEFAULT_PROFILE_NAME : profileName(context, id);
    }

    /** Switch lists. The caller reloads afterwards; nothing else changes here. */
    static void switchTo(Context context, String id) {
        ensureProfile(context);
        if (!profileIds(context).contains(id)) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_ACTIVE, id)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: switched to profile " + profileName(context, id));
    }

    /** A new profile holding a copy of the given list. */
    static void createProfile(Context context, String name, String text) {
        ensureProfile(context);
        List<String> ids = profileIds(context);
        String id = "p" + (System.currentTimeMillis() % 1000000L);
        ids.add(id);
        prefs(context).edit()
                .putString(KEY_IDS, join(ids))
                .putString(keyName(id), name)
                .putString(keyText(id), text == null ? "" : text)
                .putString(keySource(id),
                        text == null || text.trim().isEmpty() ? BUILT_IN : EDITED)
                .putString(KEY_ACTIVE, id)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: profile " + name + " created from the current list");
    }

    /**
     * Rename one profile, active or not: the settings panel edits every row's name now, so renaming
     * is per id rather than per active. No revision bump, because a name is not the list.
     */
    static void rename(Context context, String id, String name) {
        ensureProfile(context);
        if (id == null || name == null || name.trim().isEmpty()) {
            return;
        }
        prefs(context).edit().putString(keyName(id), name.trim()).apply();
        Log.i(TAG, "playlist: profile renamed to " + name.trim());
    }

    /** Removes the active profile. Refuses to remove the last one: there is always a list. */
    /**
     * Delete one profile, active or not; refuses the last one. Deleting the active profile moves to
     * whichever is first, and only then does the revision change - deleting a profile nobody is
     * watching must not re-tune the stream.
     */
    static boolean delete(Context context, String id) {
        ensureProfile(context);
        List<String> ids = profileIds(context);
        if (id == null || !ids.contains(id) || ids.size() <= 1) {
            Log.i(TAG, "playlist: refused to delete the only profile");
            return false;
        }
        String name = profileName(context, id);
        boolean wasActive = id.equals(activeId(context));
        ids.remove(id);
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(KEY_IDS, join(ids))
                .remove(keyName(id))
                .remove(keyText(id))
                .remove(keySource(id));
        if (wasActive) {
            edit.putString(KEY_ACTIVE, ids.get(0)).putInt(KEY_REVISION, revision(context) + 1);
        }
        edit.apply();
        Log.i(TAG, "playlist: deleted " + name + (wasActive ? ", now on " + activeName(context) : ""));
        return true;
    }

    /**
     * Bumped on every save, reset, switch and profile edit. The player screen keeps the
     * revision it loaded and reloads when this changes, which is how an edit reaches a
     * running app without the two activities having to know about each other.
     */
    private static String join(List<String> ids) {
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(id);
        }
        return out.toString();
    }

    static int revision(Context context) {
        return prefs(context).getInt(KEY_REVISION, 0);
    }

    /** "1 channel" / "3 channels": these count messages are read by people. */
    static String describe(int count) {
        return count + (count == 1 ? " channel" : " channels");
    }

    /**
     * The list to play. Never empty unless the built-in list is missing too: the saved text
     * is only believed when it parses to at least one channel, and the asset is the floor
     * underneath that.
     */
    static List<Channels.Channel> load(Context context, String asset) {
        String saved = savedText(context);
        if (saved != null) {
            List<Channels.Channel> parsed = parseText(saved);
            if (!parsed.isEmpty()) {
                return parsed;
            }
            Log.i(TAG, "playlist: the saved list parses to nothing, using the built-in list"
                    + " instead - fix or reset it in Settings");
        }
        try {
            List<Channels.Channel> builtIn = Channels.fromAssets(context, asset);
            if (builtIn.isEmpty()) {
                Log.i(TAG, "playlist: assets/" + asset + " holds no channels either");
            }
            return builtIn;
        } catch (RuntimeException e) {
            // An unreadable asset means an empty list and a message on the card, not a crash:
            // the settings screen exists to get out of that state.
            Log.i(TAG, "playlist: cannot read assets/" + asset + " (" + e + ")");
            return new ArrayList<>();
        }
    }

    /**
     * The address of the channel being watched, so a restart comes back to it. Keyed on the
     * URL rather than the index: the list is editable now, so an index can point at a different
     * channel after an edit while an address still identifies one channel.
     */
    static String lastChannel(Context context) {
        return prefs(context).getString(KEY_LAST_CHANNEL, null);
    }

    static void setLastChannel(Context context, String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs(context).edit().putString(KEY_LAST_CHANNEL, url).apply();
        }
    }

    /** The active profile's m3u, or null when there is none and the built-in list applies. */
    static String savedText(Context context) {
        ensureProfile(context);
        String id = activeId(context);
        String text = id == null ? null : prefs(context).getString(keyText(id), null);
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /** Where the active profile's list came from, for the settings screen to show. */
    static String source(Context context) {
        ensureProfile(context);
        String id = activeId(context);
        return id == null ? BUILT_IN : prefs(context).getString(keySource(id), BUILT_IN);
    }

    static boolean save(Context context, String text, String source) {
        int count = parseText(text).size();
        if (count == 0) {
            Log.i(TAG, "playlist: refused to save a list with no playable entries");
            return false;
        }
        ensureProfile(context);
        String id = activeId(context);
        prefs(context).edit()
                .putString(keyText(id), text)
                .putString(keySource(id), source)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: saved " + describe(count) + " (" + source + ", profile "
                + profileName(context, id) + ")");
        return true;
    }

    /** Back to the built-in list for this profile, which is done by forgetting its text. */
    static void reset(Context context) {
        ensureProfile(context);
        String id = activeId(context);
        prefs(context).edit()
                .remove(keyText(id))
                .putString(keySource(id), BUILT_IN)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: profile " + profileName(context, id)
                + " reset to the built-in list");
    }

    /**
     * Parses text from a preference, a file or the network. Never throws: a bad list is
     * an empty list, and every caller has something sensible to do with that.
     */
    static List<Channels.Channel> parseText(String text) {
        if (text == null) {
            return new ArrayList<>();
        }
        try (InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            return Channels.parse(in);
        } catch (IOException e) {
            Log.i(TAG, "playlist: cannot parse that list (" + e + ")");
            return new ArrayList<>();
        }
    }

    /**
     * The list back as m3u. Entries with no address are dropped here rather than saved
     * as duds: a half-typed row in the editor should not become a channel that fails to
     * tune. The name is dropped if it is blank, so an entry is never "#EXTINF:-1,".
     */
    static String buildM3U(List<Channels.Channel> channels) {
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        for (Channels.Channel channel : channels) {
            String url = channel.url == null ? "" : channel.url.trim();
            if (url.isEmpty()) {
                continue;
            }
            String name = channel.name == null ? "" : channel.name.trim();
            out.append("#EXTINF:-1,").append(name.isEmpty() ? url : name).append('\n');
            out.append(url).append('\n');
        }
        return out.toString();
    }
}
