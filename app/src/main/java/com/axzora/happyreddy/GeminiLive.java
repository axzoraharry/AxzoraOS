package com.axzora.happyreddy;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Live voice session with the Gemini Live API (BidiGenerateContent) —
 * the same pipeline the desktop MARK XLVIII uses:
 * mic PCM 16 kHz → WebSocket → Gemini native audio → PCM 24 kHz → speaker.
 */
class GeminiLive {

    interface Listener {
        void onReady();
        void onUserText(String chunk);
        void onModelText(String chunk);
        void onSpeakingStart();
        void onTurnComplete();
        void onToolCall(String id, String name, JSONObject args);
        void onAudioLevel(float level); // 0..1 RMS of speech being played
        void onClosed(String reason);   // normal close, error, or server goAway
    }

    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";

    static final int RECEIVE_RATE = 24000;

    private final Listener listener;
    private WsClient ws;
    private Thread wsThread;
    private Thread playThread;
    private AudioTrack track;
    private final LinkedBlockingQueue<byte[]> playQueue = new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile boolean ready;
    private volatile boolean speaking;

    GeminiLive(Listener listener) {
        this.listener = listener;
    }

    boolean isReady()      { return ready; }
    boolean isRunning()    { return running; }
    boolean isSpeakingNow(){ return speaking; }

    void start(String apiKey, String model, String systemPrompt, JSONArray tools) {
        if (running) return;
        running = true;

        int minBuf = AudioTrack.getMinBufferSize(RECEIVE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RECEIVE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf * 4, 32000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        playThread = new Thread(this::playLoop, "hr-audio-out");
        playThread.start();

        String path;
        try {
            path = PATH + URLEncoder.encode(apiKey, "UTF-8");
        } catch (Exception e) {
            path = PATH + apiKey;
        }

        final String setupMsg = buildSetup(model, systemPrompt, tools);
        ws = new WsClient(HOST, path, new WsClient.Handler() {
            @Override public void onOpen() {
                try {
                    ws.sendText(setupMsg);
                } catch (IOException e) {
                    shutdown("Setup send failed: " + e.getMessage());
                }
            }
            @Override public void onText(String message) {
                try {
                    handleMessage(message);
                } catch (Exception ignored) { }
            }
            @Override public void onClose(int code, String reason) {
                shutdown(reason == null || reason.isEmpty()
                        ? ("Connection closed (" + code + ")") : reason);
            }
            @Override public void onFailure(Exception e) {
                shutdown(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        wsThread = new Thread(ws::connect, "hr-ws");
        wsThread.start();
    }

    private static String buildSetup(String model, String systemPrompt, JSONArray tools) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", model.startsWith("models/") ? model : "models/" + model);
            setup.put("generationConfig", new JSONObject()
                    .put("responseModalities", new JSONArray().put("AUDIO")));
            setup.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(
                            new JSONObject().put("text", systemPrompt))));
            if (tools != null && tools.length() > 0) {
                setup.put("tools", new JSONArray().put(
                        new JSONObject().put("functionDeclarations", tools)));
            }
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());
            // Balanced turn-taking: responsive without cutting the user off.
            // (Echo/self-interruption is handled by half-duplex mic gating.)
            setup.put("realtimeInputConfig", new JSONObject()
                    .put("automaticActivityDetection", new JSONObject()
                            .put("prefixPaddingMs", 40)
                            .put("silenceDurationMs", 500)));
            return new JSONObject().put("setup", setup).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void handleMessage(String msg) throws Exception {
        JSONObject o = new JSONObject(msg);

        if (o.has("setupComplete")) {
            ready = true;
            listener.onReady();
            return;
        }
        if (o.has("goAway")) {
            shutdown("Session time limit — reconnecting");
            return;
        }

        JSONObject tc = o.optJSONObject("toolCall");
        if (tc != null) {
            JSONArray fcs = tc.optJSONArray("functionCalls");
            if (fcs != null) {
                for (int i = 0; i < fcs.length(); i++) {
                    JSONObject fc = fcs.getJSONObject(i);
                    JSONObject args = fc.optJSONObject("args");
                    listener.onToolCall(fc.optString("id"), fc.optString("name"),
                            args != null ? args : new JSONObject());
                }
            }
            return;
        }

        JSONObject sc = o.optJSONObject("serverContent");
        if (sc == null) return;

        if (sc.optBoolean("interrupted", false)) {
            // barge-in: user spoke over the model — drop pending audio instantly
            playQueue.clear();
            try {
                track.pause();
                track.flush();
                track.play();
            } catch (Exception ignored) { }
            speaking = false;
        }

        JSONObject it = sc.optJSONObject("inputTranscription");
        if (it != null) {
            String t = it.optString("text", "");
            if (!t.isEmpty()) listener.onUserText(t);
        }
        JSONObject ot = sc.optJSONObject("outputTranscription");
        if (ot != null) {
            String t = ot.optString("text", "");
            if (!t.isEmpty()) listener.onModelText(t);
        }

        JSONObject mt = sc.optJSONObject("modelTurn");
        if (mt != null) {
            JSONArray parts = mt.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (inline == null) continue;
                    String data = inline.optString("data", "");
                    if (data.isEmpty()) continue;
                    playQueue.offer(Base64.decode(data, Base64.DEFAULT));
                    if (!speaking) {
                        speaking = true;
                        listener.onSpeakingStart();
                    }
                }
            }
        }

        if (sc.optBoolean("turnComplete", false)) {
            speaking = false;
            listener.onTurnComplete();
        }
    }

