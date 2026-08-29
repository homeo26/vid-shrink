/*
 * VideoScanner — walks the storage roots for video files above a size
 * threshold, and performs the verified, in-place replacement after a
 * successful transcode.
 *
 * In-place contract:
 *   - transcode to a sibling .vidshrink.tmp (never touch the original until done)
 *   - verify the output: exists, non-trivial, shorter, same duration (±1s),
 *     openable by MediaMetadataRetriever
 *   - preserve the original's lastModified timestamp
 *   - atomically replace the original, keeping the exact same path/name
 *   - notify MediaStore so Gallery updates
 */
package com.homeo.vidshrink;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class VideoScanner {

    static final String[] DEFAULT_ROOTS = {
        "/storage/emulated/0/DCIM/Camera",
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Movies",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Pictures",
    };

    static class Item {
        File file;
        long size;
        String folder;
        long mtime;
        Item(File f) { this.file = f; this.size = f.length();
            this.folder = f.getParent(); this.mtime = f.lastModified(); }
    }

    interface ScanListener { void onFolder(String folder, int foundSoFar); }

    private static final String[] VIDEO_EXT = {".mp4", ".mov", ".m4v", ".3gp", ".mkv"};

    /**
     * Find videos under the given roots matching size and date filters.
     * fromMillis / toMillis of 0 mean "no bound". Reports each folder entered.
     */
    static List<Item> scan(String[] roots, long thresholdBytes,
                           long fromMillis, long toMillis, ScanListener l) {
        List<Item> out = new ArrayList<>();
        long to = (toMillis <= 0) ? Long.MAX_VALUE : toMillis;
        for (String root : roots)
            walk(new File(root), thresholdBytes, fromMillis, to, out, 0, l);
        Collections.sort(out, (a, b) -> Long.compare(b.size, a.size));
        return out;
    }

    private static void walk(File dir, long threshold, long from, long to,
                             List<Item> out, int depth, ScanListener l) {
        if (dir == null || !dir.isDirectory() || depth > 8) return;
        if (l != null) l.onFolder(dir.getAbsolutePath(), out.size());
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                walk(f, threshold, from, to, out, depth + 1, l);
            } else if (f.length() >= threshold && isVideo(f.getName())
                    && !f.getName().endsWith(".vidshrink.tmp")
                    && !f.getName().endsWith(".vidshrink.bak")) {
                long m = f.lastModified();
                if (m >= from && m <= to) out.add(new Item(f));
            }
        }
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase();
        for (String e : VIDEO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** Duration in ms via retriever, or -1 if unreadable. */
    private static long durationMs(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Throwable t) {
            return -1;
        } finally {
            try { mmr.release(); } catch (Throwable ignore) {}
        }
    }

    /**
     * Compress one file in place. Returns a status string; on success the
     * original is replaced and the returned string starts with "OK".
     */
    static String compressInPlace(Context ctx, File original,
                                  VideoTranscoder.Progress cb) {
        String path = original.getAbsolutePath();
        long beforeBytes = original.length();
        long beforeDur = durationMs(path);
        long beforeMtime = original.lastModified();
        // Capture the original capture-date now, before we replace the file.
        // MediaMuxer can't write creation_time, so after replacing we push this
        // back into MediaStore's DATE_TAKEN — that's what Gallery sorts by.
        long originalDateTaken = queryDateTaken(ctx, path, beforeMtime);

        File tmp = new File(path + ".vidshrink.tmp");
        if (tmp.exists()) tmp.delete();

        VideoTranscoder.Result res = VideoTranscoder.transcode(path,
                tmp.getAbsolutePath(), cb);

        // A "skip" comes back as success=false with a skip message — check it
        // BEFORE treating a non-success as a failure, so already-efficient /
        // already-compressed files are reported as SKIP, not FAIL.
        if (res.message != null && res.message.contains("skipped")) {
            tmp.delete();
            return "SKIP (already efficient)";
        }
        if (!res.success) {
            tmp.delete();
            return "FAIL (" + res.message + ")";
        }
        // ---- patch embedded creation time so Gallery keeps the original date ----
        // (MediaMuxer stamps "now"; MediaStore re-derives DATE_TAKEN from this.)
        Mp4DatePatcher.patch(tmp.getAbsolutePath(), originalDateTaken);

        // ---- verify before replacing ----
        long outBytes = tmp.length();
        if (outBytes < 100 * 1024) { tmp.delete(); return "FAIL (output too small)"; }
        if (outBytes >= beforeBytes) { tmp.delete(); return "SKIP (not smaller)"; }
        long outDur = durationMs(tmp.getAbsolutePath());
        if (outDur < 0) { tmp.delete(); return "FAIL (output unreadable)"; }
        if (beforeDur > 0 && Math.abs(outDur - beforeDur) > 1500) {
            tmp.delete();
            return "FAIL (duration mismatch " + beforeDur + "->" + outDur + ")";
        }

        // ---- preserve timestamp, atomic replace ----
        tmp.setLastModified(beforeMtime);
        File bak = new File(path + ".vidshrink.bak");
        if (!original.renameTo(bak)) { tmp.delete(); return "FAIL (cannot move original)"; }
        if (!tmp.renameTo(original)) {
            bak.renameTo(original);   // roll back
            tmp.delete();
            return "FAIL (cannot install output)";
        }
        original.setLastModified(beforeMtime);
        bak.delete();

        // ---- refresh MediaStore, then restore the original capture date ----
        try {
            MediaScannerConnection.scanFile(ctx, new String[]{path}, null,
                (scannedPath, uri) -> restoreDateTaken(ctx, uri, scannedPath,
                        originalDateTaken, beforeMtime));
        } catch (Throwable ignore) {}

        long savedPct = 100 - (outBytes * 100 / beforeBytes);
        return "OK " + (beforeBytes / 1_000_000) + "MB->" + (outBytes / 1_000_000)
                + "MB (-" + savedPct + "%)";
    }

    /** Read DATE_TAKEN (ms) for a path from MediaStore; fall back to file mtime. */
    private static long queryDateTaken(Context ctx, String path, long mtimeMs) {
        try {
            Cursor c = ctx.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Video.Media.DATE_TAKEN},
                    MediaStore.Video.Media.DATA + "=?", new String[]{path}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        long dt = c.getLong(0);
                        if (dt > 0) return dt;
                    }
                } finally { c.close(); }
            }
        } catch (Throwable ignore) {}
        return mtimeMs;   // file modification time is the best remaining proxy
    }

    /** Push the captured capture-date back into MediaStore after the rescan. */
    private static void restoreDateTaken(Context ctx, Uri uri, String path,
                                         long dateTaken, long mtimeMs) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DATE_TAKEN, dateTaken);
            v.put(MediaStore.Video.Media.DATE_MODIFIED, mtimeMs / 1000);
            if (uri != null) {
                cr.update(uri, v, null, null);
            } else {
                cr.update(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v,
                        MediaStore.Video.Media.DATA + "=?", new String[]{path});
            }
        } catch (Throwable ignore) {}
    }
}
