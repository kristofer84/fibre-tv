package net.xcds.iptv;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.URL;

/**
 * Where the sniffers that run beside libVLC get their transport stream.
 *
 * The player hands libVLC a URL and libVLC handles it, but the SDT probe and the teletext reader need
 * the same bytes themselves, and they were written assuming the channel is a multicast group. A relay
 * is an HTTP URL, so that assumption is the one thing keeping names and subtitles on the LAN only.
 * This is the seam: callers ask for a source and read packets, and do not know or care which
 * transport they got.
 *
 * Multicast is the original path and is kept as it was: reuse the address so the player's own bind
 * does not conflict, join the group, read RTP datagrams, and let the socket timeout surface as an
 * IOException exactly as it did before. HTTP is additive: one GET and a packetiser that emits
 * 188-byte packets, so the two parsers - which walk whole packets and already tolerate a 12 byte RTP
 * header or none - did not have to change at all. That was the point: the multicast path is
 * device-verified, so the refactor was arranged to leave it alone.
 *
 * Cost is deliberate. Every HTTP source here is a second connection to the relay, so the SDT probe is
 * one short connection and the teletext reader exists only while subtitles are on. Nothing here opens
 * a connection that outlives its use, and the http case says so in the log.
 */
final class Ts {

    private static final String TAG = "iptv";

    private static final int TS_PACKET = 188;
    private static final int TS_SYNC = 0x47;
    /** Enough to chain a few packets when resynchronising. */
    private static final int WINDOW = TS_PACKET * 32;

    private Ts() {
    }

    /** One transport stream, however it arrives. Callers read whole packets and close it. */
    interface Source extends Closeable {
        /**
         * The next packet, copied into buffer: always TS_PACKET bytes, or 0 at end of stream. May
         * throw SocketTimeoutException, which the callers treat as "nothing yet, keep waiting".
         */
        int readPacket(byte[] buffer) throws IOException;
    }

    /** True for the schemes the app plays directly off the network rather than through a relay. */
    static boolean isMulticast(String url) {
        return url != null && (url.startsWith("rtp://") || url.startsWith("udp://"));
    }

    static Source open(String url, int timeoutMs) throws IOException {
        if (isMulticast(url)) {
            return openMulticast(url, timeoutMs);
        }
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            return openHttp(url, timeoutMs);
        }
        // A file:// or similar has nothing to join and nothing to GET, so say so rather than
        // failing later with a class cast.
        throw new IOException("nothing to sniff for " + url);
    }

    /**
     * Whether the sniffers have any hope with this address: a multicast group they can join, or a
     * relay they can GET. Callers use it to skip the work - and to say so in the log - instead of
     * assuming every channel is a group.
     */
    static boolean canSniff(String url) {
        return isMulticast(url)
                || (url != null && (url.startsWith("http://") || url.startsWith("https://")));
    }

    // ------------------------------------------------------------------ multicast

    /** The original path, unchanged in behaviour: same options, same order, same exceptions. */
    private static Source openMulticast(String url, int timeoutMs) throws IOException {
        String group = groupOf(url);
        int port = portOf(url);
        if (group == null || port <= 0) {
            throw new IOException("not a multicast address: " + url);
        }
        // reuse before bind: the player is already bound to this port for the same group, and
        // without it this bind fails outright.
        final MulticastSocket socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.setSoTimeout(timeoutMs);
            socket.bind(new InetSocketAddress(port));
            socket.joinGroup(InetAddress.getByName(group));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return new Source() {
            @Override
            public int readPacket(byte[] buffer) throws IOException {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                return packet.getLength();
            }

            @Override
            public void close() {
                socket.close();
            }
        };
    }

    /** The group without the scheme and the @, or null. */
    static String groupOf(String url) {
        String rest = stripScheme(url);
        if (rest == null) {
            return null;
        }
        if (rest.startsWith("@")) {
            rest = rest.substring(1);
        }
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            rest = rest.substring(0, colon);
        }
        return rest.isEmpty() ? null : rest;
    }

    /** The port, or -1. */
    static int portOf(String url) {
        String rest = stripScheme(url);
        if (rest == null) {
            return -1;
        }
        int colon = rest.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= rest.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripScheme(String url) {
        if (url == null) {
            return null;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        return url.substring(scheme + 3);
    }

    // ------------------------------------------------------------------ http relay

    /**
     * One GET on the relay, read as a packet stream. A relay that answers with an error, or with
     * something that is not a transport stream, fails fast and says which, because the alternative is
     * a sniff that silently finds nothing and a bug report about names not working.
     */
    private static Source openHttp(String url, int timeoutMs) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "video/mp2t, application/octet-stream, */*");
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("relay answered HTTP " + code);
        }
        // Said out loud because this is the cost the relay case adds: a second connection, and the
        // reason it is short-lived is that the callers close it as soon as they have what they need.
        Log.i(TAG, "ts: " + url + " is a relay, so this is a second connection, closed when done");
        final InputStream in = connection.getInputStream();
        return new PacketStream(in, connection);
    }

    /**
     * Turns a byte stream into transport packets.
     *
     * Alignment is found from the sync byte rather than assumed, because a connection can be joined
     * mid-packet and because a relay may restart underneath us. Two packets in a row starting with
     * 0x47 is the test: one is a byte of payload that happens to look like a sync, two in a row at
     * exactly 188 apart is not.
     */
    private static final class PacketStream implements Source {

        private final InputStream in;
        private final HttpURLConnection connection;
        private final byte[] window = new byte[WINDOW];
        private int start;
        private int end;

        PacketStream(InputStream in, HttpURLConnection connection) {
            this.in = in;
            this.connection = connection;
        }

        @Override
        public int readPacket(byte[] out) throws IOException {
            while (true) {
                compact();
                if (end - start >= TS_PACKET && aligned()) {
                    System.arraycopy(window, start, out, 0, TS_PACKET);
                    start += TS_PACKET;
                    return TS_PACKET;
                }
                if (end - start >= 3 * TS_PACKET) {
                    if (!resync()) {
                        // Three packets' worth with nothing that looks like a transport stream in it:
                        // drop it rather than carry it around the buffer forever.
                        start = end;
                    }
                    continue;
                }
                int read = in.read(window, end, window.length - end);
                if (read < 0) {
                    return 0;                       // end of stream
                }
                end += read;
            }
        }

        /** Is the window positioned on a packet, judged against the next packet's sync byte? */
        private boolean aligned() {
            if ((window[start] & 0xff) != TS_SYNC) {
                return false;
            }
            return end - start < 2 * TS_PACKET || (window[start + TS_PACKET] & 0xff) == TS_SYNC;
        }

        /** Move the window to the first believable packet start. False if there is not one. */
        private boolean resync() {
            int available = end - start;
            for (int i = 0; i + TS_PACKET <= available; i++) {
                if ((window[start + i] & 0xff) != TS_SYNC) {
                    continue;
                }
                boolean chained = i + 2 * TS_PACKET > available
                        || (window[start + i + TS_PACKET] & 0xff) == TS_SYNC;
                if (chained) {
                    start += i;
                    return true;
                }
            }
            return false;
        }

        /** Reclaim the consumed prefix so a packet can always fit. */
        private void compact() {
            if (start == 0) {
                return;
            }
            int remaining = end - start;
            System.arraycopy(window, start, window, 0, remaining);
            start = 0;
            end = remaining;
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing a stream that is already gone is not worth a log line
            }
            connection.disconnect();
        }
    }
}
