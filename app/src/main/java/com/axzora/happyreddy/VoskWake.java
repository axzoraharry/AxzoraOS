package com.axzora.happyreddy;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;
import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Offline, keyless wake-word listener using Vosk (Apache-2.0).
 *
 * No beep, no account, no network. Runs its own 16 kHz AudioRecord and does
 * free-form recognition on-device; when the transcript contains the wake word
 * ("jarvis" / "mr happy") it fires the callback. The ~40 MB model ships in
 * assets and is copied to internal storage once on first launch.
 */
class VoskWake {

    interface Callback { void onWake(); void onPreparing(); void onFailed(); }

    private final Context ctx;
    private final Callback cb;
    /** Loaded once and kept: reloading the ~40 MB model after every
     *  conversation left him deaf for a second or two each time. */
    private static Model sharedModel;
    private Model model;
    private Recognizer recognizer;
    private Thread thread;
    private volatile boolean running;

    VoskWake(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    private static boolean hit(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("partial", o.optString("text", ""));
            t = t.toLowerCase(Locale.ROOT);
            // Wake phrase is "Mr Happy". Vosk hears the title as mr / mister /
            // hey / miss / master, so accept those — but require the title, so
            // an ordinary sentence containing "happy" doesn't wake him.
            return t.contains("mr happy") || t.contains("mister happy")
                    || t.contains("hey happy") || t.contains("miss happy")
                    || t.contains("master happy") || t.contains("mr. happy")
                    || t.contains("hello happy") || t.contains("ok happy");
        } catch (Exception e) {
            return false;
        }
    }

    /** Non-blocking: model prep + load + mic loop all run on a worker thread. */
    void start() {
        running = true;
        thread = new Thread(this::run, "hr-vosk");
        thread.start();
    }

    private void run() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            String modelPath = ensureModel();     // copies ~40 MB on first run
            if (modelPath == null) { cb.onFailed(); return; }
            synchronized (VoskWake.class) {
                if (sharedModel == null) sharedModel = new Model(modelPath);
                model = sharedModel;
            }
            recognizer = new Recognizer(model, 16000.0f);
        } catch (Throwable e) {
            release();
            cb.onFailed();
            return;
        }
        loop();
    }

    private void loop() {
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            rec.startRecording();
            short[] buf = new short[3200];   // 200 ms
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0 || recognizer == null) continue;
                boolean end = recognizer.acceptWaveForm(buf, n);
                String r = end ? recognizer.getResult() : recognizer.getPartialResult();
                if (hit(r)) {
                    recognizer.reset();
                    cb.onWake();
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
        }
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        release();
    }

    private void release() {
        if (recognizer != null) { try { recognizer.close(); } catch (Exception ignored) { } recognizer = null; }
        model = null;   // shared — stays loaded for the next listen
    }

    // ── Copy the bundled model from assets to internal storage (once) ────────

    private String ensureModel() {
        File dst = new File(ctx.getFilesDir(), "vosk-model");
        File done = new File(dst, ".ready");
        if (done.exists()) return dst.getAbsolutePath();
        cb.onPreparing();
        try {
            if (dst.exists()) deleteRec(dst);
            dst.mkdirs();
            copyAssetDir("model", dst);
            new FileOutputStream(done).close();   // marker
            return dst.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void copyAssetDir(String assetPath, File outDir) throws Exception {
        AssetManager am = ctx.getAssets();
        String[] items = am.list(assetPath);
        if (items == null || items.length == 0) {
            copyAssetFile(assetPath, outDir);       // it's a file
            return;
        }
        outDir.mkdirs();
        for (String it : items) {
            copyAssetDir(assetPath + "/" + it, new File(outDir, it));
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws Exception {
        outFile.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream os = new FileOutputStream(outFile)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
