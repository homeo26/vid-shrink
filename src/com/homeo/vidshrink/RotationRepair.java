/*
 * RotationRepair — losslessly fixes a video whose rotation flag was written
 * wrong (the double-rotation bug in an earlier build). It re-muxes the file
 * with orientation 0: MediaExtractor reads the already-encoded samples and
 * MediaMuxer writes them straight back with no rotation hint. No decoding, no
 * re-encoding — the pixels are copied verbatim, only the container's rotation
 * matrix changes. File date/timestamp are preserved.
 */
package com.homeo.vidshrink;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;

import java.io.File;
import java.nio.ByteBuffer;

class RotationRepair {

    /** Returns a status string; "OK" prefix means the file was repaired. */
    static String repair(Context ctx, File f) {
        return repair(ctx, f, null);
    }

    /** Same, reporting 0..100 progress while the samples are copied. */
    static String repair(Context ctx, File f, VideoTranscoder.Progress cb) {
        String path = f.getAbsolutePath();

        // current rotation — skip if already 0 (don't touch correct files)
        int rot = 0;
        long durMs = 0, dateTaken;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            rot = parseInt(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            durMs = parseInt(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
        } catch (Throwable t) {
            return "FAIL (unreadable)";
        } finally {
            try { mmr.release(); } catch (Throwable ignore) {}
        }
        if (rot == 0) return "SKIP (already 0)";

        long beforeMtime = f.lastModified();
        dateTaken = beforeMtime;   // preserve file date; DATE_TAKEN re-patched below

        File tmp = new File(path + ".vidshrink.tmp");
        if (tmp.exists()) tmp.delete();

        MediaExtractor ex = null;
        MediaMuxer mux = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);
            int n = ex.getTrackCount();
            mux = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] map = new int[n];
            int maxSample = 1 << 20;
            for (int i = 0; i < n; i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                map[i] = mux.addTrack(fmt);
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    maxSample = Math.max(maxSample,
                            fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                ex.selectTrack(i);
            }
            mux.setOrientationHint(0);      // the actual fix
            mux.start();

            ByteBuffer buf = ByteBuffer.allocate(maxSample);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long durUs = durMs * 1000L;
            int lastPct = -1;
            while (true) {
                int tIdx = ex.getSampleTrackIndex();
                if (tIdx < 0) break;
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = ex.getSampleTime();
                int flags = ex.getSampleFlags();
                info.flags = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(map[tIdx], buf, info);
                if (cb != null && durUs > 0 && info.presentationTimeUs > 0) {
                    int p = (int) (info.presentationTimeUs * 100 / durUs);
                    if (p > 100) p = 100;
                    if (p != lastPct) { lastPct = p; cb.onProgress(p); }
                }
                ex.advance();
            }
        } catch (Throwable t) {
            if (tmp.exists()) tmp.delete();
            return "FAIL (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (mux != null) { try { mux.stop(); } catch (Throwable ignore) {}
                               try { mux.release(); } catch (Throwable ignore) {} }
            if (ex != null) ex.release();
        }

        // verify: exists, non-trivial, duration within 1.5s, rotation now 0
        if (!tmp.exists() || tmp.length() < 100 * 1024) { tmp.delete(); return "FAIL (bad output)"; }
        MediaMetadataRetriever v = new MediaMetadataRetriever();
        try {
            v.setDataSource(tmp.getAbsolutePath());
            long d2 = parseInt(v.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION), -1);
            int r2 = parseInt(v.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), -1);
            if (d2 < 0 || (durMs > 0 && Math.abs(d2 - durMs) > 1500)) {
                tmp.delete(); return "FAIL (duration mismatch)";
            }
            if (r2 != 0) { tmp.delete(); return "FAIL (rotation still " + r2 + ")"; }
        } catch (Throwable t) {
            tmp.delete(); return "FAIL (verify unreadable)";
        } finally {
            try { v.release(); } catch (Throwable ignore) {}
        }

        // preserve the original embedded date so Gallery keeps its place
        Mp4DatePatcher.patch(tmp.getAbsolutePath(), dateTaken);

        // atomic replace, preserve mtime
        tmp.setLastModified(beforeMtime);
        File bak = new File(path + ".vidshrink.bak");
        if (!f.renameTo(bak)) { tmp.delete(); return "FAIL (cannot move original)"; }
        if (!tmp.renameTo(f)) { bak.renameTo(f); tmp.delete(); return "FAIL (cannot install)"; }
        f.setLastModified(beforeMtime);
        bak.delete();

        try { MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null); }
        catch (Throwable ignore) {}
        return "OK (rotation " + rot + " -> 0)";
    }

    private static int parseInt(String s, int dflt) {
        try { return (s == null || s.isEmpty()) ? dflt : Integer.parseInt(s); }
        catch (Throwable t) { return dflt; }
    }
}
