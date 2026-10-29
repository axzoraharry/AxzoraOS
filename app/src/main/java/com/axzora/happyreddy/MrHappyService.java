package com.axzora.happyreddy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mr Happy's always-on ear.
 *
 * Android cuts microphone access to anything in the background unless a
 * foreground service of type "microphone" is running. Without this, Mr Happy
 * went deaf the moment the user left the screen and died when the app was
 * swiped away. This service fixes both:
 *
 *  - It keeps a microphone foreground service alive (so the live conversation
 *    in the Mr Happy screen keeps its mic even when the user switches apps).
 *  - While the Mr Happy screen is NOT open, it runs the offline wake-word
 *    listener itself. Hearing "Mr Happy" brings the assistant up, already
 *    listening, from anywhere — home screen, another app, or after the app
 *    was closed.
 *
 * The launcher starts it whenever the always-on setting is enabled, and it
 * restarts after reboot, so the user never has to "open Mr Happy" to be heard.
 */
public class MrHappyService extends Service {

    private static final String CHANNEL = "mr_happy_ear";
    private static final int NOTIF_ID = 4301;

    static final String ACTION_START = "com.axzora.happyreddy.EAR_START";
    static final String ACTION_STOP  = "com.axzora.happyreddy.EAR_STOP";
    static final String ACTION_TALK  = "com.axzora.happyreddy.EAR_TALK";
    /** Extra on the Mr Happy screen's intent: woken by voice — start talking. */
    static final String EXTRA_WAKE   = "axzora.mrhappy.wake";

    private static volatile MrHappyService instance;
    /** True while the Mr Happy screen is in front and owns the microphone. */
    private static volatile boolean screenOwnsMic;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VoskWake wake;
    private boolean listening;
    /** The conversation running with no screen, if any. */
    private BackgroundSession session;
    private View pill;
    private TextView pillText;

