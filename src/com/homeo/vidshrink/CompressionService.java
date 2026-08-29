/*
 * CompressionService — runs the bulk compression in the foreground and reports
 * richly: scan progress (folder-by-folder + running count), per-file status
 * marks (COMPRESSING / OK / SKIP / FAIL), and live counters (found, done,
 * compressed, skipped, failed, freed). Everything is also appended to
 * /storage/emulated/0/Download/vidshrink-log.txt.
 */
package com.homeo.vidshrink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class CompressionService extends Service {

    static final String ACTION_UPDATE = "com.homeo.vidshrink.UPDATE";
    static final String ACTION_DONE = "com.homeo.vidshrink.DONE";
    // extras
    static final String EX_PHASE = "phase";       // SCAN | PROCESS | DONE
    static final String EX_FOLDER = "folder";      // current folder
    static final String EX_FILE = "file";          // current file name
    static final String EX_MARK = "mark";          // COMPRESSING/OK/SKIP/FAIL
    static final String EX_FILEPCT = "filePct";
    static final String EX_OVERALL = "overall";
    static final String EX_FOUND = "found";
    static final String EX_DONE = "done";
    static final String EX_COMPRESSED = "compressed";
    static final String EX_SKIPPED = "skipped";
    static final String EX_FAILED = "failed";
    static final String EX_FREED = "freed";
    static final String EX_LOGLINE = "logLine";    // completed per-file line (nullable)

    static volatile boolean RUNNING = false;
    static volatile boolean CANCEL = false;

    private static final String CH = "vidshrink";
    private static final File LOG =
            new File("/storage/emulated/0/Download/vidshrink-log.txt");

    // counters
    private int found, done, compressed, skipped, failed;
    private long freed;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String repairList = intent != null ? intent.getStringExtra("repairList") : null;
        final String unsquashList = intent != null ? intent.getStringExtra("unsquashList") : null;
        final String singleFile = intent != null ? intent.getStringExtra("singleFile") : null;
        final long threshold = intent != null
                ? intent.getLongExtra("thresholdBytes", 200L * 1024 * 1024)
                : 200L * 1024 * 1024;
        final String[] roots = intent != null && intent.getStringArrayExtra("roots") != null
                ? intent.getStringArrayExtra("roots") : VideoScanner.DEFAULT_ROOTS;
        final long fromMs = intent != null ? intent.getLongExtra("fromMillis", 0) : 0;
        final long toMs = intent != null ? intent.getLongExtra("toMillis", 0) : 0;
        startForeground(1, buildNotification(
                unsquashList != null ? "Un-squashing…"
                        : repairList != null ? "Repairing…"
                        : singleFile != null ? "Compressing…" : "Scanning…"));
        CANCEL = false;
        RUNNING = true;
        found = done = compressed = skipped = failed = 0;
        freed = 0;
        if (unsquashList != null)
            new Thread(() -> runList(unsquashList, true)).start();
        else if (repairList != null)
            new Thread(() -> runList(repairList, false)).start();
        else if (singleFile != null)
            new Thread(() -> runSingle(singleFile)).start();
        else
            new Thread(() -> run(roots, threshold, fromMs, toMs)).start();
        return START_NOT_STICKY;
    }

    /** Single-file mode: compress exactly one chosen video, no scan, no filters. */
    private void runSingle(String path) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "vidshrink:single");
        wl.acquire(6 * 60 * 60 * 1000L);
        try {
            File f = new File(path);
            if (!f.isFile()) {
                log("=== SINGLE " + now() + " — file not found: " + path + " ===");
                return;
            }
            log("=== SINGLE " + now() + " — " + f.getName()
                    + " (" + (f.length() / 1_000_000) + "MB) ===");
            List<VideoScanner.Item> one = new java.util.ArrayList<>();
            one.add(new VideoScanner.Item(f));
            found = 1;
            processItems(one, f.length());
            log("=== DONE — compressed " + compressed + ", skipped " + skipped
                    + ", failed " + failed + ", freed " + (freed / 1_000_000) + "MB ===");
        } catch (Throwable t) {
            log("ERROR: " + t);
        } finally {
            try { wl.release(); } catch (Throwable ignore) {}
            RUNNING = false;
            send(ACTION_DONE, "DONE", null, null, null, 100, 100,
                    "Finished — compressed " + compressed + ", skipped " + skipped
                    + ", failed " + failed + ", freed " + (freed / 1_000_000) + "MB");
            stopForeground(true);
            stopSelf();
        }
    }

    /** List mode: repair rotation (unsquash=false) or un-squash (unsquash=true). */
    private void runList(String listPath, boolean unsquash) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidshrink:list");
        wl.acquire(6 * 60 * 60 * 1000L);
        String kind = unsquash ? "UNSQUASH" : "REPAIR";
        try {
            java.util.List<String> paths = new java.util.ArrayList<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(listPath))) {
                String ln;
                while ((ln = r.readLine()) != null) { ln = ln.trim(); if (!ln.isEmpty()) paths.add(ln); }
            }
            found = paths.size();
            log("=== " + kind + " " + now() + " — " + found + " file(s) ===");
            for (int i = 0; i < paths.size(); i++) {
                if (CANCEL) { log("cancelled"); break; }
                File f = new File(paths.get(i));
                String name = f.getName();
                final int idx = i + 1;
                final int base = (idx - 1) * 100;      // smooth overall: file % counts too
                int overall = base / Math.max(1, found);
                updateNotification((unsquash ? "Un-squashing " : "Repairing ") + name
                        + " (" + idx + "/" + found + ")");
                send(ACTION_UPDATE, "PROCESS", f.getParent(), name,
                        unsquash ? "UNSQUASHING" : "REPAIRING", 0, overall, null);
                final String mk = unsquash ? "UNSQUASHING" : "REPAIRING";
                final String folder = f.getParent();
                VideoTranscoder.Progress cb = p ->
                        send(ACTION_UPDATE, "PROCESS", folder, name, mk, p,
                                (base + p) / Math.max(1, found), null);
                String res;
                if (!f.exists()) res = "FAIL (missing)";
                else res = unsquash ? UnsquashRepair.repair(this, f, cb)
                                    : RotationRepair.repair(this, f, cb);
                if (res.startsWith("OK")) compressed++;
                else if (res.startsWith("SKIP")) skipped++;
                else failed++;
                done++;
                String line = "[" + idx + "/" + found + "] " + name + ": " + res;
                log(line);
                send(ACTION_UPDATE, "PROCESS", f.getParent(), name,
                        res.startsWith("OK") ? "FIXED" : res.startsWith("SKIP") ? "SKIP" : "FAIL",
                        100, idx * 100 / Math.max(1, found), line);
            }
            log("=== " + kind + " DONE — ok " + compressed + ", skipped " + skipped
                    + ", failed " + failed + " ===");
        } catch (Throwable t) {
            log(kind + " ERROR: " + t);
        } finally {
            try { wl.release(); } catch (Throwable ignore) {}
            RUNNING = false;
            send(ACTION_DONE, "DONE", null, null, null, 100, 100,
                    kind + " finished — ok " + compressed + ", skipped " + skipped
                    + ", failed " + failed);
            stopForeground(true);
            stopSelf();
        }
    }

    private void run(String[] roots, long threshold, long fromMs, long toMs) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "vidshrink:job");
        wl.acquire(6 * 60 * 60 * 1000L);
        try {
            log("=== RUN " + now() + " — threshold " + (threshold / 1_000_000)
                    + "MB, roots " + roots.length + ", from " + fromMs + " to " + toMs + " ===");

            List<VideoScanner.Item> items = VideoScanner.scan(roots, threshold,
                    fromMs, toMs, (folder, foundSoFar) -> {
                        found = foundSoFar;
                        send(ACTION_UPDATE, "SCAN", folder, null, null, 0, 0, null);
                    });
            found = items.size();
            long totalBytes = 0;
            for (VideoScanner.Item it : items) totalBytes += it.size;
            log("scan complete: " + found + " candidate(s), "
                    + (totalBytes / 1_000_000) + "MB");
            send(ACTION_UPDATE, "PROCESS", "scan complete", null, null, 0, 0, null);

            processItems(items, totalBytes);
            log("=== DONE — compressed " + compressed + ", skipped " + skipped
                    + ", failed " + failed + ", freed " + (freed / 1_000_000) + "MB ===");
        } catch (Throwable t) {
            log("ERROR: " + t);
        } finally {
            try { wl.release(); } catch (Throwable ignore) {}
            RUNNING = false;
            send(ACTION_DONE, "DONE", null, null, null, 100, 100,
                    "Finished — compressed " + compressed + ", skipped " + skipped
                    + ", failed " + failed + ", freed " + (freed / 1_000_000) + "MB");
            stopForeground(true);
            stopSelf();
        }
    }

    /** Shared processing loop: byte-weighted overall progress, per-file percent. */
    private void processItems(List<VideoScanner.Item> items, long totalBytes) {
        long processed = 0;
        for (int i = 0; i < items.size(); i++) {
            if (CANCEL) { log("cancelled by user"); break; }
            final VideoScanner.Item it = items.get(i);
            final int idx = i + 1;
            final long beforeSize = it.file.length();
            final long procBase = processed, totalF = totalBytes;
            String name = it.file.getName();

            updateNotification(name + "  (" + idx + "/" + found + ")");
            send(ACTION_UPDATE, "PROCESS", it.folder, name, "COMPRESSING", 0,
                    pct(procBase, totalF), null);

            String result = VideoScanner.compressInPlace(this, it.file, p -> {
                long procNow = procBase + (long) (beforeSize * (p / 100.0));
                send(ACTION_UPDATE, "PROCESS", it.folder, name, "COMPRESSING",
                        p, pct(procNow, totalF), null);
            });

            processed += it.size;
            String mark;
            if (result.startsWith("OK")) {
                long after = it.file.length();
                freed += Math.max(0, beforeSize - after);
                compressed++; mark = "OK";
            } else if (result.startsWith("SKIP")) {
                skipped++; mark = "SKIP";
            } else {
                failed++; mark = "FAIL";
            }
            done++;
            String line = String.format(Locale.US, "[%d/%d | %d%%] %s: %s",
                    idx, found, pct(processed, totalBytes), name, result);
            log(line);
            send(ACTION_UPDATE, "PROCESS", it.folder, name, mark, 100,
                    pct(processed, totalBytes), line);
        }
    }

    private int pct(long part, long total) {
        return total > 0 ? (int) (part * 100 / total) : 0;
    }

    private void send(String action, String phase, String folder, String file,
                      String mark, int filePct, int overall, String logLine) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        i.putExtra(EX_PHASE, phase);
        i.putExtra(EX_FOLDER, folder);
        i.putExtra(EX_FILE, file);
        i.putExtra(EX_MARK, mark);
        i.putExtra(EX_FILEPCT, filePct);
        i.putExtra(EX_OVERALL, overall);
        i.putExtra(EX_FOUND, found);
        i.putExtra(EX_DONE, done);
        i.putExtra(EX_COMPRESSED, compressed);
        i.putExtra(EX_SKIPPED, skipped);
        i.putExtra(EX_FAILED, failed);
        i.putExtra(EX_FREED, freed);
        i.putExtra(EX_LOGLINE, logLine);
        sendBroadcast(i);
    }

    private void log(String s) {
        try (FileWriter w = new FileWriter(LOG, true)) { w.write(now() + "  " + s + "\n"); }
        catch (Throwable ignore) {}
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date());
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "VidShrink",
                    NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("VidShrink")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true).build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(1, buildNotification(text));
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
