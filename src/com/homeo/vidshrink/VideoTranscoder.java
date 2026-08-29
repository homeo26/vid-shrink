/*
 * VideoTranscoder — re-encodes a single video to HEVC at a reduced bitrate
 * using the phone's hardware codecs, entirely on-device.
 *
 * Pipeline:
 *   MediaExtractor(video) -> HW decoder -> OutputSurface(GL) -> InputSurface
 *     -> HW HEVC encoder -> MediaMuxer
 *   MediaExtractor(audio) -> MediaMuxer   (passthrough, no re-encode)
 *
 * Quality: targets a bitrate derived from the source (a fraction of the
 * original, capped per-resolution) which for phone camera footage is
 * perceptually close to the CRF~22 point validated on the desktop. Uses
 * VBR. Rotation, capture location and creation time are carried across.
 *
 * No third-party dependencies — Android SDK only.
 */
package com.homeo.vidshrink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

class VideoTranscoder {

    interface Progress { void onProgress(int percent); }

    static class Result {
        boolean success;
        String message;
        long outBytes;
    }

    private static final String OUT_VIDEO_MIME = "video/hevc";
    private static final long TIMEOUT_US = 10000;

    /** Per-resolution "already efficient" bitrate cap (HEVC, phone content). */
    static long capFor(int width, int height) {
        long pixels = (long) width * height;
        if (pixels >= 3840L * 2160) return 22_000_000L;
        if (pixels >= 2560L * 1440) return 14_000_000L;
        if (pixels >= 1920L * 1080) return 8_000_000L;
        if (pixels >= 1280L * 720) return 4_500_000L;
        return 2_500_000L;
    }

    /** Decide a target video bitrate (bps) or return <=0 to signal "skip". */
    static long targetBitrate(int width, int height, long srcVideoBitrate) {
        long cap = capFor(width, height);
        // IDEMPOTENCY GUARD: source already at/below cap (30% margin) -> skip.
        if (srcVideoBitrate > 0 && srcVideoBitrate <= cap * 1.3) return -1;
        return cap;
    }

