package com.axzora.happyreddy;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import ai.picovoice.porcupine.Porcupine;

/**
 * Offline wake-word listener using Picovoice Porcupine.
 *
 * No beep, no network, low battery. We run our own AudioRecord and feed 16 kHz
 * frames straight into the native engine (Porcupine low-level API), so we avoid
 * the voice-processor and okhttp dependencies entirely.
 *
 * If a custom "Mr Happy" model is bundled at assets/mr_happy.ppn it is used;
 * otherwise it falls back to the built-in "Jarvis" keyword.
 */
class PorcupineWake {

    interface Callback { void onWake(); }

    private final Context ctx;
    private final Callback cb;
    private Porcupine porcupine;
    private Thread thread;
    private volatile boolean running;

    PorcupineWake(Context ctx, Callback cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    /** @return true if the engine started; false if it couldn't (bad key etc.). */
    boolean start(String accessKey) {
        if (accessKey == null || accessKey.trim().isEmpty()) return false;
        try {
            Porcupine.Builder b = new Porcupine.Builder()
                    .setAccessKey(accessKey.trim())
                    .setSensitivity(0.6f);
            String custom = extractCustomKeyword();
            if (custom != null) {
                b.setKeywordPath(custom);
            } else {
                b.setKeyword(Porcupine.BuiltInKeyword.JARVIS);
            }
            porcupine = b.build(ctx);
        } catch (Throwable e) {
            porcupine = null;
            return false;
        }

        running = true;
        thread = new Thread(this::loop, "hr-porcupine");
        thread.start();
        return true;
    }

    private void loop() {
        AudioRecord rec = null;
        try {
            int frame = porcupine.getFrameLength();          // 512
            int rate = porcupine.getSampleRate();            // 16000
            int minBuf = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, frame * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
            rec.startRecording();
            short[] buf = new short[frame];
            while (running) {
                int n = rec.read(buf, 0, frame);
                if (n == frame && porcupine != null) {
                    int idx = porcupine.process(buf);
                    if (idx >= 0) {
                        cb.onWake();
                        return;   // stop after detection; caller restarts if needed
                    }
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
            try { thread.join(400); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (porcupine != null) {
            try { porcupine.delete(); } catch (Exception ignored) { }
            porcupine = null;
        }
    }

    /** Copy assets/mr_happy.ppn to a file Porcupine can read; null if absent. */
    private String extractCustomKeyword() {
        try (InputStream in = ctx.getAssets().open("mr_happy.ppn")) {
            File out = new File(ctx.getFilesDir(), "mr_happy.ppn");
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;   // no custom model bundled → use built-in
        }
    }
}
