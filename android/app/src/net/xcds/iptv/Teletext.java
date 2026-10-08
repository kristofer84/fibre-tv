package net.xcds.iptv;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the teletext subtitle pages of one channel and reports the lines
 * to draw.
 *
 * Why this exists rather than using libVLC's teletext decoder: VLC renders teletext
 * as a 40x25 character grid scaled up to the panel, which is legible but ugly. Doing
 * the decode here means the text can be drawn as text - a real font, real colour -
 * instead of an upscaled bitmap.
 *
 * The layout, all of it measured off the stream rather than assumed:
 *
 *   transport     RTP (12 bytes) -> MPEG-TS -> PES -> EN 300 472 data units
 *   data units    data_unit_id 0x02 is teletext, 0x03 is subtitle teletext; the
 *                 payload is 44 bytes: one leading byte, the 0xE4 framing code,
 *                 then the 2-byte address and 40 bytes of data
 *   address       two Hamming 8/4 bytes: magazine and packet number
 *   byte order    EVERY payload byte arrives bit-reversed, least significant bit
 *                 first. Without reversing, no address byte ever decodes and the
 *                 whole thing looks like noise - this is the single detail that cost
 *                 the most time.
 *   codewords     the Hamming 8/4 table from EN 300 706 8.2:
 *                 0x15 0x02 0x49 0x5E 0x64 0x73 0x38 0x2F
 *                 0xD0 0xC7 0x8C 0x9B 0xA1 0xB6 0xFD 0xEA
 *                 (index = the four data bits). The stream confirms it: every
 *                 address byte seen, reversed, lands in this set.
 *   page header   row 0 carries page units, page tens, four subcode bytes and the
 *                 control bits, all Hamming 8/4, the numbers in BCD
 *   text rows     1..25, one character per byte, seven bits plus odd parity
 *
 * The pages worth reading are discovered from the PMT rather than hardcoded: the
 * teletext descriptor (tag 0x56) lists each page with its language and whether it
 * is a subtitle page.
 */
final class Teletext implements Runnable {

    interface Listener {
        /** Lines to draw, top to bottom. Empty means nothing to show. */
        void onSubtitles(List<String> lines, String label);
    }

    private static final String TAG = "iptv";

    private static final int TS_PACKET = 188;
    private static final int TS_SYNC = 0x47;
    private static final int PID_PAT = 0x0000;
    private static final int DESCRIPTOR_TELETEXT = 0x56;
    private static final int TELETEXT_FRAMING = 0xE4;
    private static final int DATA_UNIT_TELETEXT = 0x02;
    private static final int DATA_UNIT_SUBTITLE = 0x03;
    private static final int LAST_ROW = 23;

    /** Hamming 8/4 codewords from EN 300 706 8.2, indexed by the data bits. */
    private static final int[] HAM8 = {
            0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F,
            0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA
    };

