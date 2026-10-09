package net.xcds.iptv;

import android.util.Log;

import java.io.IOException;

/**
 * The picture size, read from the stream's own H.264 sequence parameter set.
 *
 * Why this exists: libVLC will not tell us. getCurrentVideoTrack() stays empty when video goes out
 * through a Vout view, and the media track table lists only the first audio elementary stream, which
 * is a behaviour this app measured for the audio labels long before it wanted a resolution. So the
 * number comes from the same transport stream the SDT and teletext readers already read: one more
 * reader beside them, additive, and the verified video path is not involved at all.
 *
 * Cost, since a relay pays for connections: this is a third short read per tune, beside the SDT
 * probe and the teletext page probe. Each is independent and closes as soon as it has what it wants,
 * but merging the SDT and video reads into one connection is the cheap fix if the relay cost ever
 * becomes real - written here rather than remembered, because a message log is a stale source.
 *
 * A dash is the failure mode, never a guess. If no parameter set arrives in time the caller shows a
 * dash, and a parse that produces an implausible size is discarded rather than displayed.
 *
 * The value is in PIXELS. It is formatted into a string and nothing else - it must never reach
 * setTextSize, setPadding or a LayoutParams, where dp or sp would be meant. This is the units rule
 * from the README, and a resolution is exactly the kind of number that invites the mistake.
 */
final class Sps {

    private static final String TAG = "iptv";

    private static final int TS_PACKET = 188;
    private static final int TS_SYNC = 0x47;
    /** A dash: what the panel shows until a real size is known. */
    static final String UNKNOWN = "\u2014";

    /** Plausible display sizes. Anything outside this is a bad parse, not a big screen. */
    private static final int MIN_W = 160;
    private static final int MAX_W = 4096;
    private static final int MIN_H = 120;
    private static final int MAX_H = 2160;

    private Sps() {
    }

