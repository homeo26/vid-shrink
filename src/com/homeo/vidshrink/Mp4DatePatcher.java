/*
 * Mp4DatePatcher — rewrites the embedded creation/modification time in an
 * MP4's header boxes (mvhd, tkhd, mdhd) to a chosen instant.
 *
 * Why: MediaMuxer always stamps the output with "now", and Android 16 will not
 * let DATE_TAKEN be written through MediaStore — it re-derives it from the
 * file's mvhd creation_time on scan. So to keep a compressed video on its
 * original date in the Gallery timeline, we patch that field in the file.
 *
 * MP4 time fields are seconds since 1904-01-01 UTC (Unix epoch + 2082844800),
 * stored as uint32 (box version 0) or uint64 (box version 1). We locate the
 * boxes inside the (small, structured) moov box and overwrite creation_time
 * and modification_time.
 */
package com.homeo.vidshrink;

import java.io.RandomAccessFile;

class Mp4DatePatcher {

    private static final long EPOCH_1904 = 2082844800L; // 1904->1970 seconds

    /** Patch mvhd/tkhd/mdhd times to unixMillis. Returns true if anything changed. */
    static boolean patch(String path, long unixMillis) {
        long mp4Time = unixMillis / 1000 + EPOCH_1904;
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "rw");
            long fileLen = f.length();
            // find top-level 'moov'
            long[] moov = findBox(f, 0, fileLen, "moov");
            if (moov == null) return false;
            long moovContent = moov[0];
            long moovEnd = moov[1];
            byte[] buf = new byte[(int) (moovEnd - moovContent)];
            f.seek(moovContent);
            f.readFully(buf);
            boolean changed = false;
            changed |= patchInBuffer(buf, "mvhd", mp4Time);
            changed |= patchInBuffer(buf, "tkhd", mp4Time);
            changed |= patchInBuffer(buf, "mdhd", mp4Time);
            if (changed) {
                f.seek(moovContent);
                f.write(buf);
            }
            return changed;
        } catch (Throwable t) {
            return false;
        } finally {
            if (f != null) try { f.close(); } catch (Throwable ignore) {}
        }
    }

    /** Locate a top-level box by type between [start,end); returns {contentStart, contentEnd}. */
    private static long[] findBox(RandomAccessFile f, long start, long end,
                                  String type) throws Exception {
        long pos = start;
        byte[] hdr = new byte[8];
        while (pos + 8 <= end) {
            f.seek(pos);
            f.readFully(hdr);
            long size = u32(hdr, 0);
            String t = new String(hdr, 4, 4, "US-ASCII");
            long headerLen = 8;
            if (size == 1) {            // 64-bit largesize
                byte[] big = new byte[8];
                f.readFully(big);
                size = u64(big, 0);
                headerLen = 16;
            } else if (size == 0) {
                size = end - pos;       // extends to end
            }
            if (t.equals(type)) return new long[]{pos + headerLen, pos + size};
            if (size <= 0) break;
            pos += size;
        }
        return null;
    }

    /** Find a full-box by 4-char type inside buf and overwrite its two time fields. */
    private static boolean patchInBuffer(byte[] buf, String type, long mp4Time) {
        byte[] tb;
        try { tb = type.getBytes("US-ASCII"); } catch (Exception e) { return false; }
        boolean any = false;
        for (int i = 0; i + 8 < buf.length - 20; i++) {
            if (buf[i] == tb[0] && buf[i + 1] == tb[1]
                    && buf[i + 2] == tb[2] && buf[i + 3] == tb[3]) {
                int version = buf[i + 4] & 0xFF;   // byte after the 4-char type
                int off = i + 8;                   // skip version(1)+flags(3)
                if (version == 1) {
                    if (off + 16 > buf.length) continue;
                    putU64(buf, off, mp4Time);
                    putU64(buf, off + 8, mp4Time);
                } else {
                    if (off + 8 > buf.length) continue;
                    putU32(buf, off, mp4Time);
                    putU32(buf, off + 4, mp4Time);
                }
                any = true;
            }
        }
        return any;
    }

    private static long u32(byte[] b, int o) {
        return ((long)(b[o] & 0xFF) << 24) | ((b[o+1] & 0xFF) << 16)
                | ((b[o+2] & 0xFF) << 8) | (b[o+3] & 0xFF);
    }
    private static long u64(byte[] b, int o) {
        long v = 0;
        for (int k = 0; k < 8; k++) v = (v << 8) | (b[o+k] & 0xFF);
        return v;
    }
    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte)(v >>> 24); b[o+1] = (byte)(v >>> 16);
        b[o+2] = (byte)(v >>> 8); b[o+3] = (byte)v;
    }
    private static void putU64(byte[] b, int o, long v) {
        for (int k = 7; k >= 0; k--) { b[o+k] = (byte)v; v >>>= 8; }
    }
}
