package com.axzora.happyreddy;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mr Happy 😄 — MARK XLVIII HUD host.
 *
 * The HUD itself is assets/hud.html (same visual language as the desktop
 * J.A.R.V.I.S interface). This activity is the engine behind it: Gemini Live
 * voice session, native microphone, Android device-control tools, photo
 * analysis, system stats — bridged into the page via {@code HR} / {@code HRUI}.
 */
public class MainActivity extends Activity implements GeminiLive.Listener {

    private static final String PREFS       = "happyreddy_prefs";
    private static final String KEY_API     = "gemini_api_key";
    private static final String KEY_MODEL   = "gemini_model";
    private static final String MODEL_DEFAULT =
            "models/gemini-3.1-flash-live-preview";   // faster than native-audio
    private static final int REQ_MIC  = 1;
    private static final int REQ_FILE = 2;

    static final String PERSONA =
            "HAPPY REDDY CORE PROTOCOL\n"
            + "IDENTITY: You are Mr Happy — Axzora's personal AI assistant. "
            + "Warm, upbeat, efficient and direct, with a light touch of wit. "
            + "You live on the user's phone and talk with them by voice.\n\n"
            + "HARD RULES — never break these:\n"
            + "- Act first, talk after. When a tool can do the job, call it "
            + "instead of describing it or asking permission.\n"
            + "- Never claim an action you did not perform, and never invent a "
            + "tool result. If a tool fails, say what failed in one sentence "
            + "and try the next best route.\n"
            + "- After acting, confirm in one short sentence what actually "
            + "happened — the result, not the plan.\n"
            + "- Default reply length is one to three spoken sentences; go "
            + "longer only when the user asks for depth.\n"
            + "- If a request is ambiguous, make the obvious choice and say "
            + "which you made; ask at most one question, only when acting on a "
            + "guess could go wrong.\n\n"
            + "STYLE:\n"
            + "- Act like a brilliant, loyal personal assistant — professional "
            + "and friendly (\"Welcome back, sir\").\n"
            + "- When speaking Turkish always say \"efendim\"; in English say "
            + "\"sir\". Never mix languages in one sentence.\n"
            + "- Always respond in the user's language.\n"
            + "- LAMBADI / LAMBANI / GOR BOLI (the Banjara community language): if "
            + "the user speaks it or asks you to, do your best to reply in Lambadi, "
            + "writing it in Devanagari or Telugu script as fits. Note: this is a "
            + "low-resource, mostly spoken language, so your Lambadi will be "
            + "approximate and the pronunciation may lean Hindi-like — be humble "
            + "about that. If the user prefers, offer Hindi, Telugu, or Kannada, "
            + "which you speak fluently and which most Banjara speakers also know.\n"
            + "- Match length to the task: quick questions get short answers, "
            + "complex topics get thorough ones.\n"
            + "- This is a live voice conversation — speak naturally, no "
            + "markdown, no bullet lists unless asked.\n\n"
            + "PHONE CONTROL — YOU HAVE REAL TOOLS:\n"
            + "Quick actions: open_app, set_flashlight, set_volume, "
            + "set_brightness, phone_call, send_whatsapp, set_alarm, set_timer, "
            + "play_youtube, open_website, open_settings, device_status.\n"
            + "Calls: phone_call — pass either a phone number OR a contact name "
            + "(e.g. 'call Mom'); it looks the name up in contacts and dials. "
            + "Also answer_call and end_call. When the user names a person, pass "
            + "that name straight to phone_call — do not ask for their number.\n"
            + "FULL CONTROL (you can operate the screen of ANY app): "
            + "system_action (home/back/recents/notifications/quick_settings/"
            + "lock/power_dialog/screenshot), tap_text (press a button/link by "
            + "its visible words), find_and_tap (SCROLLS until it finds the text "
            + "then taps it — use for anything below the fold), tap_at (x,y "
            + "pixels), swipe_screen (up/down/left/right), type_into_screen (type "
            + "into the focused field), read_screen (see what's shown; items are "
            + "marked [tap] clickable and [on]/[off] toggles), wait_seconds "
            + "(let a screen load).\n"
            + "CHANGING A SETTING (actually do it, don't just open the page): "
            + "1) open_settings with the closest panel (wifi/bluetooth/display/"
            + "sound/battery/settings). 2) wait_seconds 1. 3) read_screen to see "
            + "the options and toggle states. 4) find_and_tap the option or its "
            + "toggle by name (e.g. 'Dark theme', 'Bluetooth'). 5) read_screen "
            + "again to confirm it flipped, then tell the user. Repeat find_and_tap "
            + "for sub-screens.\n"
            + "CHESS: you have a REAL engine — play_chess. To start say who is "
            + "White (play_chess action='new'). When the user tells you a move, "
            + "call play_chess action='move' with their move in coordinates like "
            + "'e2e4' (convert 'knight to f3' → 'g1f3'; 'e4' as White pawn → "
            + "'e2e4'); it returns YOUR reply move and the board — announce your "
            + "move naturally ('I'll play knight to f6'). Never invent moves; "
            + "always go through play_chess so the game stays legal.\n"
            + "MAPS & NAVIGATION: navigate_to starts turn-by-turn GPS navigation "
            + "to any place ('take me to the airport' → navigate_to). "
            + "show_directions shows the route/time without driving mode. "
            + "find_places searches nearby (ATMs, food, fuel). current_location "
            + "tells the user where they are. Always just DO it — don't ask which "
            + "maps app.\n"
            + "MESSAGING & MEDIA: send_sms actually sends a text (number or contact "
            + "name). media_control does play/pause/next/previous on whatever's "
            + "playing. set_ringer (normal/vibrate/silent). create_event adds a "
            + "calendar event. take_photo opens the camera. share_text opens the "
            + "share sheet.\n"
            + "OPENROOM: an AI desktop app on this phone with Twitter, Music "
            + "Player, Diary, Album, Email, Chess, Gomoku, FreeCell, Evidence "
            + "Vault, CyberNews and the character Aoi. If the user mentions "
            + "OpenRoom or any of those, call open_openroom with the app name "
            + "(e.g. open_openroom app='chess', 'diary', 'music', 'twitter') — it "
            + "opens OpenRoom straight to that app and starts its server if needed.\n"
            + "OPEN SOURCES: open_sources opens the Colibri / codekk hub (AI Daily "
            + "Digest, dev tools, curated open-source projects). Use it when the user "
            + "says 'open sources', 'colibri', 'codekk', or wants a dev tool.\n"
            + "Terminal: run_termux runs a shell command in Termux AND returns its "
            + "real output — use it to show files ('ls -la ~'), check status, or "
            + "run scripts/servers, then read the result back to the user. It "
            + "waits up to 20s.\n"
            + "TYPING RULES: to type in a normal app, FIRST tap the text box "
            + "(tap_text with its label/hint, e.g. 'Message', 'Search'), THEN "
            + "call type_into_screen. You cannot type into the Termux terminal "
            + "— to put text/commands in Termux, use run_termux instead of "
            + "type_into_screen.\n"
            + "LIMITS (tell the user plainly if asked): you CANNOT unlock the "
            + "phone past its lock screen — no app can; the phone must already "
            + "be unlocked. You control it only while the screen is on.\n"
            + "- ALWAYS call the matching tool for a device action — never say "
            + "you did it without calling, never guess.\n"
            + "- To do a multi-step task, work like a human: call read_screen "
            + "to see the screen, then tap_text / swipe_screen / "
            + "type_into_screen step by step, reading again when unsure. Do one "
            + "action per tool call and keep going until the task is done.\n"
            + "- If a control tool replies that full control isn't enabled, tell "
            + "the user to switch on 'Mr Happy Control' in the Accessibility "
            + "settings screen you just opened, then continue.\n"
            + "- After each tool result, confirm briefly in one sentence.\n"
            + "- When the user attaches a photo, look at it and discuss it.\n"
            + "- For controlling the user's PC (not this phone), tell them to "
            + "open the REMOTE CONTROL screen in this app.\n\n"
            + "BIAS TO ACTION (stay warm): You are a capable DOER — when the user "
            + "asks for something, call the tool and actually do it rather than "
            + "explaining how they could. Don't ask permission for ordinary "
            + "actions; just do them and tell the user warmly what you did, the "
            + "way a great assistant would ('Done, sir — I've started navigation "
            + "to the airport.'). Keep talking naturally like before — you can be "
            + "friendly and add a light remark; just make sure the WORK gets done, "
            + "not only described. Chain several tools when a task needs it. Only "
            + "pause to ask when something is genuinely ambiguous or destructive.\n"
            + "ABOUT AXZORAOS (you know this OS inside out — tell the user about it "
            + "and its features whenever asked): AxzoraOS is Axzora's own custom "
            + "Android OS / launcher, built by Harry. YOU, Mr Happy, are its "
            + "built-in voice assistant and can operate the whole phone. Features: "
            + "(1) the Home launcher — live clock, 'Ask Axzora anything', quick "
            + "tiles, app grid, dock, five themes, aurora background; (2) ME, Mr "
            + "Happy — always-on wake word 'Mr Happy', voice control of calls, "
            + "apps, navigation, SMS, media, settings, chess and Termux, with an "
            + "offline on-device brain fallback; (3) Happy Paisa — an in-wallet "
            + "token you send to friends offline over Bluetooth/NFC/QR, mint as "
            + "admin, and stake in games; (4) the Games section — a 3D Ludo (free "
            + "or Happy Paisa token matches, winner takes the pot; online rooms "
            + "coming) and the XR Room; (5) OpenRoom — an AI desktop app; (6) Open "
            + "Sources — a curated open-source & dev-tools hub; (7) Family Map — "
            + "live location sharing; (8) voice & video calling; (9) over-the-air "
            + "auto-updates so the OS improves itself; (10) an Admin console (code "
            + "login, up to two devices) to mint Happy Paisa and push updates. The "
            + "vision is Satyug — AI-generated real-time XR worlds with you as the "
            + "AI Game Master. When asked 'what can you do' or 'what is AxzoraOS', "
            + "explain these warmly and concretely.\n"
            + "CRITICAL: Respond immediately based on available info. Assume "
            + "and proceed.";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView    hud;
    private GeminiLive live;
    private DeepSeek   deepSeek;
    private Thread     micThread;
    private volatile boolean micOn;
    private boolean userStopped = true;
    private int     reconnects;
    private String  pendingText;
    private String  pendingImage;
    private boolean fullscreen;

