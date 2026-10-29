package com.axzora.happyreddy;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A Mr Happy conversation with no screen.
 *
 * When the always-on ear hears "Mr Happy" the service runs the whole
 * conversation right here — Gemini Live session, microphone, tool calls,
 * memory — so he answers in a second from wherever the user is, without his
 * screen taking over the phone. It's the same pipeline as the HUD screen
 * (same persona, same tools, same half-duplex mic gating, same key rotation),
 * minus the WebView. Ends on "stop" or after a quiet spell.
 */
final class BackgroundSession implements GeminiLive.Listener {

    interface Host {
        /** 'connecting' | 'live' | 'speaking' | 'done' */
        void onState(String state);
        void onEnded();
    }

    private static final String PREFS       = "happyreddy_prefs";
    private static final String KEY_API     = "gemini_api_key";
    private static final String KEY_MODEL   = "gemini_model";
    private static final String MODEL_DEFAULT = "models/gemini-3.1-flash-live-preview";
    /** End the conversation after this much silence following his last reply. */
    private static final long IDLE_MS = 25_000L;

    private final Context ctx;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MemoryStore mem;
    private final List<String> keys = new ArrayList<>();
    private final StringBuilder utter = new StringBuilder();

    private GeminiLive live;
    private Thread micThread;
    private volatile boolean micOn;
    private volatile boolean ended;
    private int keyIdx, keysTried, reconnects;
    private boolean greet = true;

    private final Runnable idle = this::end;

    BackgroundSession(Context ctx, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.mem = new MemoryStore(this.ctx);
        loadKeys();
    }

    boolean isRunning() { return !ended && live != null; }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    void start() {
        earcon();                      // "I heard you" — instant, before the network
        startSession();
    }

    /** Stop everything and hand the mic back to the ear. */
    void end() {
        if (ended) return;
        ended = true;
        ui.removeCallbacks(idle);
        micOn = false;
        micThread = null;
        GeminiLive l = live;
        live = null;
        if (l != null) new Thread(l::stop, "hr-bg-stop").start();
        mem.flush();
        host.onState("done");
        host.onEnded();
    }

    private void startSession() {
        if (ended) return;
        if (currentKey().isEmpty()) { end(); return; }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) { end(); return; }
        host.onState("connecting");
        live = new GeminiLive(this);
        live.start(currentKey(),
                prefs().getString(KEY_MODEL, MODEL_DEFAULT),
                MainActivity.PERSONA + mem.promptBlock()
                        + "\nCONTEXT: You were woken by voice while the user is busy "
                        + "elsewhere on the phone — there is no screen showing you. "
                        + "Be quick and spoken-only.",
                DeviceActions.declarations(ctx));
    }

    private void armIdle() {
        ui.removeCallbacks(idle);
        ui.postDelayed(idle, IDLE_MS);
    }

    private void earcon() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            ui.postDelayed(tg::release, 400);
        } catch (Exception ignored) { }
    }

    // ── Keys (same pool as the HUD screen) ───────────────────────────────────

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void loadKeys() {
        keys.clear();
        try (InputStream in = ctx.getAssets().open("keys.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject cfg = new JSONObject(b.toString("UTF-8"));
            JSONArray arr = cfg.optJSONArray("keys");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, "").trim();
                    if (!k.isEmpty() && !k.startsWith("PUT_") && !keys.contains(k)) keys.add(k);
                }
            }
        } catch (Exception ignored) { }
        String saved = prefs().getString(KEY_API, "").trim();
        if (!saved.isEmpty() && !keys.contains(saved)) keys.add(saved);
    }

    private String currentKey() {
        if (keys.isEmpty()) return "";
        return keys.get(Math.min(keyIdx, keys.size() - 1));
    }

    // ── Microphone (half-duplex, like the HUD) ───────────────────────────────

    private void startMic() {
        if (micThread != null) return;
        micOn = true;
        micThread = new Thread(() -> {
            AudioRecord rec = null;
            try {
                int minBuf = AudioRecord.getMinBufferSize(16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 8192));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
                try {
                    if (AcousticEchoCanceler.isAvailable())
                        AcousticEchoCanceler.create(rec.getAudioSessionId());
                } catch (Exception ignored) { }
                rec.startRecording();
                byte[] buf = new byte[2048];
                while (micOn) {
                    int n = rec.read(buf, 0, buf.length);
                    GeminiLive l = live;
                    // While he's speaking, don't feed the speaker back into
                    // the mic — otherwise he hears himself and cuts off.
                    if (n > 0 && l != null && !l.isSpeakingNow()) l.sendAudio(buf, n);
                }
            } catch (SecurityException ignored) {
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignored) { }
                    rec.release();
                }
            }
        }, "hr-bg-mic");
        micThread.start();
    }

    // ── GeminiLive.Listener ──────────────────────────────────────────────────

    @Override public void onReady() {
        ui.post(() -> {
            if (ended) return;
            reconnects = 0;
            keysTried = 0;
            utter.setLength(0);
            startMic();
            host.onState("live");
            GeminiLive l = live;
            if (l != null && greet) {
                greet = false;
                l.sendText("(You were just woken by your wake word \"Mr Happy\". "
                        + "Reply with a brief 'Yes, sir?' style greeting, then wait "
                        + "for the request.)");
            }
            armIdle();
        });
    }

    @Override public void onUserText(String chunk) {
        mem.noteSpeech(chunk);
        ui.post(() -> {
            if (ended) return;
            ui.removeCallbacks(idle);          // the user is talking
            utter.append(' ').append(chunk);
            if (MainActivity.matchesStop(utter.toString())) {
                utter.setLength(0);
                end();
            }
        });
    }

    @Override public void onModelText(String chunk) {
        mem.flush();
    }

    @Override public void onSpeakingStart() {
        ui.post(() -> { if (!ended) { host.onState("speaking"); ui.removeCallbacks(idle); } });
    }

    @Override public void onAudioLevel(float level) { }

    @Override public void onToolCall(String id, String name, JSONObject args) {
        new Thread(() -> {
            String result;
            try {
                // Sensitive tools (send message/call/share) need the user's OK,
                // and the background session has no screen to ask on — so refuse.
                if (DeviceActions.isSensitive(name)) {
                    result = DeviceActions.requireConfirmation(name, args);
                } else {
                    result = DeviceActions.execute(ctx, name, args);
                }
            } catch (Throwable t) {
                result = "Failed: " + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
            GeminiLive l = live;
            if (l != null) l.sendToolResponse(id, name, result);
        }, "hr-bg-tool").start();
    }

    @Override public void onTurnComplete() {
        ui.post(() -> {
            if (ended) return;
            utter.setLength(0);
            host.onState("live");
            armIdle();                         // quiet after his reply → wind down
        });
    }

    @Override public void onClosed(String reason) {
        ui.post(() -> {
            micOn = false;
            micThread = null;
            live = null;
            if (ended) return;

            String r = reason == null ? "" : reason.toLowerCase();
            boolean keyErr = r.contains("api key") || r.contains("api_key_invalid")
                    || r.contains("permission_denied") || r.contains("quota")
                    || r.contains("resource_exhausted") || r.contains("exceeded")
                    || r.contains("429") || r.contains("1008") || r.contains("1011");
            if (keyErr && keys.size() > 1 && ++keysTried < keys.size()) {
                keyIdx = (keyIdx + 1) % keys.size();
                ui.postDelayed(this::startSession, 600);
                return;
            }
            if (!keyErr && reconnects < 3) {
                reconnects++;
                ui.postDelayed(this::startSession, 1200);
                return;
            }
            end();
        });
    }
}
