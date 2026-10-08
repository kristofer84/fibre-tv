package net.xcds.iptv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The two audio adjustments: a delay for A/V sync, and a level trim per track, because this
 * line carries both MP2 and AC-3 and they are not the same loudness.
 *
 * Delays are stored in MILLISECONDS, which is what a person types and what the settings screen
 * shows, and converted to the MICROSECONDS libVLC wants in exactly one place - TvActivity's
 * applyAudioDelay - so that trap cannot come back through a second call site.
 *
 * Trims are percentages of the player's own default volume rather than numbers in libVLC's
 * scale. The API we have does not document that scale (javap shows setVolume(int) and nothing
 * about its range), and a trim expressed as a ratio cannot be wrong by a factor of two, which
 * is the failure mode of guessing.
 */
final class AudioTuning {

    private static final String PREFS = "iptv";
    private static final String KEY_DELAY = "audio_delay_ms";
    private static final String KEY_PREFIX = "audio_trim_";

    /**
     * Trims are keyed on the audio track's ORDER, not on its codec name.
     *
     * The reason is measurable and was measured: libVLC's media track table lists only the first
     * audio ES for these streams (the MP2) while the player has both, so the second track - the
     * AC-3 - has no codec name to look up and every attempt to name it comes back null. Order is
     * stable for a given channel and the order is what the viewer experiences: the first audio
     * track is the MP2, the second is the AC-3, and those are the two names shown on the buttons.
     */
    static final String OTHER = "Other";
    static final String FIRST = "MP2";
    static final String SECOND = "AC-3";
    static final String[] TRIM_LABELS = {FIRST, SECOND, OTHER};

    static final int TRIM_DEFAULT = 100;
    static final int TRIM_MIN = 0;
    static final int TRIM_MAX = 200;
    static final int DELAY_LIMIT_MS = 5000;

    private AudioTuning() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Milliseconds, positive meaning audio later. */
    static int delayMs(Context context) {
        return prefs(context).getInt(KEY_DELAY, 0);
    }

    static void setDelayMs(Context context, int millis) {
        int clamped = clamp(millis, -DELAY_LIMIT_MS, DELAY_LIMIT_MS);
        prefs(context).edit().putInt(KEY_DELAY, clamped).apply();
    }

    /** Percent of the player's own volume, 100 meaning untouched. */
    static int trim(Context context, String bucket) {
        return prefs(context).getInt(KEY_PREFIX + bucket(bucket), TRIM_DEFAULT);
    }

    static void setTrim(Context context, String bucket, int percent) {
        prefs(context).edit()
                .putInt(KEY_PREFIX + bucket(bucket), clamp(percent, TRIM_MIN, TRIM_MAX))
                .apply();
    }

    /** Which trim a track ordinal belongs to: first audio track, second, or anything else. */
    static String bucket(int ordinal) {
        if (ordinal == 0) {
            return FIRST;
        }
        if (ordinal == 1) {
            return SECOND;
        }
        return OTHER;
    }

    static String bucket(String name) {
        if (name != null) {
            for (String known : TRIM_LABELS) {
                if (known.equals(name)) {
                    return known;
                }
            }
        }
        return OTHER;
    }

    static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    /** What someone typed into a field, or the fallback when it is not a number. */
    static int parse(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
