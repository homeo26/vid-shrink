/*
 * MainActivity — programmatic UI, no layout XML, no third-party libraries.
 * Everything is drawn with plain Android widgets plus a few GradientDrawables,
 * which keeps the APK at ~60 KB.
 *
 * Layout:
 *   header            app name + one-line explanation
 *   card "Compress"   size threshold, folder chips, custom path, optional
 *                     date filter, primary Start button, Scan / Pick a video
 *   card "Progress"   status, current file, two progress bars with percent
 *                     labels, counter chips, Stop
 *   card "Recovery"   (collapsed) rotation repair + portrait un-squash
 *   card "Log"        newest-first per-file results
 */
package com.homeo.vidshrink;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // ---- palette ----
    private static final int BG      = 0xFF0F1115;
    private static final int CARD    = 0xFF181B21;
    private static final int STROKE  = 0xFF272B33;
    private static final int TEXT    = 0xFFE8EAED;
    private static final int MUTED   = 0xFF9AA0A6;
    private static final int ACCENT  = 0xFF4C8BF5;
    private static final int OK      = 0xFF34A853;
    private static final int WARN    = 0xFFFBBC04;
    private static final int ERR     = 0xFFEA4335;

    private static final int REQ_PICK = 1001;

    private static final String[][] PRESETS = {
        {"Camera",        "/storage/emulated/0/DCIM/Camera"},
        {"DCIM (all)",    "/storage/emulated/0/DCIM"},
        {"Movies",        "/storage/emulated/0/Movies"},
        {"Download",      "/storage/emulated/0/Download"},
        {"Pictures",      "/storage/emulated/0/Pictures"},
        {"WhatsApp",      "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"},
        {"Whole storage", "/storage/emulated/0"},
    };

    private EditText thresholdField, customFolder, fromDate, toDate;
    private final List<CheckBox> presetBoxes = new ArrayList<>();
    private TextView statusLine, fileLine, filePctLabel, overallPctLabel, counters, logView;
    private TextView pickedLine;
    private Button compressPicked;
    private View filtersBox, recoveryBox;
    private ProgressBar fileBar, overallBar;
    private final StringBuilder logBuf = new StringBuilder();
    private String pickedPath;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String phase = i.getStringExtra(CompressionService.EX_PHASE);
            String folder = i.getStringExtra(CompressionService.EX_FOLDER);
            String file = i.getStringExtra(CompressionService.EX_FILE);
            String mark = i.getStringExtra(CompressionService.EX_MARK);
            int filePct = i.getIntExtra(CompressionService.EX_FILEPCT, 0);
            int overall = i.getIntExtra(CompressionService.EX_OVERALL, 0);
            int found = i.getIntExtra(CompressionService.EX_FOUND, 0);
            int done = i.getIntExtra(CompressionService.EX_DONE, 0);
            int comp = i.getIntExtra(CompressionService.EX_COMPRESSED, 0);
            int skip = i.getIntExtra(CompressionService.EX_SKIPPED, 0);
            int fail = i.getIntExtra(CompressionService.EX_FAILED, 0);
            long freed = i.getLongExtra(CompressionService.EX_FREED, 0);
            String logLine = i.getStringExtra(CompressionService.EX_LOGLINE);

            if ("SCAN".equals(phase)) {
                statusLine.setText("Scanning…  " + found + " found");
                statusLine.setTextColor(ACCENT);
                fileLine.setText(shortFolder(folder));
            } else if ("DONE".equals(phase)) {
                statusLine.setText("Finished");
                statusLine.setTextColor(OK);
                fileLine.setText(logLine == null ? "" : logLine);
                if (logLine != null) Toast.makeText(c, logLine, Toast.LENGTH_LONG).show();
            } else {
                statusLine.setText("Working…  " + overall + "%");
                statusLine.setTextColor(ACCENT);
                if (file != null) fileLine.setText(mark == null ? file : mark + "  ·  " + file);
                else if (folder != null) fileLine.setText(shortFolder(folder));
            }
            fileBar.setProgress(filePct);
            overallBar.setProgress(overall);
            filePctLabel.setText(filePct + "%");
            overallPctLabel.setText(overall + "%");
            counters.setText("found " + found + "   ·   done " + done
                    + "   ·   ok " + comp + "   ·   skipped " + skip
                    + "   ·   failed " + fail + "   ·   freed " + (freed / 1_000_000) + " MB");
            if (logLine != null) {
                logBuf.insert(0, logLine + "\n");
                logView.setText(logBuf.toString());
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();   // our own header instead
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BG);
        LinearLayout root = col();
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        page.addView(root);

        // ---------- header ----------
        TextView h = new TextView(this);
        h.setText("VidShrink");
        h.setTextSize(26);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(TEXT);
        root.addView(h);
        root.addView(muted("Shrinks large videos in place with the phone's hardware "
                + "HEVC encoder. An original is replaced only after a smaller copy is "
                + "verified, and already-efficient files are skipped — so re-runs are safe."));

        // ---------- compress card ----------
        LinearLayout compressCard = card(root, "Compress");

        LinearLayout thrRow = row();
        thrRow.addView(body("Only videos larger than"));
        thresholdField = num("200");
        thrRow.addView(thresholdField);
        thrRow.addView(body("MB"));
        compressCard.addView(thrRow);

        compressCard.addView(sectionLabel("Folders to scan"));
        for (String[] preset : PRESETS) {
            CheckBox cb = new CheckBox(this);
            cb.setText(preset[0]);
            cb.setTag(preset[1]);
            cb.setTextColor(TEXT);
            cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            cb.setChecked("Camera".equals(preset[0]));
            presetBoxes.add(cb);
            compressCard.addView(cb);
        }
        customFolder = text("/storage/emulated/0/… (optional custom folder)");
        compressCard.addView(customFolder);

        // collapsible date filter
        Button filtersToggle = linkButton("▸  Date filter (optional)");
        compressCard.addView(filtersToggle);
        LinearLayout filters = col();
        filters.setVisibility(View.GONE);
        filtersBox = filters;
        LinearLayout dr = row();
        fromDate = text("from yyyy-MM-dd");
        toDate = text("to yyyy-MM-dd");
        dr.addView(flex(fromDate)); dr.addView(flex(toDate));
        filters.addView(dr);
        compressCard.addView(filters);
        filtersToggle.setOnClickListener(v -> {
            boolean show = filtersBox.getVisibility() == View.GONE;
            filtersBox.setVisibility(show ? View.VISIBLE : View.GONE);
            filtersToggle.setText((show ? "▾" : "▸") + "  Date filter (optional)");
        });

        Button start = primaryButton("Start compression");
        start.setOnClickListener(v -> doStart());
        compressCard.addView(start);

        LinearLayout btnRow = row();
        Button scan = secondaryButton("Scan only");
        scan.setOnClickListener(v -> doScan());
        Button pick = secondaryButton("Pick a video…");
        pick.setOnClickListener(v -> doPick());
        btnRow.addView(flex(scan)); btnRow.addView(flex(pick));
        compressCard.addView(btnRow);

        // chosen-file row (hidden until a video is picked)
        pickedLine = body("");
        pickedLine.setVisibility(View.GONE);
        pickedLine.setTextColor(WARN);
        compressCard.addView(pickedLine);
        compressPicked = primaryButton("Compress this video");
        compressPicked.setVisibility(View.GONE);
        compressPicked.setOnClickListener(v -> doCompressPicked());
        compressCard.addView(compressPicked);

        // ---------- progress card ----------
        LinearLayout progCard = card(root, "Progress");
        statusLine = new TextView(this);
        statusLine.setText("Idle");
        statusLine.setTextSize(18);
        statusLine.setTypeface(Typeface.DEFAULT_BOLD);
        statusLine.setTextColor(MUTED);
        progCard.addView(statusLine);
        fileLine = muted("—");
        fileLine.setSingleLine(true);
        fileLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        progCard.addView(fileLine);

        progCard.addView(sectionLabel("Current file"));
        fileBar = bar(ACCENT);
        filePctLabel = pctLabel();
        progCard.addView(barRow(fileBar, filePctLabel));

        progCard.addView(sectionLabel("Overall"));
        overallBar = bar(OK);
        overallPctLabel = pctLabel();
        progCard.addView(barRow(overallBar, overallPctLabel));

        counters = muted("found 0   ·   done 0   ·   ok 0   ·   skipped 0"
                + "   ·   failed 0   ·   freed 0 MB");
        counters.setTextSize(12);
        progCard.addView(counters);

        Button stop = secondaryButton("Stop");
        stop.setOnClickListener(v -> {
            CompressionService.CANCEL = true;
            statusLine.setText("Stopping after current file…");
            statusLine.setTextColor(WARN);
        });
        progCard.addView(stop);

        // ---------- recovery card (collapsed) ----------
        LinearLayout recCard = card(root, "Recovery tools");
        Button recToggle = linkButton("▸  Show");
        recCard.addView(recToggle);
        LinearLayout rec = col();
        rec.setVisibility(View.GONE);
        recoveryBox = rec;
        rec.addView(muted("Reads a newline-separated list of file paths from "
                + "Download/vidshrink-repair.txt or Download/vidshrink-unsquash.txt."));
        Button repair = secondaryButton("Repair rotation flag");
        repair.setOnClickListener(v -> doRepair());
        Button unsquash = secondaryButton("Un-squash portrait");
        unsquash.setOnClickListener(v -> doUnsquash());
        rec.addView(repair); rec.addView(unsquash);
        recCard.addView(rec);
        recToggle.setOnClickListener(v -> {
            boolean show = recoveryBox.getVisibility() == View.GONE;
            recoveryBox.setVisibility(show ? View.VISIBLE : View.GONE);
            recToggle.setText(show ? "▾  Hide" : "▸  Show");
        });

        // ---------- log ----------
        LinearLayout logCard = card(root, "Log");
        logView = new TextView(this);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(MUTED);
        logView.setText("No runs yet.");
        logCard.addView(logView);

        setContentView(page);
        ensureAllFilesAccess();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(CompressionService.ACTION_UPDATE);
        f.addAction(CompressionService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rx, f);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(rx); } catch (Throwable ignore) {}
    }

    // ---------------- single-video picking ----------------

    private void doPick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        String path = resolvePath(data.getData());
        if (path == null || !new File(path).isFile()) {
            pickedPath = null;
            pickedLine.setVisibility(View.VISIBLE);
            pickedLine.setTextColor(ERR);
            pickedLine.setText("Could not resolve that file to a storage path. "
                    + "Pick it from internal storage (not Drive/cloud).");
            compressPicked.setVisibility(View.GONE);
            return;
        }
        File f = new File(path);
        pickedPath = path;
        pickedLine.setVisibility(View.VISIBLE);
        pickedLine.setTextColor(WARN);
        pickedLine.setText("Selected:  " + f.getName()
                + "   (" + (f.length() / 1_000_000) + " MB)");
        compressPicked.setVisibility(View.VISIBLE);
    }

    private void doCompressPicked() {
        if (pickedPath == null) return;
        if (!guard()) return;
        resetLog();
        Intent i = new Intent(this, CompressionService.class);
        i.putExtra("singleFile", pickedPath);
        startSvc(i, "Compressing selected video…");
    }

    /**
     * Turn a picker Uri into a real filesystem path. Handles the three cases we
     * can act on: a plain file:// Uri, a MediaStore/document id we can look up
     * in MediaStore, and the external-storage document form "primary:Movies/x".
     * Returns null for anything not on local storage (e.g. Drive), because
     * in-place replacement needs a real path.
     */
    private String resolvePath(Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) return uri.getPath();

            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String auth = uri.getAuthority();
                if ("com.android.externalstorage.documents".equals(auth)) {
                    String[] parts = docId.split(":", 2);
                    if (parts.length == 2 && "primary".equalsIgnoreCase(parts[0]))
                        return "/storage/emulated/0/" + parts[1];
                    if (parts.length == 2)
                        return "/storage/" + parts[0] + "/" + parts[1];
                } else if ("com.android.providers.media.documents".equals(auth)) {
                    String[] parts = docId.split(":", 2);
                    if (parts.length == 2) {
                        Uri content = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        String p = queryData(content, "_id=?", new String[]{parts[1]});
                        if (p != null) return p;
                    }
                } else if ("com.android.providers.downloads.documents".equals(auth)) {
                    try {
                        Uri content = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"),
                                Long.parseLong(docId));
                        String p = queryData(content, null, null);
                        if (p != null) return p;
                    } catch (NumberFormatException ignore) { /* raw path form below */ }
                    if (docId.startsWith("raw:")) return docId.substring(4);
                }
            }
            if ("content".equalsIgnoreCase(uri.getScheme()))
                return queryData(uri, null, null);
        } catch (Throwable ignore) {}
        return null;
    }

    private String queryData(Uri uri, String sel, String[] args) {
        try (Cursor c = getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DATA}, sel, args, null)) {
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty()) return p;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ---------------- actions ----------------

    private String[] selectedRoots() {
        List<String> r = new ArrayList<>();
        for (CheckBox cb : presetBoxes) if (cb.isChecked()) r.add((String) cb.getTag());
        String custom = customFolder.getText().toString().trim();
        if (!custom.isEmpty() && custom.startsWith("/")) r.add(custom);
        if (r.isEmpty()) r.add("/storage/emulated/0/DCIM/Camera");
        return r.toArray(new String[0]);
    }

    private long thresholdBytes() {
        long mb;
        try { mb = Long.parseLong(thresholdField.getText().toString().trim()); }
        catch (Throwable t) { mb = 200; }
        return mb * 1024 * 1024;
    }

    private long parseDate(EditText e, boolean endOfDay) {
        String s = e.getText().toString().trim();
        if (s.isEmpty()) return 0;
        try {
            long t = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s).getTime();
            return endOfDay ? t + 86_399_000L : t;
        } catch (Throwable t) { return 0; }
    }

    private void doScan() {
        if (!guard()) return;
        statusLine.setText("Scanning…");
        statusLine.setTextColor(ACCENT);
        logBuf.setLength(0);
        logView.setText("");
        final String[] roots = selectedRoots();
        final long thr = thresholdBytes();
        final long from = parseDate(fromDate, false), to = parseDate(toDate, true);
        new Thread(() -> {
            final int[] folders = {0};
            List<VideoScanner.Item> items = VideoScanner.scan(roots, thr, from, to,
                    (folder, n) -> {
                        folders[0]++;
                        runOnUiThread(() -> fileLine.setText(shortFolder(folder)));
                    });

            // Second pass: ask the transcoder's own rule whether each file would
            // be skipped, so the preview matches what a real run would do.
            int todo = 0, opt = 0;
            long todoBytes = 0, optBytes = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                VideoScanner.Item it = items.get(i);
                final int n = i + 1, total = items.size();
                runOnUiThread(() -> fileLine.setText("checking " + n + "/" + total + "…"));
                boolean skip = VideoTranscoder.wouldSkip(it.file.getAbsolutePath());
                // tenths of a Mbps, so the listing can show one decimal place
                long tenthsMbps =
                        VideoTranscoder.sourceBitrate(it.file.getAbsolutePath()) / 100_000;
                if (skip) { opt++; optBytes += it.size; } else { todo++; todoBytes += it.size; }
                sb.append(skip ? "OPTIMIZED  " : "COMPRESS   ")
                  .append(String.format(Locale.US, "%5d MB  %4.1f Mbps  ",
                          it.size / 1_000_000, tenthsMbps / 10.0))
                  .append(it.file.getName())
                  .append('\n');
            }

            final int fTodo = todo, fOpt = opt;
            final long fTodoB = todoBytes, fOptB = optBytes;
            final String listing = sb.length() == 0
                    ? "Nothing matched the folders / size / date filters."
                    : sb.toString();
            runOnUiThread(() -> {
                statusLine.setText(fTodo + " to compress  ·  " + (fTodoB / 1_000_000) + " MB");
                statusLine.setTextColor(fTodo > 0 ? WARN : OK);
                fileLine.setText(fOpt + " already optimized  ·  " + (fOptB / 1_000_000)
                        + " MB  ·  " + folders[0] + " folders scanned");
                counters.setText("found " + (fTodo + fOpt) + "   ·   to compress " + fTodo
                        + "   ·   already optimized " + fOpt
                        + "   ·   total " + ((fTodoB + fOptB) / 1_000_000) + " MB");
                logView.setText(listing);
            });
        }).start();
    }

    private void doStart() {
        if (!guard()) return;
        resetLog();
        Intent i = new Intent(this, CompressionService.class);
        i.putExtra("thresholdBytes", thresholdBytes());
        i.putExtra("roots", selectedRoots());
        i.putExtra("fromMillis", parseDate(fromDate, false));
        i.putExtra("toMillis", parseDate(toDate, true));
        startSvc(i, "Starting…");
    }

    private void doRepair() { listJob("vidshrink-repair.txt", "repairList", "Repairing…"); }
    private void doUnsquash() { listJob("vidshrink-unsquash.txt", "unsquashList", "Un-squashing…"); }

    private void listJob(String fileName, String extra, String status) {
        if (!guard()) return;
        String list = "/storage/emulated/0/Download/" + fileName;
        if (!new File(list).exists()) {
            Toast.makeText(this, "No list at " + list, Toast.LENGTH_LONG).show();
            return;
        }
        resetLog();
        Intent i = new Intent(this, CompressionService.class);
        i.putExtra(extra, list);
        startSvc(i, status);
    }

    /** Permission + already-running check shared by every action. */
    private boolean guard() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            ensureAllFilesAccess();
            return false;
        }
        if (CompressionService.RUNNING) {
            Toast.makeText(this, "A job is already running", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void startSvc(Intent i, String status) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        statusLine.setText(status);
        statusLine.setTextColor(ACCENT);
    }

    private void resetLog() {
        logBuf.setLength(0);
        logView.setText("");
        fileBar.setProgress(0); overallBar.setProgress(0);
        filePctLabel.setText("0%"); overallPctLabel.setText("0%");
    }

    private void ensureAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Grant All-files access, then come back",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    // ---------------- view helpers ----------------

    private String shortFolder(String f) {
        return f == null ? "—" : f.replace("/storage/emulated/0", "…");
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Rounded card with a title; returns the inner column to add content to. */
    private LinearLayout card(LinearLayout parent, String titleText) {
        LinearLayout c = col();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), STROKE);
        c.setBackground(bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        parent.addView(c);
        return c;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(MUTED);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setPadding(0, dp(2), dp(6), dp(2));
        return t;
    }

    private TextView muted(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(MUTED);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView pctLabel() {
        TextView t = new TextView(this);
        t.setText("0%");
        t.setTextSize(12);
        t.setTextColor(MUTED);
        t.setMinWidth(dp(44));
        t.setGravity(Gravity.END);
        return t;
    }

    private LinearLayout barRow(ProgressBar bar, TextView label) {
        LinearLayout r = row();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(10), 1f);
        lp.rightMargin = dp(8);
        bar.setLayoutParams(lp);
        r.addView(bar);
        r.addView(label);
        return r;
    }

    private EditText num(String v) {
        EditText e = new EditText(this);
        e.setText(v);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setWidth(dp(72));
        e.setGravity(Gravity.CENTER);
        e.setBackground(fieldBg());
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        return e;
    }

    private EditText text(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF6B7075);
        e.setTextColor(TEXT);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setBackground(fieldBg());
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        e.setLayoutParams(lp);
        return e;
    }

    /** Re-lay a child so it shares a horizontal row equally. */
    private <T extends View> T flex(T v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                v instanceof Button ? dp(44) : ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable fieldBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF0F1115);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), STROKE);
        return g;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), STROKE);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button linkButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        b.setBackground(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(0, dp(6), 0, dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private ProgressBar bar(int tint) {
        ProgressBar p = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        p.setProgress(0);
        p.setProgressTintList(ColorStateList.valueOf(tint));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