    private static final int[] HAM8_INVERSE = new int[256];
    private static final int[] REVERSED = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            HAM8_INVERSE[i] = -1;
        }
        for (int i = 0; i < HAM8.length; i++) {
            HAM8_INVERSE[HAM8[i]] = i;
        }
        for (int i = 0; i < 256; i++) {
            int reversed = 0;
            for (int bit = 0; bit < 8; bit++) {
                if ((i & (1 << bit)) != 0) {
                    reversed |= 1 << (7 - bit);
                }
            }
            REVERSED[i] = reversed;
        }
    }

    /** One subtitle page offered by the stream. */
    static final class Page {
        final int magazine;      // 1..8
        final int number;        // 0..99, the last two digits
        final String language;   // ISO 639, e.g. "swe"
        final boolean impaired;

        Page(int magazine, int number, String language, boolean impaired) {
            this.magazine = magazine;
            this.number = number;
            this.language = language;
            this.impaired = impaired;
        }

        int full() {
            return magazine * 100 + number;
        }

        /** "Swedish", "Danish", "Swedish HI" - short enough for a button. */
        String label() {
            String name;
            if ("swe".equals(language)) {
                name = "Swedish";
            } else if ("dan".equals(language)) {
                name = "Danish";
            } else if ("nor".equals(language)) {
                name = "Norwegian";
            } else if ("fin".equals(language)) {
                return language.toUpperCase() + (impaired ? " HI" : "");
            } else {
                name = language;
            }
            return impaired ? name + " HI" : name;
        }
    }

    /** The channel's URL: a multicast group or a relay, decided by Ts. */
    private final String url;
    private final Listener listener;

    /** Which page to follow, or null while none is selected. */
    private volatile Page wanted;
    private volatile boolean running = true;

    private final List<Page> pages = new ArrayList<>();
    private int teletextPid = -1;
    private int pmtPid = -1;
    private boolean pmtParsed;

    // current page assembly
    private final String[] rows = new String[32];
    private int assembling = -1;

    Teletext(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    List<Page> pages() {
        return pages;
    }

    void select(Page page) {
        wanted = page;
        synchronized (rows) {
            java.util.Arrays.fill(rows, null);
            assembling = -1;
        }
    }

    void stop() {
        running = false;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public void run() {
        Ts.Source source = null;
        byte[] pending = new byte[0];
        try {
            source = Ts.open(url, 2000);

            byte[] buffer = new byte[2048];
            while (running) {
                int length;
                try {
                    length = source.readPacket(buffer);
                } catch (IOException timeout) {
                    continue;                       // keeps the loop interruptible
                }
                if (length == 0) {
                    Log.i(TAG, "teletext: " + url + " ended");
                    break;
                }
                pending = consume(buffer, length, pending);
            }
        } catch (IOException e) {
            Log.i(TAG, "teletext: " + url + " unavailable (" + e + ")");
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // already gone; nothing to report
                }
            }
        }
    }

    /** Walks one datagram, returning the bytes left over from a split data unit. */
    private byte[] consume(byte[] data, int length, byte[] carry) {
        int offset = (length > 12 && (data[0] & 0xc0) == 0x80) ? 12 : 0;
        for (int i = offset; i + TS_PACKET <= length; i += TS_PACKET) {
            if ((data[i] & 0xff) != TS_SYNC) {
                continue;
            }
            int pid = ((data[i + 1] & 0x1f) << 8) | (data[i + 2] & 0xff);
            int adaptation = (data[i + 3] >> 4) & 0x03;
            int start = i + 4;
            if ((adaptation & 0x02) != 0) {
                start += 1 + (data[start] & 0xff);
            }
            if ((adaptation & 0x01) == 0 || start >= i + TS_PACKET) {
                continue;
            }
            byte[] payload = new byte[i + TS_PACKET - start];
            System.arraycopy(data, start, payload, 0, payload.length);

            boolean sectionStart = (data[i + 1] & 0x40) != 0;
            if (pid == PID_PAT || (pmtPid >= 0 && pid == pmtPid)) {
                parseProgramTables(payload, sectionStart, pid == PID_PAT);
            }
            if (pid != teletextPid || teletextPid < 0) {
                continue;
            }
            if ((data[i + 1] & 0x40) != 0) {
                // PES header: 00 00 01, stream id, length, flags, flags, header length
                if (payload.length < 9 || payload[0] != 0x00 || payload[1] != 0x00
                        || payload[2] != 0x01) {
                    continue;
                }
                int header = 9 + (payload[8] & 0xff);
                if (header >= payload.length) {
                    continue;
                }
                byte[] trimmed = new byte[payload.length - header];
                System.arraycopy(payload, header, trimmed, 0, trimmed.length);
                payload = trimmed;
            }
            carry = units(payload, carry);
        }
        return carry;
    }

    /**
     * Splits the byte stream into EN 300 472 data units, carrying split ones over.
     *
     * The resynchronisation is the whole point of the framing-code test. A socket
     * joins in the middle of a PES, so the first bytes it ever sees are the middle
     * of a data unit, and every PES payload then begins with the 0x10 data
     * identifier rather than a unit. A walker that trusts its own alignment stays
     * misaligned for the life of the socket and decodes nothing at all - while the
     * PAT and PMT keep parsing perfectly, because they do not use this walk. That
     * failure looks exactly like "the stream carries no pages".
     *
     * So a unit is only believed when its id is one of the two teletext ids, its
     * length is at least a teletext row, and the 0xE4 framing code sits where the
     * layout says it must. Anything else advances one byte and tries again.
     */
    private byte[] units(byte[] payload, byte[] carry) {
        byte[] joined = new byte[carry.length + payload.length];
        System.arraycopy(carry, 0, joined, 0, carry.length);
        System.arraycopy(payload, 0, joined, carry.length, payload.length);

        int at = 0;
        while (at + 2 <= joined.length) {
            int id = joined[at] & 0xff;
            int unitLength = joined[at + 1] & 0xff;
            boolean unit = (id == DATA_UNIT_TELETEXT || id == DATA_UNIT_SUBTITLE)
                    && unitLength >= 44
                    && at + 2 + unitLength <= joined.length
                    && (joined[at + 3] & 0xff) == TELETEXT_FRAMING;
            if (!unit) {
                at++;                   // resynchronise: the framing code is the anchor
                continue;
            }
            decodeUnit(joined, at + 2, id == DATA_UNIT_SUBTITLE);
            at += 2 + unitLength;
        }
        byte[] rest = new byte[joined.length - at];
        System.arraycopy(joined, at, rest, 0, rest.length);
        return rest;
    }

    // ------------------------------------------------------------- page assembly

    private void decodeUnit(byte[] data, int body, boolean subtitleUnit) {
        if (body + 44 > data.length || (data[body + 1] & 0xff) != TELETEXT_FRAMING) {
            return;
        }
        int high = HAM8_INVERSE[REVERSED[data[body + 2] & 0xff]];
        int low = HAM8_INVERSE[REVERSED[data[body + 3] & 0xff]];
        if (high < 0 || low < 0) {
            return;
        }
        int magazine = (high & 0x07);
        if (magazine == 0) {
            magazine = 8;
        }
        int row = (((high >> 3) & 1) << 4) | (low & 0x0f);

        Page page = wanted;
        if (page == null || magazine != page.magazine) {
            return;
        }
        if (row == 0) {
            header(data, body, page, subtitleUnit);
            return;
        }
        synchronized (rows) {
            if (assembling != page.full()) {
                return;
            }
            rows[row] = text(data, body + 4, 40);
            if (row >= LAST_ROW) {
                publish(page);
            }
        }
    }

    private void header(byte[] data, int body, Page page, boolean subtitleUnit) {
        int units = HAM8_INVERSE[REVERSED[data[body + 4] & 0xff]];
        int tens = HAM8_INVERSE[REVERSED[data[body + 5] & 0xff]];
        if (units < 0 || tens < 0) {
            return;
        }
        int number = (tens & 0x0f) * 10 + (units & 0x0f);
        synchronized (rows) {
            // A header for ANY page in this magazine ends whatever was being
            // assembled. Rows belong to the header that introduced them, and this
            // stream interleaves the pages of a magazine - so without this the rows
            // of every page that followed the subtitle page were collected into it,
            // and a subtitle page came out as the news index with the odd stray
            // comma row.
            boolean following = assembling == page.full();
            if (following) {
                publish(page);                      // the previous subtitle
            }
            java.util.Arrays.fill(rows, null);
            if (number == page.number) {
                assembling = page.full();
                if (!following) {
                    // Once per run of this page, which is the deterministic answer to
                    // "is the subtitle page being transmitted at all".
                    Log.i(TAG, "teletext: page " + page.full() + " header");
                }
            } else {
                assembling = -1;                    // not our page: ignore its rows
            }
        }
    }

    /** Called with the lock held. */
    private void publish(Page page) {
        List<String> lines = new ArrayList<>();
        for (int row = 1; row <= 25; row++) {
            String line = rows[row];
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        rows[0] = null;
        listener.onSubtitles(lines, page.label());
    }

    // ------------------------------------------------------------------- tables

    /** PAT then PMT, until the teletext descriptor has been read. */
    private void parseProgramTables(byte[] payload, boolean start, boolean isPat) {
        if (!start || pmtParsed || payload.length < 12) {
            return;
        }
        int at = 1 + (payload[0] & 0xff);
        if (at + 3 > payload.length) {
            return;
        }
        int tableId = payload[at] & 0xff;
        int sectionLength = ((payload[at + 1] & 0x0f) << 8) | (payload[at + 2] & 0xff);
        int end = Math.min(at + 3 + sectionLength, payload.length);

        if (isPat) {                                // program association table
            if (tableId != 0x00) {
                return;
            }
            int entry = at + 8;
            while (entry + 4 <= end) {
                int program = ((payload[entry] & 0xff) << 8) | (payload[entry + 1] & 0xff);
                int pid = ((payload[entry + 2] & 0x1f) << 8) | (payload[entry + 3] & 0xff);
                if (program != 0) {
                    pmtPid = pid;
                    Log.i(TAG, "teletext: pmt pid " + pid);
                    return;
                }
                entry += 4;
            }
            return;
        }

        if (tableId != 0x02) {                      // program map table
            return;
        }
        int infoLength = ((payload[at + 10] & 0x0f) << 8) | (payload[at + 11] & 0xff);
        int stream = at + 12 + infoLength;
        while (stream + 5 <= end) {
            int pid = ((payload[stream + 1] & 0x1f) << 8) | (payload[stream + 2] & 0xff);
            int esLength = ((payload[stream + 3] & 0x0f) << 8) | (payload[stream + 4] & 0xff);
            int descriptor = stream + 5;
            int descriptorsEnd = Math.min(descriptor + esLength, end);
            while (descriptor + 2 <= descriptorsEnd) {
                int tag = payload[descriptor] & 0xff;
                int length = payload[descriptor + 1] & 0xff;
                if (tag == DESCRIPTOR_TELETEXT) {
                    readTeletextDescriptor(payload, descriptor + 2, length, pid);
                }
                descriptor += 2 + length;
            }
            stream = descriptorsEnd;
        }
    }

    private void readTeletextDescriptor(byte[] data, int start, int length, int pid) {
        int at = start;
        while (at + 5 <= start + length) {
            String language = "" + (char) (data[at] & 0xff)
                    + (char) (data[at + 1] & 0xff) + (char) (data[at + 2] & 0xff);
            int type = (data[at + 3] >> 3) & 0x1f;
            int magazine = data[at + 3] & 0x07;
            int pageByte = data[at + 4] & 0xff;
            int number = ((pageByte >> 4) & 0x0f) * 10 + (pageByte & 0x0f);
            if (type == 0x02 || type == 0x05) {      // subtitle, or subtitle for HI
                pages.add(new Page(magazine == 0 ? 8 : magazine, number, language,
                        type == 0x05));
            }
            at += 5;
        }
        if (!pages.isEmpty()) {
            teletextPid = pid;
            pmtParsed = true;
            Log.i(TAG, "teletext: pid " + pid + ", " + pages.size() + " subtitle pages");
        }
    }

    // ----------------------------------------------------------------- charmaps

    /**
     * One teletext row: seven bits per byte plus odd parity, then the national
     * option subset. This is the Swedish/Finnish/Hungarian subset, which is the one
     * these channels use; control codes become spaces.
     *
     * The reversal matters as much here as it does for the addresses: the text bytes
     * are bit-reversed on the wire like every other payload byte, and reading them
     * unreversed yields letters that are almost-but-not-quite right - which is how
     * this shipped once, as "OPPwG &'/" where the stream said "och sortera".
     */
    private static String text(byte[] data, int start, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length && start + i < data.length; i++) {
            int value = REVERSED[data[start + i] & 0xff] & 0x7f;
            if (value < 0x20) {
                out.append(' ');                    // colour and control codes
                continue;
            }
            out.append(SWEDISH[value - 0x20]);
        }
        return out.toString();
    }

    private static final char[] SWEDISH = new char[0x60];

    static {
        for (int i = 0; i < SWEDISH.length; i++) {
            SWEDISH[i] = (char) (i + 0x20);
        }
        SWEDISH[0x23 - 0x20] = '#';
        SWEDISH[0x24 - 0x20] = '\u00a4';            // currency sign
        SWEDISH[0x40 - 0x20] = '@';
        SWEDISH[0x5B - 0x20] = '\u00c4';            // Ä
        SWEDISH[0x5C - 0x20] = '\u00d6';            // Ö
        SWEDISH[0x5D - 0x20] = '\u00c5';            // Å
        SWEDISH[0x5E - 0x20] = '\u00dc';            // Ü
        SWEDISH[0x5F - 0x20] = '_';
        SWEDISH[0x60 - 0x20] = '\u00e9';            // é
        SWEDISH[0x7B - 0x20] = '\u00e4';            // ä
        SWEDISH[0x7C - 0x20] = '\u00f6';            // ö
        SWEDISH[0x7D - 0x20] = '\u00e5';            // å
        SWEDISH[0x7E - 0x20] = '\u00fc';            // ü
    }
}