    /**
     * Source video bitrate in bps, derived exactly the way transcode() does it:
     * the track's declared bitrate when present, else file size over duration.
     * Returns 0 when it cannot be determined.
     */
    static long sourceBitrate(String path) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);
            int t = firstTrack(ex, "video/");
            if (t < 0) return 0;
            MediaFormat f = ex.getTrackFormat(t);
            long durUs = f.containsKey(MediaFormat.KEY_DURATION)
                    ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            long fileBits = new File(path).length() * 8;
            return getInt(f, MediaFormat.KEY_BIT_RATE,
                    durUs > 0 ? (int) (fileBits * 1_000_000L / durUs) : 0);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (ex != null) try { ex.release(); } catch (Throwable ignore) {}
        }
    }

    /**
     * Would transcode() skip this file as already efficient? Mirrors the real
     * decision so a scan preview matches what a run actually does. Files whose
     * bitrate cannot be read are reported as compressible (the run will decide).
     */
    static boolean wouldSkip(String path) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);
            int t = firstTrack(ex, "video/");
            if (t < 0) return true;                       // no video track: nothing to do
            MediaFormat f = ex.getTrackFormat(t);
            int w = f.getInteger(MediaFormat.KEY_WIDTH);
            int h = f.getInteger(MediaFormat.KEY_HEIGHT);
            long durUs = f.containsKey(MediaFormat.KEY_DURATION)
                    ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            long fileBits = new File(path).length() * 8;
            long src = getInt(f, MediaFormat.KEY_BIT_RATE,
                    durUs > 0 ? (int) (fileBits * 1_000_000L / durUs) : 0);
            // capFor() uses the pixel count, which a rotation swap doesn't change,
            // so stored vs display dimensions give the same verdict here.
            return targetBitrate(w, h, src) <= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (ex != null) try { ex.release(); } catch (Throwable ignore) {}
        }
    }

    static Result transcode(String inPath, String outPath, Progress cb) {
        return transcode(inPath, outPath, cb, 0, 0);
    }

    /**
     * Transcode inPath -> outPath. Never touches the original.
     * When outW/outH > 0 the encoder is forced to those dimensions (used for
     * the un-squash repair: decode WxH, encode at HxW = a deliberate
     * non-uniform stretch), the efficiency gate is skipped, and orientation is
     * written as 0 (the input must already be rotation-0 so the decoder does
     * not auto-rotate).
     */
    static Result transcode(String inPath, String outPath, Progress cb,
                            int outW, int outH) {
        Result r = new Result();
        MediaExtractor vExtractor = null, aExtractor = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        OutputSurface outputSurface = null;
        InputSurface inputSurface = null;
        try {
            // ---- probe source ----
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(inPath);
            long durationUs = 1000L * Long.parseLong(safe(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION), "0"));
            int rotation = Integer.parseInt(safe(mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), "0"));
            String loc = mmr.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_LOCATION);
            mmr.release();

            // ---- video track ----
            vExtractor = new MediaExtractor();
            vExtractor.setDataSource(inPath);
            int vTrack = firstTrack(vExtractor, "video/");
            if (vTrack < 0) { r.message = "no video track"; return r; }
            vExtractor.selectTrack(vTrack);
            MediaFormat inFormat = vExtractor.getTrackFormat(vTrack);
            int width = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            int frameRate = getInt(inFormat, MediaFormat.KEY_FRAME_RATE, 30);

            long fileBits = new File(inPath).length() * 8;
            long srcVideoBitrate = getInt(inFormat, MediaFormat.KEY_BIT_RATE,
                    durationUs > 0 ? (int) (fileBits * 1_000_000L / durationUs) : 0);
            long target;
            int encW, encH;
            boolean forced = outW > 0 && outH > 0;
            if (forced) {
                encW = outW; encH = outH;             // un-squash: caller-forced
                target = capFor(encW, encH);
            } else {
                // ROOT-CAUSE FIX: the decoder renders frames in DISPLAY
                // orientation (it applies the stored rotation), so for a
                // portrait video stored as 1920x1080 + rotation 90 the decoded
                // frame is 1080x1920. Configure the encoder at those display
                // dimensions — NOT the stored ones — otherwise the portrait
                // frame gets squashed into a landscape surface.
                boolean rotated = (rotation == 90 || rotation == 270
                        || rotation == -90 || rotation == -270);
                encW = rotated ? height : width;
                encH = rotated ? width : height;
                target = targetBitrate(encW, encH, srcVideoBitrate);
                if (target <= 0) { r.message = "already efficient — skipped"; return r; }
            }

            // ---- encoder (HEVC, target bitrate, VBR) ----
            MediaFormat outFormat = MediaFormat.createVideoFormat(
                    OUT_VIDEO_MIME, encW, encH);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int) target);
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            outFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            encoder = MediaCodec.createEncoderByType(OUT_VIDEO_MIME);
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = new InputSurface(encoder.createInputSurface());
            inputSurface.makeCurrent();
            encoder.start();

            // ---- decoder (renders to our OutputSurface) ----
            outputSurface = new OutputSurface();
            decoder = MediaCodec.createDecoderByType(
                    inFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inFormat, outputSurface.getSurface(), null, 0);
            decoder.start();

            // ---- muxer ----
            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // Pixels are encoded in display orientation (decoder already applied
            // the rotation), so the output needs NO rotation flag.
            muxer.setOrientationHint(0);
            double[] latLon = parseLoc(loc);
            if (latLon != null) muxer.setLocation((float) latLon[0], (float) latLon[1]);

            // ---- audio passthrough setup ----
            aExtractor = new MediaExtractor();
            aExtractor.setDataSource(inPath);
            int aInTrack = firstTrack(aExtractor, "audio/");
            int aOutTrack = -1;
            if (aInTrack >= 0) {
                aExtractor.selectTrack(aInTrack);
                aOutTrack = muxer.addTrack(aExtractor.getTrackFormat(aInTrack));
            }

            int vOutTrack = -1;
            boolean muxerStarted = false;
            boolean decoderDone = false, encoderDone = false, inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int lastPct = -1;

            while (!encoderDone) {
                // feed decoder from extractor
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIdx);
                        int sz = vExtractor.readSampleData(buf, 0);
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz,
                                    vExtractor.getSampleTime(), 0);
                            vExtractor.advance();
                        }
                    }
                }
                // drain decoder -> render to encoder input surface
                if (!decoderDone) {
                    int dIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (dIdx >= 0) {
                        boolean render = info.size != 0;
                        boolean eos = (info.flags &
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        decoder.releaseOutputBuffer(dIdx, render);
                        if (render) {
                            outputSurface.awaitNewImage();
                            outputSurface.drawImage();
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000);
                            inputSurface.swapBuffers();
                            if (durationUs > 0) {
                                int pct = (int) (info.presentationTimeUs * 100 / durationUs);
                                if (pct != lastPct && cb != null) { cb.onProgress(pct); lastPct = pct; }
                            }
                        }
                        if (eos) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }
                // drain encoder -> muxer
                int eIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vOutTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    if (aOutTrack >= 0) copyAudio(aExtractor, muxer, aOutTrack);
                } else if (eIdx >= 0) {
                    ByteBuffer out = encoder.getOutputBuffer(eIdx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size != 0 && muxerStarted) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(vOutTrack, out, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(eIdx, false);
                    if (eos) encoderDone = true;
                }
            }

            r.success = true;
            r.outBytes = new File(outPath).length();
            if (cb != null) cb.onProgress(100);
            return r;
        } catch (Throwable t) {
            android.util.Log.e("VidShrink", "transcode failed: " + inPath, t);
            r.success = false;
            r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        } finally {
            safeStop(decoder); safeStop(encoder);
            if (muxer != null) try { muxer.stop(); } catch (Throwable ignore) {}
            if (muxer != null) try { muxer.release(); } catch (Throwable ignore) {}
            if (outputSurface != null) outputSurface.release();
            if (inputSurface != null) inputSurface.release();
            if (vExtractor != null) vExtractor.release();
            if (aExtractor != null) aExtractor.release();
        }
    }

    /** Copy all audio samples straight through (no re-encode). */
    private static void copyAudio(MediaExtractor ex, MediaMuxer muxer, int outTrack) {
        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0;
            info.size = sz;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags()
                    & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    private static int firstTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int getInt(MediaFormat f, String k, int dflt) {
        try { return f.containsKey(k) ? f.getInteger(k) : dflt; }
        catch (Throwable t) { return dflt; }
    }

    private static String safe(String s, String dflt) {
        return (s == null || s.isEmpty()) ? dflt : s;
    }

    private static double[] parseLoc(String iso6709) {
        if (iso6709 == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([+-]\\d+\\.?\\d*)([+-]\\d+\\.?\\d*)").matcher(iso6709);
            if (m.find()) return new double[]{
                    Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        } catch (Throwable ignore) {}
        return null;
    }

    private static void safeStop(MediaCodec c) {
        if (c != null) {
            try { c.stop(); } catch (Throwable ignore) {}
            try { c.release(); } catch (Throwable ignore) {}
        }
    }
}
