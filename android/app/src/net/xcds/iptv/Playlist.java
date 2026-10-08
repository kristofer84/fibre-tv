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

    /** What the source label says when the list has never been touched. */
    static final String BUILT_IN = "built-in";
    static final String EDITED = "edited on this device";

    private Playlist() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The saved m3u, or null when there is none worth reading. */
    static String savedText(Context context) {
        String text = prefs(context).getString(KEY_TEXT, null);
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /** Where the current list came from, for the settings screen to show. */
    static String source(Context context) {
        return prefs(context).getString(KEY_SOURCE, BUILT_IN);
    }

    /**
     * Bumped on every save and reset. The player screen keeps the revision it loaded
     * and reloads when this changes, which is how an edit reaches a running app without
     * the two activities having to know about each other.
     */
    static int revision(Context context) {
        return prefs(context).getInt(KEY_REVISION, 0);
    }

    /**
     * The list to play. Never empty unless the built-in list is missing too: the saved
     * text is only believed when it parses to at least one channel, and the asset is the
     * floor underneath that.
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
            // Without settings this could not happen, and it is not worth a crash now:
            // an unreadable asset means an empty list and a message on the card.
            Log.i(TAG, "playlist: cannot read assets/" + asset + " (" + e + ")");
            return new ArrayList<>();
        }
    }

    /**
     * Store the list, refusing anything that would leave nothing to play. Returns false
     * when it refused, so the caller can tell the viewer rather than pretend.
     */
    static boolean save(Context context, String text, String source) {
        int count = parseText(text).size();
        if (count == 0) {
            Log.i(TAG, "playlist: refused to save a list with no playable entries");
            return false;
        }
        prefs(context).edit()
                .putString(KEY_TEXT, text)
                .putString(KEY_SOURCE, source)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: saved " + describe(count) + " (" + source + ")");
        return true;
    }

    /** "1 channel" / "3 channels": these count messages are read by people. */
    static String describe(int count) {
        return count + (count == 1 ? " channel" : " channels");
    }

    /** Back to the built-in list, which is done by forgetting the saved one. */
    static void reset(Context context) {
        prefs(context).edit()
                .remove(KEY_TEXT)
                .putString(KEY_SOURCE, BUILT_IN)
                .putInt(KEY_REVISION, revision(context) + 1)
                .apply();
        Log.i(TAG, "playlist: reset to the built-in list");
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
