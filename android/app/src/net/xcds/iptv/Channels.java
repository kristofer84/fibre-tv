package net.xcds.iptv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The channel list, read from the m3u staged into assets from the repository root
 * (see build-apk.sh) - the same file that documents the channels.
 */
final class Channels {

    static final class Channel {
        final String name;
        final String url;

        Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }

        /** "rtp://@233.171.129.211:5500" -> "233.171.129.211". */
        String group() {
            int slash = url.lastIndexOf('/');
            int colon = url.lastIndexOf(':');
            return (slash >= 0 && colon > slash) ? url.substring(slash + 1, colon) : url;
        }
    }

    private Channels() {
    }

    static List<Channel> fromAssets(Context context, String asset) {
        try (InputStream in = context.getAssets().open(asset)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read assets/" + asset, e);
        }
    }

    /**
     * Minimal m3u reader: "#EXTINF... , Name" names the entry that follows it.
     * Comments and blank lines are skipped, so the annotations at the top of
     * channels.m3u8 are harmless.
     */
    static List<Channel> parse(InputStream in) throws IOException {
        List<Channel> out = new ArrayList<>();
        String pending = null;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                pending = nameOf(line);
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            out.add(new Channel(pending != null ? pending : line, line));
            pending = null;
        }
        return out;
    }

    /** The display name is whatever follows the last comma on an #EXTINF line. */
    private static String nameOf(String extinf) {
        int comma = extinf.lastIndexOf(',');
        if (comma >= 0 && comma + 1 < extinf.length()) {
            String name = extinf.substring(comma + 1).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return extinf;
    }
}