    // ── Public control ───────────────────────────────────────────────────────

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MrHappyService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, MrHappyService.class)); } catch (Exception ignored) { }
    }

    public static boolean isRunning() { return instance != null; }

    /** Restricted mode (on by default) blocks Termux shell, SMS and screen-reading tools. */
    public static boolean isRestricted(Context ctx) { return DeviceActions.isRestricted(ctx); }

    /** Lets the Settings screen switch restricted mode. */
    public static void setRestricted(Context ctx, boolean on) { DeviceActions.setRestricted(ctx, on); }

    /**
     * The Mr Happy screen calls this when it comes to the front (it runs its
     * own wake listener / live mic there) and again when it leaves, so only
     * one of us holds the microphone at a time.
     */
    static void setScreenOwnsMic(boolean owns) {
        screenOwnsMic = owns;
        MrHappyService s = instance;
        if (s == null) return;
        s.ui.post(() -> {
            if (owns) {
                // The HUD screen is in front and runs its own session; a
                // background conversation hands over rather than competing.
                if (s.session != null) { BackgroundSession b = s.session; s.session = null; b.end(); }
                s.stopListening();
            } else if (s.session == null) {
                s.startListening();
            }
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification(false),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification(false),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification(false));
            }
        } catch (Exception e) {
            // Mic-type FGS refused (no mic permission yet) — still stay alive
            // as a plain foreground service so the notification explains.
            try { startForeground(NOTIF_ID, notification(false)); } catch (Exception ignored) { }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TALK.equals(action)) {
            ui.post(this::talkNow);
            return START_STICKY;
        }
        if (!screenOwnsMic) startListening();
        return START_STICKY;          // come back if the system reclaims us
    }

    @Override
    public void onDestroy() {
        if (session != null) { BackgroundSession b = session; session = null; b.end(); }
        hidePill();
        stopListening();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Wake word ────────────────────────────────────────────────────────────

    /** Vosk's native engine is built for ARM; on x86 emulators it dies with
     *  SIGILL, which would take the whole launcher down. Real phones are ARM. */
    private static boolean wakeEngineSupported() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 && abis[0].startsWith("arm");
    }

    private void startListening() {
        if (listening || screenOwnsMic) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED || !wakeEngineSupported()) {
            update(false);
            return;
        }
        listening = true;
        update(true);
        wake = new VoskWake(this, new VoskWake.Callback() {
            @Override public void onWake() {
                ui.post(() -> {
                    listening = false;
                    wake = null;
                    talkNow();
                });
            }
            @Override public void onPreparing() { }
            @Override public void onFailed() {
                ui.post(() -> {
                    listening = false;
                    wake = null;
                    update(false);
                    // Model failed (e.g. unsupported CPU) — retry later rather
                    // than spinning.
                    ui.postDelayed(MrHappyService.this::startListening, 60_000);
                });
            }
        });
        wake.start();
    }

    private void stopListening() {
        listening = false;
        VoskWake w = wake;
        wake = null;
        if (w != null) new Thread(w::stop, "hr-ear-stop").start();
    }

    /**
     * He heard his name: answer right here, right now. No screen takeover —
     * the conversation runs inside this service (mic, Gemini, tools), with a
     * small floating pill showing his state. Tap the pill for the full HUD.
     */
    private void talkNow() {
        if (screenOwnsMic) { openAssistant(true); return; }   // HUD is up: let it talk
        if (session != null && session.isRunning()) return;
        stopListening();
        session = new BackgroundSession(this, new BackgroundSession.Host() {
            @Override public void onState(String state) {
                switch (state) {
                    case "connecting": showPill("Mr Happy · one moment…", 0xFFF0A73C); break;
                    case "live":       showPill("Mr Happy · listening", 0xFF4CD964); break;
                    case "speaking":   showPill("Mr Happy · speaking", 0xFF4F86FF); break;
                    default:           hidePill(); break;
                }
                update(true);
            }
            @Override public void onEnded() {
                session = null;
                hidePill();
                // Back to waiting for his name.
                if (!screenOwnsMic) startListening();
            }
        });
        session.start();
    }

    // ── Floating status pill (only if "display over other apps" is allowed) ──

    private void showPill(String text, int dot) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (pill == null) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dp(14);
                box.setPadding(pad, dp(9), pad, dp(9));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xE6101722);
                bg.setCornerRadius(dp(22));
                bg.setStroke(dp(1), 0x55FFFFFF);
                box.setBackground(bg);

                View dotV = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(dot);
                dotV.setBackground(d);
                dotV.setTag("dot");
                box.addView(dotV, new LinearLayout.LayoutParams(dp(9), dp(9)));

                pillText = new TextView(this);
                pillText.setTextColor(Color.WHITE);
                pillText.setTextSize(13.5f);
                pillText.setPadding(dp(9), 0, 0, 0);
                box.addView(pillText);

                TextView x = new TextView(this);
                x.setText("  ×");
                x.setTextColor(0xAAFFFFFF);
                x.setTextSize(15f);
                x.setOnClickListener(v -> { if (session != null) session.end(); });
                box.addView(x);

                box.setOnClickListener(v -> openAssistant(false));   // user tap → full HUD

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(56);
                wm.addView(box, lp);
                pill = box;
            }
            View dotV = pill.findViewWithTag("dot");
            if (dotV != null && dotV.getBackground() instanceof GradientDrawable)
                ((GradientDrawable) dotV.getBackground()).setColor(dot);
            pillText.setText(text);
        } catch (Exception ignored) { }
    }

    private void hidePill() {
        if (pill == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pill);
        } catch (Exception ignored) { }
        pill = null;
        pillText = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Bring the Mr Happy screen up, already activated. */
    private void openAssistant(boolean woken) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_WAKE, woken);
        try { startActivity(i); } catch (Exception ignored) { }
        // Android drops a blocked background start SILENTLY (no exception), so
        // verify: if his screen isn't up shortly, raise a heads-up / full-screen
        // notification that brings him up with one tap. (With the bubble overlay
        // or the accessibility service enabled, the direct start is allowed and
        // this never fires.)
        ui.postDelayed(() -> { if (!MainActivity.screenVisible) nudge(i); }, 1500);
    }

    private static final String WAKE_CHANNEL = "mr_happy_wake";

    private void nudge(Intent open) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(WAKE_CHANNEL,
                    "Mr Happy — wake", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Shown when Mr Happy hears his name");
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 7, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this,
                Build.VERSION.SDK_INT >= 26 ? WAKE_CHANNEL : "")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Mr Happy: I'm here, sir")
                .setContentText("Tap to talk")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)     // opens him outright when the screen is off/locked
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setTimeoutAfter(20_000);
        nm.notify(NOTIF_ID + 1, b.build());
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Mr Happy",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Mr Happy listening for his name in the background");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void update(boolean active) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try { nm.notify(NOTIF_ID, notification(active)); } catch (Exception ignored) { }
    }

    private Notification notification(boolean active) {
        Intent talk = new Intent(this, MrHappyService.class).setAction(ACTION_TALK);
        Intent off = new Intent(this, MrHappyService.class).setAction(ACTION_STOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent talkPi = PendingIntent.getService(this, 1, talk, f);
        PendingIntent offPi  = PendingIntent.getService(this, 2, off, f);
        PendingIntent openPi = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), f);

        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        String text = !micOk ? "Needs microphone permission — tap to grant"
                : (session != null && session.isRunning()) ? "In conversation — say “stop” to end"
                : active ? "Say “Mr Happy” anytime" : "Standing by";

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Mr Happy is listening")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Talk now", talkPi).build())
                .addAction(new Notification.Action.Builder(null, "Turn off", offPi).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
