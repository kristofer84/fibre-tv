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

        /**
         * "rtp://@233.171.129.211:5500" -> "233.171.129.211".
         *
         * The @ is VLC's "join this multicast group" sigil and is not part of the
         * address: passing it to InetAddress.getByName fails with
         * UnknownHostException. That went unnoticed while this only fed the card's
         * "joining..." text, and became fatal as soon as Sdt::serviceName used it
         * as a hostname.
         */
        String group() {
            int slash = url.lastIndexOf('/');
            int colon = url.lastIndexOf(':');
            String group = (slash >= 0 && colon > slash)
                    ? url.substring(slash + 1, colon) : url;
            return group.startsWith("@") ? group.substring(1) : group;
        }

        /** "rtp://@233.171.129.211:5500" -> 5500. */
        int port() {
            int colon = url.lastIndexOf(':');
            if (colon >= 0) {
                try {
                    return Integer.parseInt(url.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the default below
                }
            }
            return 5500;
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
