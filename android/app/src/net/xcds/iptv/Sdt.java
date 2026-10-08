package net.xcds.iptv;

import android.util.Log;

import java.io.IOException;
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
 * The stream is opened only for the probe and closed immediately afterwards, so the
 * extra membership - or, on a relay, the extra connection - is short-lived. Which one
 * it is depends on the channel's URL; Ts decides, this class does not care. Everything
 * is best-effort: any failure returns null and the caller keeps the playlist's name.
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
    static String serviceName(String url, int timeoutMs) {
        Ts.Source source = null;
        int datagrams = 0;
        int sdtPackets = 0;
        try {
            source = Ts.open(url, timeoutMs);

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int length = source.readPacket(buffer);
                if (length == 0) {
                    Log.i(TAG, "sdt: " + url + " ended before it named anything");
                    break;
                }
                datagrams++;
                sdtPackets += countSdtPackets(buffer, length);
                String name = parseDatagram(buffer, length);
                if (name != null && !name.isEmpty()) {
                    Log.i(TAG, "sdt: " + url + " named '" + name + "' after "
                            + datagrams + " datagrams");
                    return name;
                }
            }
            // Kept on purpose: this is the only signal if a channel stops naming
            // itself, and the SDT count is what says whether the section was there
            // at all, or whether the parse is at fault.
            Log.i(TAG, "sdt: " + url + " gave no service name (" + datagrams
                    + " datagrams, " + sdtPackets + " SDT packets)");
        } catch (IOException e) {
            Log.i(TAG, "sdt: " + url + " unavailable (" + e + ")");
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // already gone; nothing to report
                }
            }
        }
        return null;
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

    /** How many SDT packets a datagram held. Only used for the failure log. */
    private static int countSdtPackets(byte[] data, int length) {
        int offset = rtpHeaderLength(data, length);
        int sdt = 0;
        for (int i = offset; i + TS_PACKET <= length; i += TS_PACKET) {
            if ((data[i] & 0xff) != TS_SYNC) {
                continue;
            }
            int pid = ((data[i + 1] & 0x1f) << 8) | (data[i + 2] & 0xff);
            if (pid == PID_SDT) {
                sdt++;
            }
        }
        return sdt;
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
