package nodomain.freeyourgadget.gadgetbridge.service.devices.infinix;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Wire protocol for the Infinix XWatch N5 Pro (XW5E), derived from a Bluetooth HCI capture.
 * No Android dependencies, so it can be unit tested on a plain JVM.  Java 8 compatible.
 *
 * Frame:  0xEE | len_lo | cmd | flags | idx | payload[len]
 *         len = len_lo | (flags bit4) << 8 | (flags bit5) << 9
 *         flags: bit0 response, bit1 continuation, bit2 wants-ack, bits4-5 length high bits
 * Payloads are protobuf messages.
 */
public final class XWatchProtocol {
    private XWatchProtocol() {
    }

    // ---------------------------------------------------------------- GATT
    public static final UUID SERVICE = UUID.fromString("0000fd35-0000-1000-8000-00805f9b34fb");
    /** Write Without Response from the phone; the watch answers with notifications here. */
    public static final UUID CHAR_COMMAND = UUID.fromString("00009231-0000-1000-8000-00805f9b34fb");
    /** Write Without Response + Notify; carried the bind reply (cmd 0x91) in the capture. */
    public static final UUID CHAR_SECONDARY = UUID.fromString("00009232-0000-1000-8000-00805f9b34fb");
    /** Read only; value {@code 08 04 10 80 20 18 6E}. */
    public static final UUID CHAR_INFO = UUID.fromString("00009233-0000-1000-8000-00805f9b34fb");

    // ------------------------------------------------------------ commands
    public static final int CMD_VERSION = 0x32;
    public static final int CMD_BATTERY = 0x33;
    public static final int CMD_STEPS = 0x34;
    public static final int CMD_HEART_RATE = 0x36;
    public static final int CMD_SLEEP = 0x38;
    public static final int CMD_TIME_SYNC = 0x60;
    public static final int CMD_BIND = 0x6E;
    public static final int CMD_BIND_REPLY = 0x91;
    public static final int CMD_RESPONSE = 0x92;

    public static final int QUERY_TODAY = 0;
    public static final int QUERY_HISTORY = 1;

    public static final int MAX_PAYLOAD = 1023;

    // -------------------------------------------------------------- frames
    public static final class Frame {
        public final int cmd;
        public final int flags;
        public final int idx;
        public final byte[] payload;

        Frame(int cmd, int flags, int idx, byte[] payload) {
            this.cmd = cmd;
            this.flags = flags;
            this.idx = idx;
            this.payload = payload;
        }

        public boolean isResponse() {
            return (flags & 0x01) != 0;
        }

        public boolean isContinuation() {
            return (flags & 0x02) != 0;
        }
    }

    public static byte[] buildFrame(int cmd, byte[] payload) {
        return buildFrame(cmd, 0, 0, payload);
    }

    public static byte[] buildFrame(int cmd, int flags, int idx, byte[] payload) {
        final int n = payload.length;
        if (n > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too long for one frame: " + n);
        }
        final int f = (flags & ~0x30) | (((n >> 8) & 1) << 4) | (((n >> 9) & 1) << 5);
        final byte[] out = new byte[5 + n];
        out[0] = (byte) 0xEE;
        out[1] = (byte) n;
        out[2] = (byte) cmd;
        out[3] = (byte) f;
        out[4] = (byte) idx;
        System.arraycopy(payload, 0, out, 5, n);
        return out;
    }

    /** Collects notification/write values and returns complete frames. */
    public static final class Reassembler {
        private byte[] buf = new byte[0];
        private int skipped;

        public int getSkippedBytes() {
            return skipped;
        }

        public void reset() {
            buf = new byte[0];
        }

        public List<Frame> feed(final byte[] data) {
            final byte[] merged = new byte[buf.length + data.length];
            System.arraycopy(buf, 0, merged, 0, buf.length);
            System.arraycopy(data, 0, merged, buf.length, data.length);
            buf = merged;
            final List<Frame> out = new ArrayList<>();
            int pos = 0;
            while (pos < buf.length) {
                if ((buf[pos] & 0xFF) != 0xEE) {
                    pos++;
                    skipped++;
                    continue;
                }
                if (buf.length - pos < 5) {
                    break;
                }
                final int flags = buf[pos + 3] & 0xFF;
                final int n = (buf[pos + 1] & 0xFF) | (((flags >> 4) & 1) << 8) | (((flags >> 5) & 1) << 9);
                if (buf.length - pos < 5 + n) {
                    break;
                }
                out.add(new Frame(buf[pos + 2] & 0xFF, flags, buf[pos + 4] & 0xFF,
                        Arrays.copyOfRange(buf, pos + 5, pos + 5 + n)));
                pos += 5 + n;
            }
            buf = Arrays.copyOfRange(buf, pos, buf.length);
            return out;
        }
    }

    // ------------------------------------------------------------ protobuf
    /** One decoded protobuf field: either a varint ({@code data == null}) or a length-delimited blob. */
    public static final class Field {
        public final int number;
        public final long value;
        public final byte[] data;

        Field(int number, long value, byte[] data) {
            this.number = number;
            this.value = value;
            this.data = data;
        }

