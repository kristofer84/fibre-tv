package net.xcds.iptv;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

/**
 * Reads the name a channel gives itself, out of the DVB SDT.
 *
 * Hand-rolled because libVLC does not surface it: the media title for these
 * streams is the MRL ("rtp://233.171.129.211:5500"), which is what an earlier
 * attempt at this feature put on the channel bar. Confirmed on the device - meta
 * id 0 is populated, and it is the URL.
 *
 * Where the name lives, all of it read off the stream rather than assumed:
 *
 *   PID 0x0011   the SDT, table_id 0x42 (actual transport stream)
 *   descriptors  tag 0x48, the service_descriptor
 *   layout       service_type(1), provider_name_length(1) + provider,
 *                service_name_length(1) + name
 *
 * DVB strings may begin with a character-table selector byte in 0x01..0x1f, which
 * is where the "leading 0x15 byte" on Lokal kanal came from; it is stripped here
 * rather than being treated as text.
 *
 * The group is joined only for the probe and left immediately afterwards, so the
 * extra membership is short-lived. Everything is best-effort: any failure returns
 * null and the caller keeps the playlist's name.
 */
final class Sdt {

    private static final String TAG = "iptv";

    private static final int TS_PACKET = 188;
    private static final int TS_SYNC = 0x47;
    private static final int PID_SDT = 0x0011;
    private static final int TABLE_SDT_ACTUAL = 0x42;
    private static final int DESCRIPTOR_SERVICE = 0x48;

    private Sdt() {
    }

    /**
     * The service name for one group, or null. Blocks for at most timeoutMs, so
     * call it off the main thread.
     */
    static String serviceName(String group, int port, int timeoutMs) {
        MulticastSocket socket = null;
        int datagrams = 0;
        int tsPackets = 0;
        int sdtPackets = 0;
        String sample = null;
        try {
            InetAddress groupAddress = InetAddress.getByName(group);

            // reuse before bind: the player is already bound to this port for the
            // same group, and without it this bind fails outright.
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(timeoutMs);
            socket.bind(new InetSocketAddress(port));
            socket.joinGroup(groupAddress);

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                datagrams++;
                if (sample == null) {
                    sample = hexOf(buffer, packet.getLength()) + " len=" + packet.getLength();
                }
                int[] counts = countPackets(buffer, packet.getLength());
                tsPackets += counts[0];
                sdtPackets += counts[1];
                String name = parseDatagram(buffer, packet.getLength());
                if (name != null && !name.isEmpty()) {
                    Log.i(TAG, "sdt: " + group + " named '" + name + "' after "
                            + datagrams + " datagrams");
                    return name;
                }
            }
            // The whole point of this line: zero datagrams means the group is not
            // being delivered to a second socket at all, which is a different
            // problem from datagrams arriving and the parse failing.
            Log.i(TAG, "sdt: " + group + " no name: datagrams=" + datagrams
                    + " ts=" + tsPackets + " sdt=" + sdtPackets + " first=[" + sample + "]");
        } catch (IOException e) {
            Log.i(TAG, "sdt: " + group + " unavailable (" + e + ")");
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    /** The first bytes of a datagram, so a failed probe can say what arrived. */
    private static String hexOf(byte[] data, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length && i < 16; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format("%02x", data[i] & 0xff));
        }
        return out.toString();
    }

    /**
     * 12 if this datagram starts with an RTP header, otherwise 0.
     *
     * The mask matters: RTP version 2 sets the top two bits, so the first byte is
     * 0x80, which as a SIGNED Java byte is -128. "data[0] >> 6 == 2" therefore
     * never matches - it evaluates to -2 - and the header was never skipped, so the
     * scan ran at the wrong offsets and found almost nothing. The same expression
     * is correct in Python, where a byte is unsigned, which is where it came from.
     */
    private static int rtpHeaderLength(byte[] data, int length) {
        return (length > 12 && (data[0] & 0xc0) == 0x80) ? 12 : 0;
    }

    /** {transport packets, SDT packets} present in one datagram. */
    private static int[] countPackets(byte[] data, int length) {
        int offset = rtpHeaderLength(data, length);
        int ts = 0;
        int sdt = 0;
        for (int i = offset; i + TS_PACKET <= length; i += TS_PACKET) {
            if ((data[i] & 0xff) != TS_SYNC) {
                continue;
            }
            ts++;
            int pid = ((data[i + 1] & 0x1f) << 8) | (data[i + 2] & 0xff);
            if (pid == PID_SDT) {
                sdt++;
            }
        }
        return new int[] {ts, sdt};
    }

    /** A datagram is an RTP header followed by whole transport packets. */
    private static String parseDatagram(byte[] data, int length) {
        int offset = rtpHeaderLength(data, length);
        for (int i = offset; i + TS_PACKET <= length; i += TS_PACKET) {
            if ((data[i] & 0xff) != TS_SYNC) {
                continue;
            }
            int pid = ((data[i + 1] & 0x1f) << 8) | (data[i + 2] & 0xff);
            boolean startOfSection = (data[i + 1] & 0x40) != 0;
            if (pid != PID_SDT || !startOfSection) {
                continue;
            }
            String name = parseSection(data, i + 4, i + TS_PACKET);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /** One SDT section, walked to its first service_descriptor. */
    private static String parseSection(byte[] data, int start, int limit) {
        if (start + 1 > limit) {
            return null;
        }
        int offset = start + 1 + (data[start] & 0xff);      // pointer_field
        if (offset + 3 > limit || (data[offset] & 0xff) != TABLE_SDT_ACTUAL) {
            return null;
        }
        int sectionLength = ((data[offset + 1] & 0x0f) << 8) | (data[offset + 2] & 0xff);
        int end = Math.min(offset + 3 + sectionLength, limit);

        // transport_stream_id(2) version(1) section_number(1) last_section(1)
        // original_network_id(2) reserved(1)
        int service = offset + 3 + 8;
        while (service + 5 <= end) {
            int descriptorsLength = ((data[service + 3] & 0x0f) << 8) | (data[service + 4] & 0xff);
            int descriptor = service + 5;
            int descriptorsEnd = Math.min(descriptor + descriptorsLength, end);
            while (descriptor + 2 <= descriptorsEnd) {
                int tag = data[descriptor] & 0xff;
                int length = data[descriptor + 1] & 0xff;
                if (tag == DESCRIPTOR_SERVICE) {
                    return nameFromServiceDescriptor(data, descriptor + 2, length, end);
                }
                descriptor += 2 + length;
            }
            service = descriptorsEnd;
        }
        return null;
    }

    /** service_type(1), provider_name_length(1) + provider, then the name. */
    private static String nameFromServiceDescriptor(byte[] data, int start, int length, int limit) {
        if (length < 3 || start + length > limit) {
            return null;
        }
        int offset = start + 1;                             // service_type
        int providerLength = data[offset] & 0xff;
        offset += 1 + providerLength;
        if (offset >= start + length) {
            return null;
        }
        int nameLength = data[offset] & 0xff;
        offset += 1;
        if (nameLength == 0 || offset + nameLength > start + length) {
            return null;
        }
        if ((data[offset] & 0xff) >= 0x01 && (data[offset] & 0xff) <= 0x1f) {
            offset++;                                       // character table selector
            nameLength--;
        }
        if (nameLength <= 0) {
            return null;
        }
        return new String(data, offset, nameLength, StandardCharsets.ISO_8859_1).trim();
    }
}