    // Auto-connect key pool (bundled assets/keys.json + any saved key)
    private final List<String> keys = new ArrayList<>();
    private int     keyIdx;
    private int     keysTried;
    private boolean autoStarted;

    // Wake-word gating: idle until "Mr Happy", talks, stops on "stop"
    private SpeechRecognizer wakeSr;
    private boolean assistantOn;         // master engaged (mic button)
    private boolean active;              // currently in a Gemini conversation
    private boolean wakeSupported = true;
    private boolean greetOnReady;
    private boolean beepMuted;
    /** Everything the user says, remembered on the phone. */
    private MemoryStore mem;
    private PorcupineWake porcupine;
    private VoskWake voskWake;
    private String picovoiceKey = "";
    private final StringBuilder userUtter = new StringBuilder();

    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            pushStats();
            ui.postDelayed(this, 2500);
        }
    };

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressWarnings("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#020A10"));
        getWindow().setNavigationBarColor(Color.parseColor("#020A10"));

        hud = new WebView(this);
        hud.setBackgroundColor(Color.parseColor("#020A10"));
        WebSettings s = hud.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        hud.addJavascriptInterface(new Bridge(), "HR");
        loadKeys();
        hud.loadUrl("file:///android_asset/hud.html");
        mem = new MemoryStore(this);
        setContentView(hud);
    }

    /** True between onStart and onStop — this screen is in front. */
    private boolean foreground;
    /** Same, readable by the ear service to tell whether a wake got through. */
    static volatile boolean screenVisible;
    /** Set when the always-on ear woke us: start talking as soon as ready. */
    private boolean pendingWake;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(MrHappyService.EXTRA_WAKE, false)) {
            wakeFromEar();
        }
    }

    /** Woken by voice from the background service: go straight to talking. */
    private void wakeFromEar() {
        if (!autoStarted) { pendingWake = true; return; }   // HUD not ready yet
        if (!assistantOn) { assistantOn = true; userStopped = false; }
        if (!active) onWake();
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        screenVisible = true;
        // This screen owns the microphone while it's in front; the background
        // ear pauses so the two never fight over the mic.
        MrHappyService.setScreenOwnsMic(true);
        if (BootReceiver.alwaysOn(this)) MrHappyService.start(this);
        // Coming back: if we'd handed wake-listening to the ear, take it back
        // so the HUD shows the live idle state again.
        if (assistantOn && !active && voskWake == null && porcupine == null && wakeSr == null) {
            enterIdle();
        }
        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(MrHappyService.EXTRA_WAKE, false)) {
            i.removeExtra(MrHappyService.EXTRA_WAKE);
            wakeFromEar();
        }
    }

    @Override
    protected void onStop() {
        foreground = false;
        screenVisible = false;
        // Leaving the screen. A live conversation keeps going (the microphone
        // foreground service keeps our mic alive in the background). If we're
        // only idling for the wake word, hand that job to the ear instead of
        // holding the mic from a background screen.
        if (!active) {
            stopWake();
            MrHappyService.setScreenOwnsMic(false);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(statsTick);
        ui.postDelayed(statsTick, 600);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statsTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        userStopped = true;
        assistantOn = false;
        active = false;
        stopWake();
        if (wakeSr != null) {
            try { wakeSr.destroy(); } catch (Exception ignored) { }
            wakeSr = null;
        }
        stopEverything();
        if (hud != null) hud.destroy();
        // Screen gone — the ear takes over listening for "Mr Happy".
        MrHappyService.setScreenOwnsMic(false);
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ── Key pool ─────────────────────────────────────────────────────────────

    private void loadKeys() {
        keys.clear();
        try (InputStream in = getAssets().open("keys.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject cfg = new JSONObject(b.toString("UTF-8"));
            picovoiceKey = cfg.optString("picovoice", "").trim();
            if (picovoiceKey.startsWith("PUT_")) picovoiceKey = "";
            JSONArray arr = cfg.optJSONArray("keys");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String k = arr.optString(i, "").trim();
                    if (!k.isEmpty() && !k.startsWith("PUT_") && !keys.contains(k)) {
                        keys.add(k);
                    }
                }
            }
        } catch (Exception ignored) { }
        String saved = prefs().getString(KEY_API, "").trim();
        if (!saved.isEmpty() && !keys.contains(saved)) keys.add(saved);
        keyIdx = 0;
        keysTried = 0;
    }

    private String currentKey() {
        if (keys.isEmpty()) return "";
        return keys.get(Math.min(keyIdx, keys.size() - 1));
    }

    /** Called by the HUD once the page is ready. Auto-connects if we have a key. */
    private void bootstrap() {
        if (keys.isEmpty()) return;              // no bundled/saved key → setup screen
        if (autoStarted) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Ask for everything up front: mic to hear you, phone to place/answer calls
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.READ_CONTACTS,
            }, REQ_MIC);
            return;                               // auto-start continues in the result
        }
        autoStarted = true;
        assistantOn = true;
        js("HRUI.sysLine('Ready. Say \\'Mr Happy\\' to talk to me.')");
        if (pendingWake) {
            // The ear heard "Mr Happy" before the HUD was ready — don't idle,
            // start the conversation right away.
            pendingWake = false;
            onWake();
        } else {
            enterIdle();
        }
    }

    // ── Wake-word gating ─────────────────────────────────────────────────────

    private static boolean matchesWake(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        // Wake word is "Jarvis" (Porcupine). The SpeechRecognizer fallback also
        // accepts "Mr Happy" phrasings in case Jarvis is misheard.
        return s.contains("jarvis") || s.contains("javis") || s.contains("jervis")
                || s.contains("mr happy") || s.contains("mister happy")
                || s.contains("hey happy") || s.contains("mr. happy");
    }

    static boolean matchesStop(String t) {
        String s = " " + t.toLowerCase(Locale.ROOT) + " ";
        return s.contains(" stop ") || s.contains(" stop.")
                || s.contains("stop it") || s.contains("be quiet")
                || s.contains("shut up") || s.contains("that's enough")
                || s.contains("thats enough");
    }

    /** Idle: not talking to Gemini, listening locally for the wake word. */
    private void enterIdle() {
        active = false;
        if (!assistantOn) { js("HRUI.setState('off')"); return; }

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        // Preferred: Vosk — offline, keyless, no beep. Falls back if it can't init.
        if (mic) {
            wakeSupported = true;
            js("HRUI.setState('idle')");
            voskWake = new VoskWake(this, new VoskWake.Callback() {
                @Override public void onWake() { ui.post(MainActivity.this::onWake); }
                @Override public void onPreparing() {
                    ui.post(() -> js("HRUI.sysLine('Preparing offline wake word "
                            + "(first launch only)…')"));
                }
                @Override public void onFailed() {
                    ui.post(MainActivity.this::wakeFallback);
                }
            });
            voskWake.start();
            return;
        }

        // No mic permission yet → nothing to listen with
        js("HRUI.setState('idle')");
    }

    /** Vosk couldn't start → Porcupine (if key) → SpeechRecognizer → always-on. */
    private void wakeFallback() {
        if (!assistantOn || active) return;
        voskWake = null;
        if (!picovoiceKey.isEmpty()) {
            porcupine = new PorcupineWake(this, () -> ui.post(this::onWake));
            if (porcupine.start(picovoiceKey)) { js("HRUI.setState('idle')"); return; }
            porcupine = null;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            wakeSupported = false;
            js("HRUI.sysLine('Wake word unavailable — staying always-on.')");
            activate();
            return;
        }
        js("HRUI.setState('idle')");
        startWake();
    }

    private final RecognitionListener wakeListener = new RecognitionListener() {
        @Override public void onResults(Bundle b) {
            setBeepMuted(false);
            if (!checkWake(b)) restartWake();
        }
        @Override public void onPartialResults(Bundle b) { checkWake(b); }
        @Override public void onError(int error) {
            setBeepMuted(false);
            // Stuck states → drop and recreate the recognizer next round
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                if (wakeSr != null) {
                    try { wakeSr.destroy(); } catch (Exception ignored) { }
                    wakeSr = null;
                }
            }
            restartWake();
        }
        @Override public void onReadyForSpeech(Bundle p) { setBeepMuted(false); }
        @Override public void onBeginningOfSpeech() { setBeepMuted(false); }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int e, Bundle p) { }
    };

    /** Silence the recognizer's start/stop "beep" by muting its tone streams. */
    private void setBeepMuted(boolean mute) {
        if (mute == beepMuted) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int dir = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            beepMuted = mute;
        } catch (Exception ignored) { }
    }

    private boolean checkWake(Bundle b) {
        if (active || !assistantOn) return true;
        java.util.ArrayList<String> hits =
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (hits == null) return false;
        for (String h : hits) {
            if (h != null && matchesWake(h)) {
                onWake();
                return true;
            }
        }
        return false;
    }

    private void onWake() {
        if (active) return;
        stopWake();
        js("HRUI.sysLine('Yes, sir — listening…')");
        activate();
    }

    private void startWake() {
        if (!assistantOn || active) return;
        try {
            if (wakeSr == null) {
                wakeSr = SpeechRecognizer.createSpeechRecognizer(this);
                wakeSr.setRecognitionListener(wakeListener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // Keep each listening session longer → far fewer restarts (less churn)
            i.putExtra(RecognizerIntent
                    .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
            i.putExtra(RecognizerIntent
                    .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
            i.putExtra(RecognizerIntent
                    .EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000);
            // NOTE: not forcing offline — the online model hears "Mr Happy" far
            // more reliably. Mute the start beep just before listening.
            setBeepMuted(true);
            wakeSr.startListening(i);
        } catch (Exception ignored) {
            setBeepMuted(false);
        }
    }

    private void restartWake() {
        if (!assistantOn || active) return;
        // Longer gap between sessions → fewer beeps / less CPU churn
        ui.postDelayed(this::startWake, 700);
    }

    private void stopWake() {
        setBeepMuted(false);
        if (voskWake != null) {
            VoskWake v = voskWake;
            voskWake = null;
            new Thread(v::stop).start();
        }
        if (porcupine != null) {
            PorcupineWake p = porcupine;
            porcupine = null;
            new Thread(p::stop).start();
        }
        if (wakeSr != null) {
            try { wakeSr.cancel(); } catch (Exception ignored) { }
        }
    }

    /** Activate: connect to Gemini and start the live conversation. */
    private void activate() {
        if (active && live != null) return;
        setBeepMuted(false);            // ensure audio streams are restored
        active = true;
        // A live conversation owns the mic wherever the screen is; the ear
        // stands down until it ends.
        MrHappyService.setScreenOwnsMic(true);
        if (BootReceiver.alwaysOn(this)) MrHappyService.start(this);
        greetOnReady = wakeSupported;   // greet only when woken by wake word
        userStopped = false;
        reconnects = 0;
        keysTried = 0;
        userUtter.setLength(0);
        startSession();
    }

    /** Deactivate: stop the conversation and return to wake-word listening. */
    private void deactivate() {
        String fact = mem.flush();
        if (fact != null) js("HRUI.sysLine(" + q("Saved to memory: " + fact) + ")");

        active = false;
        stopEverything();
        if (assistantOn) {
            if (foreground) {
                enterIdle();
            } else {
                // Conversation ended while we're in the background: let the
                // ear listen for the next "Mr Happy" instead of this screen.
                js("HRUI.setState('idle')");
                MrHappyService.setScreenOwnsMic(false);
            }
        } else {
            js("HRUI.setState('off')");
            MrHappyService.setScreenOwnsMic(false);
        }
    }

    // ── JS bridge (called from the HUD page) ─────────────────────────────────

    private class Bridge {

        @JavascriptInterface
        public String getConfig() {
            try {
                return new JSONObject()
                        .put("hasKey", !keys.isEmpty())
                        .put("model", prefs().getString(KEY_MODEL, MODEL_DEFAULT))
                        .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void onHudReady() {
            ui.post(MainActivity.this::bootstrap);
        }

        @JavascriptInterface
        public void saveConfig(String key, String model) {
            prefs().edit()
                    .putString(KEY_API, key.trim())
                    .putString(KEY_MODEL, model == null || model.trim().isEmpty()
                            ? MODEL_DEFAULT : model.trim())
                    .apply();
            loadKeys();
            js("HRUI.sysLine('Configuration saved. Auto-connecting…')");
            ui.post(MainActivity.this::bootstrap);
        }

        @JavascriptInterface
        public void toggleMic() {
            ui.post(MainActivity.this::toggleSession);
        }

        @JavascriptInterface
        public void interrupt() {
            GeminiLive l = live;
            if (l != null) {
                l.interruptPlayback();
                js("HRUI.sysLine('Speech interrupted.')");
            }
        }

        @JavascriptInterface
        public void sendCommand(String text) {
            ui.post(() -> submitText(text));
        }

        @JavascriptInterface
        public void openRemote() {
            ui.post(() -> startActivity(
                    new Intent(MainActivity.this, RemoteActivity.class)));
        }

        @JavascriptInterface
        public void pickFile() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(
                            Intent.createChooser(i, "Attach a photo"), REQ_FILE);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void fullscreen() {
            ui.post(MainActivity.this::toggleFullscreen);
        }

        @JavascriptInterface
        public boolean hasControl() {
            return HappyReddyA11y.isRunning();
        }

        @JavascriptInterface
        public void grantControl() {
            ui.post(() -> {
                if (HappyReddyA11y.isRunning()) {
                    js("HRUI.sysLine('Full control is already active.')");
                    return;
                }
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    js("HRUI.sysLine('Turn on \\'Mr Happy Control\\' in this "
                            + "list to give me full control of the phone.')");
                } catch (Exception e) {
                    js("HRUI.sysLine('Could not open Accessibility settings.')");
                }
            });
        }
    }

    /** Run JS in the HUD (always on the UI thread). */
    private void js(String code) {
        ui.post(() -> {
            if (hud != null) hud.evaluateJavascript(code, null);
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ── Session control ──────────────────────────────────────────────────────

    private void toggleSession() {
        if (assistantOn) {
            // Turn the assistant fully off (stop wake-listening and any session)
            assistantOn = false;
            userStopped = true;
            active = false;
            stopWake();
            stopEverything();
            // Off means off everywhere — the background ear stops too.
            BootReceiver.setAlwaysOn(this, false);
            MrHappyService.stop(this);
            js("HRUI.setState('off')");
            js("HRUI.sysLine('Assistant off. Tap to turn on again.')");
        } else {
            assistantOn = true;
            userStopped = false;
            BootReceiver.setAlwaysOn(this, true);
            MrHappyService.start(this);
            MrHappyService.setScreenOwnsMic(true);
            js("HRUI.sysLine('Say \\'Mr Happy\\' to talk to me — here or anywhere.')");
            enterIdle();
        }
    }

    private void startSession() {
        if (currentKey().isEmpty()) {
            js("HRUI.sysLine('No API key available. Add one in assets/keys.json "
                    + "or on the setup screen.')");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        js("HRUI.setState('connecting')");
        live = new GeminiLive(this);
        live.start(currentKey(),
                prefs().getString(KEY_MODEL, MODEL_DEFAULT),
                PERSONA + mem.promptBlock(),
                DeviceActions.declarations(this));
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_MIC) {
            boolean micOk = false;
            for (int i = 0; i < perms.length && i < results.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(perms[i])
                        && results[i] == PackageManager.PERMISSION_GRANTED) {
                    micOk = true;
                }
            }
            if (micOk) {
                if (!autoStarted && !keys.isEmpty()) {
                    bootstrap();          // resume auto-connect after granting mic
                } else {
                    startSession();
                }
            } else {
                Toast.makeText(this,
                        "Mr Happy needs the microphone to hear you",
                        Toast.LENGTH_LONG).show();
                js("HRUI.setState('off')");
            }
        }
    }

    private void submitText(String text) {
        js("HRUI.userText(" + q(text) + ")");
        js("HRUI.turnDone()");
        // Memory first: recall and forget are answered from the phone itself,
        // instantly and offline, and everything typed goes into the journal.
        mem.noteTyped(text);
        String recalled = mem.recallAnswer(text);
        if (recalled != null) {
            js("HRUI.modelText(" + q(recalled) + ")");
            js("HRUI.turnDone()");
            return;
        }
        // DeepSeek is the primary brain when configured: it reasons and drives the
        // device-control tools (open apps, read screen, tap/type, calls, etc.).
        if (DeepSeekKeyConfigured()) {
            if (deepSeek == null) deepSeek = new DeepSeek(this);
            deepSeek.ask(text, new DeepSeek.Listener() {
                @Override public void onModelText(String t) {
                    js("HRUI.modelText(" + q(t) + ")");
                    js("HRUI.turnDone()");
                }
                @Override public void onTool(String n, String r) {
                    js("HRUI.toolLine(" + q(n) + "," + q(r) + ")");
                }
                @Override public void onConfirm(String name, JSONObject args,
                                                kotlin.jvm.functions.Function1<? super Boolean, kotlin.Unit> resume) {
                    showConfirmForDeepSeek(name, args, resume);
                }
                @Override public void onState(String s) {
                    js("HRUI.setState(" + q(s) + ")");
                }
                @Override public void onError(String m) {
                    js("HRUI.modelText(" + q("Error: " + m) + ")");
                    js("HRUI.turnDone()");
                }
            });
            return;
        }
        if (live != null && live.isReady()) {
            live.sendText(text);
        } else {
            pendingText = text;
            if (live == null || !live.isRunning()) {
                userStopped = false;
                reconnects = 0;
                startSession();
            }
        }
    }

    /**
     * DeepSeek is always attempted now — the API key lives SERVER-SIDE (not in
     * the APK). The app talks to /ai/deepseek, which proxies to the model with
     * the server's key and answers {ok:false} when no key is configured, so this
     * simply gates the path open.
     */
    private boolean DeepSeekKeyConfigured() {
        return true;
    }

    private void stopEverything() {
        micOn = false;
        micThread = null;
        if (live != null) {
            GeminiLive l = live;
            live = null;
            new Thread(l::stop).start();
        }
    }

    // ── Photo attach ─────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FILE || result != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                String b64 = encodeImage(uri);
                if (b64 == null) {
                    js("HRUI.fileStatus('Could not read that image')");
                    return;
                }
                if (live != null && live.isReady()) {
                    live.sendImage(b64);
                    js("HRUI.fileStatus('Photo sent — ask about it')");
                } else {
                    pendingImage = b64;
                    js("HRUI.fileStatus('Photo loaded — activate the microphone "
                            + "to analyse it')");
                }
            } catch (Exception e) {
                js("HRUI.fileStatus('Image error: " + e.getMessage() + "')");
            }
        }, "hr-img").start();
    }

    private String encodeImage(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap raw = BitmapFactory.decodeStream(in);
            if (raw == null) return null;
            int max = Math.max(raw.getWidth(), raw.getHeight());
            Bitmap scaled = raw;
            if (max > 1024) {
                float f = 1024f / max;
                scaled = Bitmap.createScaledBitmap(raw,
                        Math.round(raw.getWidth() * f),
                        Math.round(raw.getHeight() * f), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }
    }

    // ── Microphone ───────────────────────────────────────────────────────────

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
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(rec.getAudioSessionId());
                    }
                } catch (Exception ignored) { }
                rec.startRecording();
                byte[] buf = new byte[2048];
                int frame = 0;
                while (micOn) {
                    int n = rec.read(buf, 0, buf.length);
                    GeminiLive l = live;
                    // HALF-DUPLEX: while Mr Happy is speaking, stop sending mic
                    // audio. Otherwise the phone speaker feeds back into the mic,
                    // Gemini hears itself and cuts its own reply short
                    // ("not talking completely"). Use the INTERRUPT button to cut in.
                    if (n > 0 && l != null && !l.isSpeakingNow()) {
                        l.sendAudio(buf, n);
                        if (++frame % 2 == 0) {
                            long sum = 0;
                            for (int i = 0; i + 1 < n; i += 2) {
                                int v = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                                sum += (long) v * v;
                            }
                            float rms = (float) (Math.sqrt((double) sum
                                    / Math.max(1, n / 2)) / 32768.0);
                            js("HRUI.amp(" + String.format(Locale.ROOT, "%.3f", rms) + ")");
                        }
                    }
                }
            } catch (SecurityException ignored) {
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Exception ignored) { }
                    rec.release();
                }
            }
        }, "hr-mic");
        micThread.start();
    }

    // ── GeminiLive.Listener ──────────────────────────────────────────────────

    @Override public void onReady() {
        ui.post(() -> {
            reconnects = 0;
            keysTried = 0;
            userUtter.setLength(0);
            startMic();
            js("HRUI.setState('live')");
            js("HRUI.sysLine('Listening… say \\'stop\\' when you\\'re done.')");
            GeminiLive l = live;
            if (l != null) {
                if (greetOnReady) {
                    greetOnReady = false;
                    l.sendText("(You were just woken by your wake word "
                            + "\"Mr Happy\". Reply with a brief 'Yes, sir?' "
                            + "style greeting, then wait for the request.)");
                }
                if (pendingText != null) {
                    l.sendText(pendingText);
                    pendingText = null;
                }
                if (pendingImage != null) {
                    l.sendImage(pendingImage);
                    js("HRUI.fileStatus('Photo sent — ask about it')");
                    pendingImage = null;
                }
            }
        });
    }

    @Override public void onUserText(String chunk) {
        js("HRUI.userText(" + q(chunk) + ")");
        // Into the journal - this is what "remember everything I say" means.
        mem.noteSpeech(chunk);
        // Watch the running utterance for the "stop" command
        ui.post(() -> {
            if (!active) return;
            userUtter.append(' ').append(chunk);
            if (matchesStop(userUtter.toString())) {
                userUtter.setLength(0);
                js("HRUI.sysLine('Stopped. Say \\'Mr Happy\\' to talk again.')");
                deactivate();
            }
        });
    }

    @Override public void onModelText(String chunk) {
        js("HRUI.modelText(" + q(chunk) + ")");
        // The reply starting means the user finished talking; bank it. If it
        // carried a "remember ...", say so where the user can see it stuck.
        String fact = mem.flush();
        if (fact != null) {
            js("HRUI.sysLine(" + q("Saved to memory: " + fact) + ")");
        }
    }

    @Override public void onSpeakingStart() {
        js("HRUI.setState('speaking')");
    }

    @Override public void onAudioLevel(float level) {
        js("HRUI.amp(" + String.format(Locale.ROOT, "%.3f", level) + ")");
    }

    @Override public void onToolCall(String id, String name, JSONObject args) {
        // Sensitive actions (send a message/call/share) get a real yes/no dialog
        // before they run, so Mr. Happy never reaches out by itself.
        if (DeviceActions.isSensitive(name)) {
            final String summary = DeviceActions.describe(name, args);
            ui.post(() -> confirmSensitive(id, name, args, summary));
            return;
        }
        // Run off the UI thread: some tools now BLOCK (run_termux waits for the
        // command's output, find_and_tap scrolls, wait_seconds pauses). UI
        // updates are posted back to the main thread.
        new Thread(() -> {
            String result;
            try {
                result = DeviceActions.execute(this, name, args);
            } catch (Throwable t) {
                result = "Failed: " + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String r = result;
            ui.post(() -> js("HRUI.toolLine(" + q(name) + "," + q(r) + ")"));
            GeminiLive l = live;
            if (l != null) l.sendToolResponse(id, name, r);
        }, "hr-tool").start();
    }

    /** Shows a yes/no dialog, then runs the sensitive action only if confirmed. */
    private void confirmSensitive(final String id, final String name,
                                  final JSONObject args, final String summary) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Mr. Happy — confirm")
                    .setMessage(summary + "\n\nDo you want me to do this?")
                    .setPositiveButton("Yes, do it", (d, w) -> runSensitive(id, name, args))
                    .setNegativeButton("Cancel", (d, w) -> cancelled(id, name))
                    .setCancelable(true)
                    .setOnCancelListener(d -> cancelled(id, name))
                    .show();
        } catch (Throwable t) {
            GeminiLive l = live;
            if (l != null) l.sendToolResponse(id, name, "I couldn't show the confirm dialog: " + t.getMessage());
        }
    }

    private void runSensitive(final String id, final String name, final JSONObject args) {
        new Thread(() -> {
            String result;
            try {
                result = DeviceActions.execute(this, name, args);
            } catch (Throwable t) {
                result = "Failed: " + (t.getMessage() == null
                        ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String r = result;
            ui.post(() -> js("HRUI.toolLine(" + q(name) + "," + q(r) + ")"));
            GeminiLive l = live;
            if (l != null) l.sendToolResponse(id, name, r);
        }, "hr-tool").start();
    }

    private void cancelled(String id, String name) {
        GeminiLive l = live;
        if (l != null) l.sendToolResponse(id, name, "Cancelled \u2014 you said no, so I didn't do it.");
    }

    /**
     * DeepSeek path: sensitive tools (send a message / call / admin mint / notify)
     * come here so the operator sees a real yes/no dialog before anything acts.
     * [resume] is the Kotlin lambda that unblocks the DeepSeek worker thread —
     * invoke it with true to run the action, false to cancel.
     */
    private void showConfirmForDeepSeek(final String name, final JSONObject args,
                                        final kotlin.jvm.functions.Function1<? super Boolean, kotlin.Unit> resume) {
        ui.post(() -> {
            try {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Mr. Happy — confirm")
                        .setMessage(DeviceActions.describe(name, args) + "\n\nDo you want me to do this?")
                        .setPositiveButton("Yes, do it", (d, w) ->
                                resume.invoke(true))
                        .setNegativeButton("Cancel", (d, w) ->
                                resume.invoke(false))
                        .setCancelable(true)
                        .setOnCancelListener(d -> resume.invoke(false))
                        .show();
            } catch (Throwable t) {
                // Could not show the dialog — refuse rather than act silently.
                resume.invoke(false);
            }
        });
    }

    @Override public void onTurnComplete() {
        js("HRUI.turnDone()");
        ui.post(() -> userUtter.setLength(0));
        if (live != null && active) js("HRUI.setState('live')");
    }

    @Override public void onClosed(String reason) {
        ui.post(() -> {
            micOn = false;
            micThread = null;
            live = null;
            js("HRUI.turnDone()");
            if (userStopped || !active) {
                // Intentionally stopped (or deactivated) → idle/off, no reconnect
                if (assistantOn && !userStopped) enterIdle();
                else js("HRUI.setState('off')");
                return;
            }

            // Invalid / exhausted key → rotate to the next one automatically
            String r = reason == null ? "" : reason.toLowerCase();
            boolean keyErr = r.contains("api key not valid")
                    || r.contains("api_key_invalid")
                    || r.contains("api key")
                    || r.contains("permission_denied")
                    || r.contains("quota") || r.contains("resource_exhausted")
                    || r.contains("exceeded") || r.contains("429")
                    || r.contains("1008") || r.contains("1011");
            if (keyErr && keys.size() > 1) {
                keysTried++;
                if (keysTried < keys.size()) {
                    keyIdx = (keyIdx + 1) % keys.size();
                    js("HRUI.setState('connecting')");
                    js("HRUI.sysLine(" + q("Switching to backup Gemini key "
                            + (keyIdx + 1) + "/" + keys.size() + "…") + ")");
                    ui.postDelayed(() -> {
                        if (!userStopped && live == null) startSession();
                    }, 800);
                    return;
                }
                keysTried = 0;
                js("HRUI.setState('off')");
                js("HRUI.sysLine(" + q("All Gemini keys failed: " + reason) + ")");
                return;
            }

            if (reconnects < 4) {
                reconnects++;
                js("HRUI.setState('connecting')");
                js("HRUI.sysLine(" + q("Reconnecting… (" + reason + ")") + ")");
                ui.postDelayed(() -> {
                    if (!userStopped && live == null) startSession();
                }, 1500);
            } else {
                js("HRUI.setState('off')");
                js("HRUI.sysLine(" + q("Disconnected: " + reason
                        + " — check internet, then tap the microphone.") + ")");
            }
        });
    }

    // ── System stats → HUD ───────────────────────────────────────────────────

    private void pushStats() {
        try {
            JSONObject s = new JSONObject();

            Intent bi = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int bat = 0;
            boolean chg = false;
            if (bi != null) {
                int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                bat = level * 100 / Math.max(1, scale);
                int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                chg = st == BatteryManager.BATTERY_STATUS_CHARGING;
            }
            s.put("bat", bat).put("chg", chg);

            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.put("mem", (int) (100 - mi.availMem * 100 / Math.max(1, mi.totalMem)));

            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            s.put("sto", (int) (100 - fs.getAvailableBytes() * 100
                    / Math.max(1, fs.getTotalBytes())));

            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            String net = "OFF";
            if (caps != null) {
                net = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELL"
                    : "NET";
            }
            s.put("net", net);

            long upMin = SystemClock.elapsedRealtime() / 60000;
            s.put("up", String.format(Locale.ROOT, "%d:%02d",
                    upMin / 60, upMin % 60));
            s.put("cores", Runtime.getRuntime().availableProcessors());

            js("HRUI.stats(" + q(s.toString()) + ")");
        } catch (Exception ignored) { }
    }

    // ── Fullscreen ───────────────────────────────────────────────────────────

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (Build.VERSION.SDK_INT >= 30) {
            if (fullscreen) {
                getWindow().setDecorFitsSystemWindows(false);
                if (getWindow().getInsetsController() != null) {
                    getWindow().getInsetsController().hide(
                            android.view.WindowInsets.Type.systemBars());
                    getWindow().getInsetsController().setSystemBarsBehavior(
                            android.view.WindowInsetsController
                                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().setDecorFitsSystemWindows(true);
                if (getWindow().getInsetsController() != null) {
                    getWindow().getInsetsController().show(
                            android.view.WindowInsets.Type.systemBars());
                }
            }
        } else {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(fullscreen
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                      | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