        public boolean isVarint() {
            return data == null;
        }
    }

    public static byte[] varint(long v) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeVarint(o, v);
        return o.toByteArray();
    }

    private static void writeVarint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    public static List<Field> parse(final byte[] b) {
        final List<Field> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            long key = 0;
            int shift = 0;
            int c;
            do {
                if (i >= b.length) throw new IllegalArgumentException("truncated key");
                c = b[i++] & 0xFF;
                key |= (long) (c & 0x7F) << shift;
                shift += 7;
            } while ((c & 0x80) != 0);
            final int number = (int) (key >>> 3);
            final int wire = (int) (key & 7);
            if (number == 0) throw new IllegalArgumentException("field number 0");
            if (wire == 0) {
                long v = 0;
                shift = 0;
                do {
                    if (i >= b.length) throw new IllegalArgumentException("truncated varint");
                    c = b[i++] & 0xFF;
                    v |= (long) (c & 0x7F) << shift;
                    shift += 7;
                } while ((c & 0x80) != 0);
                out.add(new Field(number, v, null));
            } else if (wire == 2) {
                long len = 0;
                shift = 0;
                do {
                    if (i >= b.length) throw new IllegalArgumentException("truncated length");
                    c = b[i++] & 0xFF;
                    len |= (long) (c & 0x7F) << shift;
                    shift += 7;
                } while ((c & 0x80) != 0);
                if (len < 0 || i + len > b.length) throw new IllegalArgumentException("truncated field");
                out.add(new Field(number, 0, Arrays.copyOfRange(b, i, i + (int) len)));
                i += (int) len;
            } else {
                throw new IllegalArgumentException("unsupported wire type " + wire);
            }
        }
        return out;
    }

    private static Field first(final List<Field> fs, final int number) {
        for (final Field f : fs) {
            if (f.number == number) return f;
        }
        return null;
    }

    private static long longOf(final List<Field> fs, final int number, final long dflt) {
        final Field f = first(fs, number);
        return f != null && f.isVarint() ? f.value : dflt;
    }

    private static List<byte[]> blobs(final List<Field> fs, final int number) {
        final List<byte[]> out = new ArrayList<>();
        for (final Field f : fs) {
            if (f.number == number && !f.isVarint()) out.add(f.data);
        }
        return out;
    }

    private static List<Integer> ints(final List<Field> fs, final int number) {
        final List<Integer> out = new ArrayList<>();
        for (final Field f : fs) {
            if (f.number != number) continue;
            if (f.isVarint()) {
                out.add((int) f.value);
            } else {
                for (final Field p : parse(packedAsFields(f.data))) {
                    out.add((int) p.value);
                }
            }
        }
        return out;
    }

    /** Turns packed varints into a synthetic field stream (field 1, wire type 0) so parse() can read them. */
    private static byte[] packedAsFields(final byte[] packed) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        int i = 0;
        while (i < packed.length) {
            o.write(0x08);
            int c;
            do {
                c = packed[i++] & 0xFF;
                o.write(c);
            } while ((c & 0x80) != 0 && i < packed.length);
        }
        return o.toByteArray();
    }

    // ------------------------------------------------------------- requests
    public static byte[] versionRequest() {
        return buildFrame(CMD_VERSION, new byte[0]);
    }

    public static byte[] batteryRequest() {
        return buildFrame(CMD_BATTERY, new byte[0]);
    }

    /** History query for cmd 0x34 / 0x36 / 0x38. Payload bytes match the capture: {@code 12 02 08 <q>}. */
    public static byte[] historyRequest(final int cmd, final int query) {
        return buildFrame(cmd, new byte[]{0x12, 0x02, 0x08, (byte) query});
    }

    /**
     * Time sync (cmd 0x60): field 1 = Unix seconds, field 2 = timezone value.
     * Hypothesis: the timezone value is hours * 100 (300 in the capture, taken in UTC+3).
     */
    public static byte[] timeSync(final long unixSeconds, final int tzValue) {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x08);
        writeVarint(o, unixSeconds);
        o.write(0x10);
        writeVarint(o, tzValue);
        return buildFrame(CMD_TIME_SYNC, o.toByteArray());
    }

    /** Bind (cmd 0x6E): field 1 = 32-character identity string. How the watch validates it is unknown. */
    public static byte[] bind(final String identity) {
        final byte[] id = identity.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x0A);
        writeVarint(o, id.length);
        o.write(id, 0, id.length);
        return buildFrame(CMD_BIND, o.toByteArray());
    }

    // -------------------------------------------------------------- replies
    public static String decodeVersion(final byte[] payload) {
        final Field f = first(parse(payload), 2);
        return f != null && !f.isVarint() ? new String(f.data, java.nio.charset.StandardCharsets.US_ASCII) : null;
    }

    /** Battery percentage, or -1 if absent. */
    public static int decodeBattery(final byte[] payload) {
        return (int) longOf(parse(payload), 1, -1);
    }

    public static final class IndexEntry {
        public final long date;
        public final int a;
        public final int b;

        IndexEntry(long date, int a, int b) {