    /**
     * The picture size for a channel, as "1280x720", or null. Blocks for at most timeoutMs, so call
     * it off the main thread.
     */
    static String probe(String url, int timeoutMs) {
        Ts.Source source = null;
        int packets = 0;
        try {
            source = Ts.open(url, timeoutMs);
            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int length = source.readPacket(buffer);
                if (length == 0) {
                    Log.i(TAG, "video: stream ended before a parameter set arrived");
                    break;
                }
                packets++;
                String size = fromPackets(buffer, length);
                if (size != null) {
                    Log.i(TAG, "video: " + url + " is " + size + " after " + packets + " packets");
                    return size;
                }
            }
            Log.i(TAG, "video: " + url + " gave no parameter set in " + packets + " packets");
        } catch (IOException e) {
            Log.i(TAG, "video: " + url + " unavailable (" + e + ")");
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // already gone
                }
            }
        }
        return null;
    }

    /** Walk a datagram's transport packets looking for a parameter set. */
    private static String fromPackets(byte[] data, int length) {
        int offset = (length > 12 && (data[0] & 0xc0) == 0x80) ? 12 : 0;
        for (int i = offset; i + TS_PACKET <= length; i += TS_PACKET) {
            if ((data[i] & 0xff) != TS_SYNC) {
                continue;
            }
            int pid = ((data[i + 1] & 0x1f) << 8) | (data[i + 2] & 0xff);
            if (pid == 0x1fff) {
                continue;                                   // null packets carry nothing
            }
            int adaptation = (data[i + 3] >> 4) & 0x03;
            int start = i + 4;
            if ((adaptation & 0x02) != 0) {
                start += 1 + (data[start] & 0xff);
            }
            if ((adaptation & 0x01) == 0 || start >= i + TS_PACKET) {
                continue;
            }
            String size = fromPayload(data, start, i + TS_PACKET);
            if (size != null) {
                return size;
            }
        }
        return null;
    }

    /** The parameter set in one packet payload, if there is one and it parses. */
    private static String fromPayload(byte[] data, int start, int end) {
        for (int i = start; i + 4 < end; i++) {
            if (data[i] != 0 || data[i + 1] != 0 || data[i + 2] != 1) {
                continue;
            }
            if ((data[i + 3] & 0x1f) != 7) {               // 7 is a sequence parameter set
                continue;
            }
            int[] rbsp = unescape(data, i + 4, end);       // the NAL header is at i+3
            String size = parse(rbsp);
            if (size != null) {
                return size;
            }
        }
        return null;
    }

    /** Remove the emulation-prevention bytes: 00 00 03 becomes 00 00. */
    private static int[] unescape(byte[] data, int start, int end) {
        int[] out = new int[Math.max(0, end - start)];
        int n = 0;
        int zeros = 0;
        for (int i = start; i < end && n < out.length; i++) {
            int b = data[i] & 0xff;
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[n++] = b;
            zeros = (b == 0) ? zeros + 1 : 0;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    // ------------------------------------------------------------------ the parse

    /**
     * H.264 7.3.2.1.1, up to the cropping offsets. The cropped size is the displayed size, which is
     * the whole point: a 1080p stream is coded as 1920x1088 and cropped by four lines, so a parse
     * that ignores frame_cropping reports 1920x1088 and is wrong.
     */
    private static String parse(int[] rbsp) {
        Reader reader = new Reader(rbsp);
        try {
            reader.u(8);                                    // profile_idc
            reader.u(8);                                    // constraint flags and reserved
            reader.u(8);                                    // level_idc
            int id = reader.ue();                           // seq_parameter_set_id
            int chromaFormatIdc = 1;                        // 4:2:0 unless the profile says otherwise
            if (isHighProfile(reader.profile())) {
                chromaFormatIdc = reader.ue();
                if (chromaFormatIdc == 3) {
                    reader.u(1);                            // separate_colour_plane_flag
                }
                reader.ue();                                // bit_depth_luma_minus8
                reader.ue();                                // bit_depth_chroma_minus8
                reader.u(1);                                // qpprime_y_zero_transform_bypass_flag
                if (reader.u(1) == 1) {                     // seq_scaling_matrix_present_flag
                    int lists = (chromaFormatIdc == 3) ? 12 : 8;
                    for (int i = 0; i < lists; i++) {
                        if (reader.u(1) == 1) {
                            skipScalingList(reader, i < 6 ? 16 : 64);
                        }
                    }
                }
            }
            reader.ue();                                    // log2_max_frame_num_minus4
            int picOrderCntType = reader.ue();
            if (picOrderCntType == 0) {
                reader.ue();                                // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.u(1);                                // delta_pic_order_always_zero_flag
                reader.se();                                // offset_for_non_ref_pic
                reader.se();                                // offset_for_top_to_bottom_field
                int cycle = reader.ue();
                for (int i = 0; i < cycle; i++) {
                    reader.se();
                }
            }
            reader.ue();                                    // max_num_ref_frames
            reader.u(1);                                    // gaps_in_frame_num_value_allowed_flag
            int widthMbs = reader.ue() + 1;
            int heightMapUnits = reader.ue() + 1;
            int frameMbsOnly = reader.u(1);
            if (frameMbsOnly == 0) {
                reader.u(1);                                // mb_adaptive_frame_field_flag
            }
            reader.u(1);                                    // direct_8x8_inference_flag
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (reader.u(1) == 1) {                         // frame_cropping_flag
                cropLeft = reader.ue();
                cropRight = reader.ue();
                cropTop = reader.ue();
                cropBottom = reader.ue();
            }
            int width = widthMbs * 16;
            int height = heightMapUnits * 16 * (2 - frameMbsOnly);
            int subWidthC = (chromaFormatIdc == 1 || chromaFormatIdc == 2) ? 2 : 1;
            int subHeightC = (chromaFormatIdc == 1) ? 2 : 1;
            width -= (cropLeft + cropRight) * subWidthC;
            height -= (cropTop + cropBottom) * subHeightC * (2 - frameMbsOnly);
            if (id < 0 || width < MIN_W || width > MAX_W || height < MIN_H || height > MAX_H) {
                return null;                                // a bad parse, not a big screen
            }
            return width + "x" + height;
        } catch (RuntimeException e) {
            return null;                                    // ran off the end: not a parameter set
        }
    }

    private static boolean isHighProfile(int profile) {
        return profile == 100 || profile == 110 || profile == 122 || profile == 244
                || profile == 44 || profile == 83 || profile == 86 || profile == 118
                || profile == 128 || profile == 138 || profile == 139 || profile == 134
                || profile == 135;
    }

    /** The scaling-list syntax, only ever skipped. */
    private static void skipScalingList(Reader reader, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int delta = reader.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    /** Bits, unsigned and exp-Golomb, over one parameter set. */
    private static final class Reader {
        private final int[] data;
        private int bit;

        Reader(int[] data) {
            this.data = data;
        }

        int profile() {
            return data.length > 0 ? data[0] : -1;
        }

        int u(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | bit();
            }
            return value;
        }

        /** Unsigned exp-Golomb: leading zeros, then the value. */
        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                zeros++;
                if (zeros > 32) {
                    throw new IllegalStateException("exp-Golomb run too long");
                }
            }
            int value = 1;
            for (int i = 0; i < zeros; i++) {
                value = (value << 1) | bit();
            }
            return value - 1;
        }

        /** Signed exp-Golomb, mapped as the standard says. */
        int se() {
            int value = ue();
            int magnitude = (value + 1) / 2;
            return ((value & 1) == 1) ? magnitude : -magnitude;
        }

        private int bit() {
            if (bit >= data.length * 8) {
                throw new IllegalStateException("out of bits");
            }
            int value = (data[bit >> 3] >> (7 - (bit & 7))) & 1;
            bit++;
            return value;
        }
    }
}
