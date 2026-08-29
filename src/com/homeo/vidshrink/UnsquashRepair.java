/*
 * UnsquashRepair — recovers a portrait video that an earlier build squashed
 * into a landscape frame. Entirely on-device, no transfer.
 *
 * Per file:
 *   1. If the file still carries a rotation flag, losslessly remux it to
 *      rotation 0 first (RotationRepair) so the decoder does NOT auto-rotate
 *      the already-upright (but squashed) pixels.
 *   2. Re-encode with the encoder forced to the SWAPPED dimensions
 *      (WxH -> HxW). The GL bridge draws the full decoded frame onto the
 *      swapped-size surface, i.e. a deliberate non-uniform stretch that
 *      exactly inverts the squash, restoring correct portrait proportions.
 *   3. Verify (portrait dims, duration within tolerance), preserve the
 *      original date/timestamp, replace in place.
 *
 * This is lossy (the squash already discarded vertical detail) but restores
 * correct geometry — the best possible without the deleted originals.
 */
package com.homeo.vidshrink;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;

import java.io.File;

class UnsquashRepair {

    static String repair(Context ctx, File f) {
        return repair(ctx, f, null);
    }

    /**
     * Same, reporting 0..100 for the whole operation: the optional lossless
     * pre-remux occupies 0..10%, the re-encode 10..100%.
     */
    static String repair(Context ctx, File f, VideoTranscoder.Progress cb) {
        String path = f.getAbsolutePath();

        int w = 0, h = 0, rot = 0;
        long durMs = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            w = pi(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = pi(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rot = pi(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            durMs = pi(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Throwable t) {
            return "FAIL (unreadable)";
        } finally {
            try { mmr.release(); } catch (Throwable ignore) {}
        }
        if (w <= 0 || h <= 0) return "FAIL (no dims)";

        long beforeMtime = f.lastModified();

        // step 1: strip rotation to 0 so the decoder yields the pixels as-stored
        if (rot != 0) {
            String rr = RotationRepair.repair(ctx, f,
                    cb == null ? null : p -> cb.onProgress(p / 10));   // 0..10%
            if (!rr.startsWith("OK") && !rr.startsWith("SKIP"))
                return "FAIL (pre-remux: " + rr + ")";
            // dims unchanged by remux; rotation now 0
        }

        // step 2: re-encode stretched to swapped dimensions (W x H -> H x W)
        File tmp = new File(path + ".vidshrink.tmp");
        if (tmp.exists()) tmp.delete();
        VideoTranscoder.Result res = VideoTranscoder.transcode(
                path, tmp.getAbsolutePath(),
                cb == null ? null : p -> cb.onProgress(10 + p * 9 / 10),  // 10..100%
                /*outW=*/h, /*outH=*/w);
        if (!res.success) { tmp.delete(); return "FAIL (encode: " + res.message + ")"; }

        // step 3: verify portrait dims + duration
        MediaMetadataRetriever v = new MediaMetadataRetriever();
        int ow, oh; long od;
        try {
            v.setDataSource(tmp.getAbsolutePath());
            ow = pi(v.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            oh = pi(v.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            od = pi(v.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Throwable t) {
            tmp.delete(); return "FAIL (verify unreadable)";
        } finally {
            try { v.release(); } catch (Throwable ignore) {}
        }
        if (ow != h || oh != w) { tmp.delete(); return "FAIL (bad out dims " + ow + "x" + oh + ")"; }
        if (durMs > 0 && Math.abs(od - durMs) > 1500) {
            tmp.delete(); return "FAIL (duration " + durMs + "->" + od + ")";
        }

        // preserve original capture date
        Mp4DatePatcher.patch(tmp.getAbsolutePath(), beforeMtime);

        // replace in place, keep timestamp
        tmp.setLastModified(beforeMtime);
        File bak = new File(path + ".vidshrink.bak");
        if (!f.renameTo(bak)) { tmp.delete(); return "FAIL (cannot move original)"; }
        if (!tmp.renameTo(f)) { bak.renameTo(f); tmp.delete(); return "FAIL (cannot install)"; }
        f.setLastModified(beforeMtime);
        bak.delete();

        try { MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null); }
        catch (Throwable ignore) {}
        return "OK (unsquashed " + w + "x" + h + " -> " + h + "x" + w + ")";
    }

    private static int pi(String s) {
        try { return (s == null || s.isEmpty()) ? 0 : Integer.parseInt(s); }
        catch (Throwable t) { return 0; }
    }
}