    private void playLoop() {
        while (running) {
            byte[] chunk;
            try {
                chunk = playQueue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (chunk == null) continue;
            try {
                // RMS of this chunk → waveform in the HUD
                long sum = 0;
                for (int i = 0; i + 1 < chunk.length; i += 2) {
                    int s = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                    sum += (long) s * s;
                }
                int n = Math.max(1, chunk.length / 2);
                listener.onAudioLevel((float) (Math.sqrt((double) sum / n) / 32768.0));
                track.write(chunk, 0, chunk.length);
            } catch (Exception ignored) { }
        }
    }

    void sendAudio(byte[] pcm, int len) {
        if (!ready || ws == null) return;
        try {
            String b64 = Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP);
            JSONObject j = new JSONObject().put("realtimeInput", new JSONObject()
                    .put("audio", new JSONObject()
                            .put("data", b64)
                            .put("mimeType", "audio/pcm;rate=16000")));
            ws.sendText(j.toString());
        } catch (Exception ignored) { }
    }

    /** Manual interrupt (HUD button): silence current speech instantly. */
    void interruptPlayback() {
        playQueue.clear();
        speaking = false;
        try {
            if (track != null) {
                track.pause();
                track.flush();
                track.play();
            }
        } catch (Exception ignored) { }
    }

    void sendToolResponse(String id, String name, String result) {
        if (ws == null) return;
        try {
            JSONObject fr = new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("response", new JSONObject().put("result", result));
            JSONObject j = new JSONObject().put("toolResponse", new JSONObject()
                    .put("functionResponses", new JSONArray().put(fr)));
            ws.sendText(j.toString());
        } catch (Exception ignored) { }
    }

    /** Send a JPEG image (base64) into the live conversation for analysis. */
    void sendImage(String b64Jpeg) {
        if (!ready || ws == null) return;
        try {
            JSONObject j = new JSONObject().put("realtimeInput", new JSONObject()
                    .put("video", new JSONObject()
                            .put("data", b64Jpeg)
                            .put("mimeType", "image/jpeg")));
            ws.sendText(j.toString());
        } catch (Exception ignored) { }
    }

    void sendText(String text) {
        if (!ready || ws == null) return;
        try {
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
            JSONObject j = new JSONObject().put("clientContent", new JSONObject()
                    .put("turns", new JSONArray().put(turn))
                    .put("turnComplete", true));
            ws.sendText(j.toString());
        } catch (Exception ignored) { }
    }

    private void shutdown(String reason) {
        if (!running) return;
        stop();
        listener.onClosed(reason);
    }

    void stop() {
        running = false;
        ready = false;
        speaking = false;
        playQueue.clear();
        if (ws != null) ws.close();
        if (playThread != null) playThread.interrupt();
        if (track != null) {
            try {
                track.stop();
                track.release();
            } catch (Exception ignored) { }
            track = null;
        }
    }
}
