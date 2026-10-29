package com.axzora.happyreddy;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.CalendarContract;
import android.telephony.SmsManager;
import android.view.KeyEvent;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.provider.Settings;

import com.axzora.os.assistant.Traffic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mr Happy's hands on the phone — executes Gemini tool calls as real
 * Android actions. Every method returns a short result string that is sent
 * back to the model so it can confirm out loud what happened.
 */
final class DeviceActions {

    private DeviceActions() { }

    /** Same prefs file as the ear setting, so one place holds Mr Happy's options. */
    private static final String PREFS = "mrhappy_ear";
    private static final String KEY_TOOLS_RESTRICTED = "tools_restricted";

    /** Tools that are too invasive for a cloud model to trigger by default.
     *  Screen reading is NOT here: it's read-only and already gated behind the
     *  Accessibility permission the user grants explicitly, so double-gating it
     *  under Restricted mode blocked the main "what's on my screen" feature. */
    private static final Set<String> RESTRICTED_TOOLS =
            new HashSet<>(Arrays.asList("run_termux", "send_sms"));

    /**
     * Restricted mode (on by default) keeps the most invasive tools out of
     * Mr Happy's reach unless the user explicitly turns it off in Settings:
     * shell commands in Termux, sending SMS, and reading the screen.
     */
    static boolean isRestricted(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TOOLS_RESTRICTED, false);
    }

    static void setRestricted(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TOOLS_RESTRICTED, on).apply();
    }

    /** Actions that reach out to people or spend money, so need a confirmation first (§15). */
    private static final Set<String> SENSITIVE_TOOLS =
            new HashSet<>(Arrays.asList("send_whatsapp", "send_sms", "phone_call", "share_text",
                    // Admin actions alter the family ledger / push global notifications, so the
                    // operator must confirm even when the AI is the one requesting them.
                    "admin_notify", "admin_mint"));

    static boolean isSensitive(String name) {
        return SENSITIVE_TOOLS.contains(name);
    }

    /** A short, human-readable "what will happen" line for the confirm dialog. */
    static String describe(String name, JSONObject args) {
        try {
            switch (name) {
                case "send_whatsapp":
                    return "Send a WhatsApp message to " + args.optString("recipient", "?")
                            + ": \"" + args.optString("message", "") + "\"";
                case "send_sms":
                    return "Send an SMS to " + args.optString("to", "?")
                            + ": \"" + args.optString("message", "") + "\"";
                case "phone_call":
                    return "Call " + args.optString("number", "?") + "?";
                case "share_text":
                    return "Share text: \"" + args.optString("text", "") + "\"";
                case "admin_notify":
                    return "Send an admin notification to " + (args.optString("target", "ALL")
                            .isBlank() ? "everyone" : args.optString("target", "ALL"))
                            + ": \"" + args.optString("text", "") + "\"";
                case "admin_mint":
                    return "Mint " + args.optDouble("hp", 0) + " Happy Paisa to "
                            + args.optString("uid", "?") + "?";
                default:
                    return "Run " + name;
            }
        } catch (Throwable t) {
            return "Run " + name;
        }
    }

    /**
     * Result for a sensitive action requested when there's no screen to ask on
     * (e.g. the background voice session). Refuses to act silently — the user
     * must open the Mr. Happy screen to get the confirm dialog.
     */
    static String requireConfirmation(String name, JSONObject args) {
        return "That needs your confirmation, so I didn't do it. Open the Mr. Happy " +
                "screen and ask again and I'll show a confirm prompt. (" + describe(name, args) + ")";
    }

    /** Tool declarations sent to Gemini Live in the setup message. */
    static JSONArray declarations(Context ctx) {
        try {
            JSONArray a = new JSONArray();
            a.put(decl("open_app",
                    "Opens any installed app on this phone by name. Always call this when "
                    + "the user asks to open, launch or start an app.",
                    props("app_name", "STRING",
                            "App name, e.g. 'WhatsApp', 'YouTube', 'Camera'"),
                    "app_name"));
            a.put(decl("set_flashlight",
                    "Turns the phone's flashlight/torch on or off.",
                    props("on", "BOOLEAN", "true = on, false = off"), "on"));
            a.put(decl("set_volume",
                    "Sets media volume to a percent (0-100), or adjusts it with an action.",
                    props2("level", "NUMBER", "Volume percent 0-100 (optional)",
                           "action", "STRING", "'up', 'down' or 'mute' (optional)")));
            a.put(decl("set_brightness",
                    "Sets screen brightness percent (0-100).",
                    props("level", "NUMBER", "Brightness percent 0-100"), "level"));
            a.put(decl("phone_call",
                    "Places a phone call. Accepts either a phone number OR a "
                    + "contact name — if a name is given, it looks the person up "
                    + "in the phone's contacts and calls them.",
                    props("number", "STRING",
                            "A phone number, or a contact name like 'Mom' or 'Rahul'"),
                    "number"));
            a.put(decl("upi_usd",
                    "Opens the OFFLINE UPI menu over USSD (works with no internet, "
                    + "over the SIM's voice channel). 'send' dials *99*1*3# to send "
                    + "money, 'balance' dials *99*3# to check balance, blank dials "
                    + "*99#. The user then types their UPI ID/amount/PIN in the "
                    + "carrier's own dialog — Mr Happy only dials the code; money "
                    + "never moves without the user's PIN.",
                    props("action", "STRING",
                            "'send', 'balance', or blank for the main menu")));
            a.put(decl("answer_call",
                    "Answers the currently ringing incoming call.",
                    new JSONObject().put("type", "OBJECT")
                            .put("properties", new JSONObject())));
            a.put(decl("end_call",
                    "Ends / hangs up the current phone call.",
                    new JSONObject().put("type", "OBJECT")
                            .put("properties", new JSONObject())));
            a.put(decl("send_whatsapp",
                    "Sends a WhatsApp message to a contact. Resolves the contact by name or number, opens the chat with the message, and sends it.",
                    props2("message", "STRING", "The message text to send",
                            "recipient", "STRING",
                            "Contact name (e.g. 'Mom') or phone number with country code"),
                    "message"));
            a.put(decl("set_alarm",
                    "Sets an alarm on the phone's clock.",
                    props3("hour", "NUMBER", "Hour 0-23",
                           "minute", "NUMBER", "Minute 0-59",
                           "message", "STRING", "Alarm label (optional)"),
                    "hour", "minute"));
            a.put(decl("set_timer",
                    "Starts a countdown timer.",
                    props2("seconds", "NUMBER", "Timer length in seconds",
                           "message", "STRING", "Timer label (optional)"),
                    "seconds"));
            a.put(decl("play_youtube",
                    "Searches and opens YouTube for a video, song or topic.",
                    props("query", "STRING", "What to play or search"), "query"));
            a.put(decl("open_website",
                    "Opens a website or performs a web search in the browser.",
                    props("query", "STRING", "URL or search terms"), "query"));
            a.put(decl("open_settings",
                    "Opens a phone settings panel.",
                    props("panel", "STRING",
                            "'wifi', 'bluetooth', 'internet', 'display', 'sound', "
                            + "'battery' or 'settings'"), "panel"));
            a.put(decl("device_status",
                    "Reports battery level, charging state, memory and storage.",
                    new JSONObject().put("type", "OBJECT")
                            .put("properties", new JSONObject())));

            // ── Full-control tools (via the accessibility service) ──
            a.put(decl("system_action",
                    "Performs a system navigation action on the phone.",
                    props("action", "STRING",
                            "One of: home, back, recents, notifications, "
                            + "quick_settings, lock, power_dialog, screenshot"),
                    "action"));
            a.put(decl("tap_text",
                    "Taps an on-screen button, item or link by its visible text. "
                    + "Use this to press things inside any app.",
                    props("label", "STRING", "The visible text to tap"), "label"));
            a.put(decl("swipe_screen",
                    "Swipes or scrolls the screen.",
                    props("direction", "STRING", "up, down, left or right"),
                    "direction"));
            a.put(decl("tap_at",
                    "Taps exact screen coordinates in pixels. Prefer tap_text "
                    + "when you know the label.",
                    props2("x", "NUMBER", "X pixel", "y", "NUMBER", "Y pixel"),
                    "x", "y"));
            a.put(decl("type_into_screen",
                    "Types text into the currently focused text field of any app.",
                    props("text", "STRING", "Text to type"), "text"));
            a.put(decl("read_screen",
                    "Reads back the text currently visible on the screen, so you "
                    + "know what is shown before acting.",
                    new JSONObject().put("type", "OBJECT")
                            .put("properties", new JSONObject())));
            a.put(decl("run_termux",
                    "Runs a shell command in Termux and RETURNS its actual output "
                    + "(stdout/stderr) so you can read the result back to the user — "
                    + "e.g. list files with 'ls -la ~', check disk with 'df -h', run "
                    + "a script or the Axzora backend. Waits up to 20s for the "
                    + "command to finish. Termux must be installed with "
                    + "allow-external-apps=true in ~/.termux/termux.properties.",
                    props("command", "STRING",
                            "The shell command line, e.g. 'ls -la ~' or "
                            + "'python ~/odysseus/app.py'"), "command"));
            a.put(decl("find_and_tap",
                    "Scrolls the current screen until an item with the given visible "
                    + "text appears, then taps it. Use this to change a SETTING or "
                    + "reach anything below the fold: open the right screen first "
                    + "(e.g. open_settings), then find_and_tap the option, then "
                    + "find_and_tap the toggle/value.",
                    props("label", "STRING",
                            "The visible text to find and tap, e.g. 'Bluetooth', "
                            + "'Dark theme', 'Airplane mode'"), "label"));
            a.put(decl("wait_seconds",
                    "Pauses briefly to let a screen finish loading or an animation "
                    + "settle before the next action.",
                    props("seconds", "NUMBER", "How long to wait, 1-5"), "seconds"));
            a.put(decl("play_chess",
                    "Play a real game of chess by voice — you have a built-in chess "
                    + "engine. Call with action 'new' to start (say who is White), "
                    + "'move' with the user's move in coordinates (like 'e2e4', "
                    + "'e7e8q' to promote, 'e1g1' to castle) — it applies their move "
                    + "and returns YOUR reply move plus the board — or 'board' to see "
                    + "the current position. Narrate your move naturally.",
                    props2("action", "STRING", "'new', 'move' or 'board'",
                           "move", "STRING",
                           "The user's move in coordinates when action='move' "
                           + "(e.g. 'e2e4'); for 'new', 'black' if the user wants "
                           + "to play Black"),
                    "action"));

            // ── Maps / navigation / location ──
            a.put(decl("navigate_to",
                    "Starts turn-by-turn GPS navigation to a destination in Google "
                    + "Maps. Use this whenever the user says take/drive/navigate/go "
                    + "to a place.",
                    props2("destination", "STRING",
                           "Where to go — an address or place name, e.g. "
                           + "'Bangalore airport', 'nearest petrol pump'",
                           "mode", "STRING",
                           "Travel mode: 'driving' (default), 'walking', "
                           + "'bicycling' or 'transit'"),
                    "destination"));
            a.put(decl("show_directions",
                    "Shows the route/directions to a place on the map (without "
                    + "starting live navigation). Use when the user wants to SEE the "
                    + "route or distance/time.",
                    props2("destination", "STRING", "Address or place name",
                           "mode", "STRING",
                           "'driving', 'walking', 'bicycling' or 'transit'"),
                    "destination"));
            a.put(decl("find_places",
                    "Searches the map for nearby places — restaurants, ATMs, petrol "
                    + "pumps, hospitals, shops, etc.",
                    props("query", "STRING",
                            "What to find nearby, e.g. 'coffee', 'pharmacy'"),
                    "query"));
            a.put(decl("current_location",
                    "Reports the user's current location as an address (reverse-"
                    + "geocoded from GPS).",
                    new JSONObject().put("type", "OBJECT")
                            .put("properties", new JSONObject())));

            // ── Messaging / media / system ──
            a.put(decl("send_sms",
                    "Sends an SMS text message directly (it actually sends — no need "
                    + "to press send). Accepts a phone number or a contact name.",
                    props2("to", "STRING", "Phone number or contact name",
                           "message", "STRING", "The text to send"),
                    "to", "message"));
            a.put(decl("media_control",
                    "Controls whatever is playing music/video (Spotify, YouTube, "
                    + "etc.): play, pause, next or previous track.",
                    props("action", "STRING",
                            "'play', 'pause', 'next', 'previous' or 'stop'"),
                    "action"));
            a.put(decl("set_ringer",
                    "Sets the phone's ringer mode.",
                    props("mode", "STRING", "'normal', 'vibrate' or 'silent'"),
                    "mode"));
            a.put(decl("create_event",
                    "Creates a calendar event (opens the calendar with it pre-filled).",
                    props3("title", "STRING", "Event title",
                           "start", "STRING",
                           "Start time as 'YYYY-MM-DD HH:MM' (24h)",
                           "minutes", "NUMBER", "Duration in minutes (optional)"),
                    "title", "start"));
            a.put(decl("take_photo",
                    "Opens the camera to take a photo.",
                    props("selfie", "BOOLEAN",
                            "true for the front/selfie camera (optional)")));
            a.put(decl("share_text",
                    "Opens the share sheet to send text/a link to any app the user "
                    + "picks.",
                    props("text", "STRING", "The text or link to share"), "text"));
            a.put(decl("open_openroom",
                    "Opens OpenRoom — the AI desktop on this phone — optionally "
                    + "straight to one of its apps: Twitter, Music Player, Diary, "
                    + "Album, Email, Chess, Gomoku, FreeCell, Evidence Vault, "
                    + "CyberNews, or the character Aoi. Use this whenever the user "
                    + "mentions OpenRoom or any of those.",
                    props("app", "STRING",
                            "Which OpenRoom app to open, e.g. 'chess', 'diary', "
                            + "'music', 'twitter' — or leave blank for the desktop"))); 
            a.put(decl("open_sources",
                    "Opens 'Open Sources' — the Colibri / codekk open-source and "
                    + "AI-tools hub on this phone (AI Daily Digest, dev tools like "
                    + "JSON/hash/text utilities, and curated open-source projects). "
                    + "Use when the user says open sources, colibri, codekk, the "
                    + "AI digest, or asks for a dev tool.",
                    props("section", "STRING",
                            "Optional section, e.g. 'ai', 'json', 'md5' — blank for "
                            + "the main hub")));
            a.put(decl("open_game",
                    "Opens a game on the phone. Currently: Ludo (offline board "
                    + "game). Use when the user says play Ludo or open a game.",
                    props("game", "STRING", "Game name, e.g. 'ludo'")));
            a.put(decl("open_xr",
                    "Opens the Satyug XR Room (a 3D world). Use when the user says "
                    + "open XR, the room, or the 3D world.",
                    new JSONObject().put("type","OBJECT").put("properties", new JSONObject())));
            a.put(decl("open_games",
                    "Opens the Games section of the phone (Ludo, XR Room, and "
                    + "Happy Paisa token matches). Use when the user says games, "
                    + "open games, or play a game.",
                    new JSONObject().put("type","OBJECT").put("properties", new JSONObject())));
            a.put(decl("traffic",
                    "Checks traffic or the route between two places and opens it on "
                    + "the map with the live traffic layer. Use when the user asks "
                    + "about traffic, the commute, the fastest way, or route delays. "
                    + "'from'/'to' may be an address or the words 'home'/'work'.",
                    props2("from", "STRING", "Starting place, an address, or 'home'.",
                            "to", "STRING", "Destination, an address, or 'work'.")));
            a.put(decl("web_search",
                    "Looks something up on the web and returns real information as text. "
                    + "Use for facts, definitions, and questions about the outside world "
                    + "(\"who is…\", \"what is…\", \"who won…\") that aren't known offhand. "
                    + "Returns article titles and summaries the assistant can read out.",
                    props("query", "STRING", "The thing to look up, e.g. 'Hyperloop' or 'current ISRO mission'")));
            a.put(decl("weather",
                    "Returns live, current weather for a place: temperature and condition. "
                    + "Use for any weather question (\"weather in Delhi\", \"is it raining in "
                    + "Hyderabad\"). No key needed.",
                    props("place", "STRING", "City or place name, e.g. 'Hyderabad'")));

            // ── Admin (operator) tools ──
            // These need the on-device admin code (the console must have been
            // unlocked once) and a held admin seat. Only offer them when the
            // device actually has a stored code, so a normal install never sees
            // them. The code is injected from the wallet store — not sent to the
            // model — and the server re-checks it on every call.
            if (AdminTools.hasCode(ctx)) {
                a.put(decl("admin_overview",
                        "Reports who is on Axzora, their Happy Paisa balances, and when they "
                        + "were last active. Use when the operator asks about users, members, "
                        + "balances, or who is online.",
                        new JSONObject().put("type", "OBJECT")
                                .put("properties", new JSONObject())));
                a.put(decl("admin_notify",
                        "Sends an admin notification to every Axzora user or to one specific "
                        + "user. Target 'ALL' (or blank) for everyone; otherwise an Axzora ID. "
                        + "Operator-only, requires confirmation.",
                        props3("target", "STRING", "'ALL' or a specific Axzora user ID",
                               "title", "STRING", "Short heading (optional)",
                               "text", "STRING", "The notice text")));
                a.put(decl("admin_mint",
                        "Mints Happy Paisa to an Axzora user (adds to their balance). Takes the "
                        + "user's Axzora ID and an amount. Operator-only, requires confirmation.",
                        props2("uid", "STRING", "The Axzora user ID to credit",
                               "hp", "NUMBER", "Amount of Happy Paisa to add")));
            }

            // In restricted mode the invasive tools never reach the model, so
            // it cannot even try to call them.
            if (isRestricted(ctx)) {
                for (int i = a.length() - 1; i >= 0; i--) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null && RESTRICTED_TOOLS.contains(t.optString("name"))) a.remove(i);
                }
            }
            return a;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject decl(String name, String desc, JSONObject params,
                                   String... required) throws Exception {
        if (required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            params.put("required", req);
        }
        return new JSONObject().put("name", name)
                .put("description", desc).put("parameters", params);
    }

    private static JSONObject props(String n, String t, String d) throws Exception {
        return new JSONObject().put("type", "OBJECT").put("properties",
                new JSONObject().put(n, prop(t, d)));
    }

    private static JSONObject props2(String n1, String t1, String d1,
                                     String n2, String t2, String d2) throws Exception {
        return new JSONObject().put("type", "OBJECT").put("properties",
                new JSONObject().put(n1, prop(t1, d1)).put(n2, prop(t2, d2)));
    }

    private static JSONObject props3(String n1, String t1, String d1,
                                     String n2, String t2, String d2,
                                     String n3, String t3, String d3) throws Exception {
        return new JSONObject().put("type", "OBJECT").put("properties",
                new JSONObject().put(n1, prop(t1, d1))
                        .put(n2, prop(t2, d2)).put(n3, prop(t3, d3)));
    }

    private static JSONObject prop(String type, String desc) throws Exception {
        return new JSONObject().put("type", type).put("description", desc);
    }

    /**
     * Ask for runtime permissions when we have a screen to ask from. From the
     * background service there's no Activity, so the caller's message tells
     * the user to grant it in Mr Happy's screen instead.
     */
    private static void askPerms(Context ctx, String[] perms, int req) {
        if (ctx instanceof Activity) {
            try { ((Activity) ctx).requestPermissions(perms, req); } catch (Exception ignored) { }
        }
    }

    // ── Dispatcher ───────────────────────────────────────────────────────────

    static String execute(Context ctx, String name, JSONObject args) {
        try {
            // Restricted mode blocks the invasive tools outright, even if the
            // model somehow still names one.
            if (isRestricted(ctx) && RESTRICTED_TOOLS.contains(name)) {
                return "Blocked: " + name + " is disabled in restricted mode. "
                        + "Tell the user to allow advanced tools in Axzora "
                        + "Settings → Mr Happy if they want it.";
            }
            switch (name) {
                case "open_app":       return openApp(ctx, args.optString("app_name"));
                case "set_flashlight": return flashlight(ctx, args.optBoolean("on", true));
                case "set_volume":     return volume(ctx, args);
                case "set_brightness": return brightness(ctx, args.optInt("level", 50));
                case "phone_call":     return dial(ctx, args.optString("number"));
                case "upi_usd":        return upiUssd(ctx, args.optString("action", ""));
                case "answer_call":    return answerCall(ctx);
                case "end_call":       return endCall(ctx);
                case "send_whatsapp":  return whatsapp(ctx, args.optString("message"),
                                                      args.optString("recipient"));
                case "set_alarm":      return alarm(ctx, args.optInt("hour"),
                                                   args.optInt("minute"),
                                                   args.optString("message", "Mr Happy"));
                case "set_timer":      return timer(ctx, args.optInt("seconds", 60),
                                                   args.optString("message", "Mr Happy"));
                case "play_youtube":   return youtube(ctx, args.optString("query"));
                case "open_website":   return website(ctx, args.optString("query"));
                case "open_settings":  return settingsPanel(ctx, args.optString("panel"));
                case "device_status":  return status(ctx);
                // ── Full control (accessibility) ──
                case "system_action":  return a11y(ctx, s ->
                        s.globalAction(args.optString("action")));
                case "tap_text":       return a11y(ctx, s ->
                        s.tapText(args.optString("label")));
                case "swipe_screen":   return a11y(ctx, s ->
                        s.swipe(args.optString("direction")));
                case "tap_at":         return a11y(ctx, s ->
                        s.tap((float) args.optDouble("x", 0),
                              (float) args.optDouble("y", 0)));
                case "type_into_screen": return a11y(ctx, s ->
                        s.typeText(args.optString("text")));
                case "read_screen":    return a11y(ctx, HappyReddyA11y::readScreen);
                case "find_and_tap":   return a11y(ctx, s ->
                        s.scrollToAndTap(args.optString("label")));
                case "run_termux":     return runTermux(ctx, args.optString("command"));
                case "wait_seconds":   return waitSeconds(args.optInt("seconds", 2));
                case "play_chess":     return playChess(args);
                case "navigate_to":    return navigate(ctx, args.optString("destination"),
                                                       args.optString("mode"), true);
                case "show_directions":return navigate(ctx, args.optString("destination"),
                                                       args.optString("mode"), false);
                case "traffic":        return Traffic.INSTANCE.syncReply(ctx,
                                                       args.optString("from").isEmpty() ? null : args.optString("from"),
                                                       args.optString("to").isEmpty() ? null : args.optString("to"));
                case "web_search":     return webSearch(ctx, args.optString("query"));
                case "weather":        return weatherNow(ctx, args.optString("place"));
                case "admin_overview": return AdminTools.overview(ctx);
                case "admin_notify":   return AdminTools.notify(ctx,
                        args.optString("target"), args.optString("title"), args.optString("text"));
                case "admin_mint":     return AdminTools.mint(ctx,
                        args.optString("uid"), args.optDouble("hp", 0));
                case "find_places":    return findPlaces(ctx, args.optString("query"));
                case "current_location": return currentLocation(ctx);
                case "send_sms":       return sendSms(ctx, args.optString("to"),
                                                     args.optString("message"));
                case "media_control":  return media(ctx, args.optString("action"));
                case "set_ringer":     return ringer(ctx, args.optString("mode"));
                case "create_event":   return createEvent(ctx, args);
                case "take_photo":     return takePhoto(ctx, args.optBoolean("selfie", false));
                case "share_text":     return shareText(ctx, args.optString("text"));
                case "open_openroom":  return openOpenRoom(ctx, args.optString("app"));
                case "open_sources":   return openSources(ctx, args.optString("section"));
                case "open_game":      return openGame(ctx, args.optString("game"));
                case "open_xr":        return openXr(ctx);
                case "open_games":     return openGames(ctx);
                default:               return "Unknown tool: " + name;
            }
        } catch (Exception e) {
            return "Failed: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ── Full-control routing ─────────────────────────────────────────────────

    private interface A11yOp { String run(HappyReddyA11y svc); }

    private static String a11y(Context ctx, A11yOp op) {
        HappyReddyA11y svc = HappyReddyA11y.get();
        if (svc == null) {
            // Not yet granted — send the user to enable it, once.
            try {
                ctx.startActivity(new Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            return "Screen control isn't on. I opened Accessibility settings — "
                    + "turn on 'Mr Happy Control' (an app update can switch it off "
                    + "again), then try once more.";
        }
        return op.run(svc);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private static String openApp(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) return "No app name given";
        String q = query.trim().toLowerCase(Locale.ROOT);
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);

        ResolveInfo exact = null, partial = null;
        List<String> names = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            names.add(label);
            String l = label.toLowerCase(Locale.ROOT);
            if (l.equals(q)) { exact = ri; break; }
            if (partial == null && (l.contains(q) || q.contains(l))) partial = ri;
        }
        ResolveInfo hit = exact != null ? exact : partial;
        if (hit == null) {
            return "App '" + query + "' not found. Installed apps include: "
                    + joinFirst(names, 25);
        }
        Intent launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName);
        if (launch == null) return "Cannot launch " + hit.loadLabel(pm);
        ctx.startActivity(launch);
        return "Opened " + hit.loadLabel(pm);
    }

    private static String joinFirst(List<String> items, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, items.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String flashlight(Context ctx, boolean on) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean has = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(has)) {
                cm.setTorchMode(id, on);
                return "Flashlight " + (on ? "on" : "off");
            }
        }
        return "This device has no flashlight";
    }

    private static String volume(Context ctx, JSONObject args) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        String action = args.optString("action", "");
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        switch (action) {
            case "up":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "Volume up";
            case "down":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "Volume down";
            case "mute":
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return "Muted";
            default:
                int pct = Math.max(0, Math.min(100, args.optInt("level", 50)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI);
                return "Volume set to " + pct + "%";
        }
    }

    private static String brightness(Context ctx, int pct) {
        pct = Math.max(0, Math.min(100, pct));
        if (!Settings.System.canWrite(ctx)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(i);
            return "I need permission first — I opened the settings page. Ask the "
                    + "user to enable 'Modify system settings' for Mr Happy, "
                    + "then try again.";
        }
        Settings.System.putInt(ctx.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(ctx.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                Math.round(255 * pct / 100f));
        return "Brightness set to " + pct + "%";
    }

    private static String dial(Context ctx, String input) {
        if (input == null || input.trim().isEmpty()) return "No number or name given";
        String t = input.trim();
        // Looks like a phone number → call directly; otherwise treat as a name.
        if (t.matches("[+0-9()\\-\\s]{3,}")) {
            return placeCall(ctx, t.replaceAll("[^+0-9]", ""), null);
        }
        return callContact(ctx, t);
    }

    private static String callContact(Context ctx, String name) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            askPerms(ctx, new String[]{
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE}, 22);
            return "I need the Contacts permission to find " + name
                    + " — please allow it, then ask me again.";
        }
        String[] hit = lookupContact(ctx, name);
        if (hit == null) return "I couldn't find '" + name + "' in your contacts.";
        return placeCall(ctx, hit[1], hit[0]);
    }

    /** @return {displayName, number} of the first matching contact, or null. */
    private static String[] lookupContact(Context ctx, String name) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c != null && c.moveToFirst()) {
                return new String[]{c.getString(0), c.getString(1)};
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static String placeCall(Context ctx, String number, String displayName) {
        String num = number.replaceAll("[^+0-9]", "");
        String label = displayName != null ? displayName + " (" + num + ")" : num;
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            ctx.startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + num))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "Calling " + label;
        }
        ctx.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            askPerms(ctx, new String[]{Manifest.permission.CALL_PHONE}, 20);
        } catch (Exception ignored) { }
        return "Opened the dialer for " + label + " — allow the Phone permission "
                + "so I can place the call directly.";
    }

    /**
     * Dial a UPI USSD code over the SIM's voice channel — offline payments, no
     * data. The '#' must be URL-encoded or the ACTION_CALL intent drops it.
     * Mr Happy only dials the code; the user's PIN is entered in the carrier's
     * own dialog, so money never moves without the user.
     */
    private static String upiUssd(Context ctx, String action) {
        String code;
        String what;
        if ("balance".equalsIgnoreCase(action.trim())) { code = UssdScript.INSTANCE.balanceCode(); what = "balance"; }
        else if ("send".equalsIgnoreCase(action.trim())) { code = UssdScript.INSTANCE.sendCode(); what = "send money"; }
        else { code = "*99#"; what = "the UPI menu"; }
        return UssdScript.INSTANCE.dial(ctx, code) + " (" + what + ")";
    }

    private static String answerCall(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return "Answering needs Android 8+";
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
            askPerms(ctx, 
                    new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, 21);
            return "I need the Phone/answer permission — please allow it, then try again.";
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            tm.acceptRingingCall();
            return "Answered the call.";
        } catch (Exception e) {
            return "Couldn't answer: " + e.getMessage();
        }
    }

    private static String endCall(Context ctx) {
        if (Build.VERSION.SDK_INT < 28) return "Ending calls needs Android 9+";
        if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
            askPerms(ctx, 
                    new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, 21);
            return "I need the Phone permission — please allow it, then try again.";
        }
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            return tm.endCall() ? "Call ended." : "No active call to end.";
        } catch (Exception e) {
            return "Couldn't end the call: " + e.getMessage();
        }
    }

    private static String whatsapp(Context ctx, String message, String recipient) {
        if (message == null || message.trim().isEmpty()) {
            return "What should I say? I need the message text.";
        }
        String number = null;
        String name = null;
        if (recipient != null && !recipient.trim().isEmpty()) {
            String r = recipient.trim();
            if (r.matches("[+0-9()\\-\\s]{3,}")) {
                number = r.replaceAll("[^+0-9]", "");
                name = r.trim();
            } else {
                String[] hit = lookupContact(ctx, r);
                if (hit != null) { number = hit[1]; name = hit[0]; }
                else return "I couldn\'t find \'" + r + "\' in your contacts. Try their number, or add them as a contact.";
            }
        }
        try {
            if (number == null || number.isEmpty()) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, message);
                share.setPackage("com.whatsapp");
                share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(share);
                return "I need to know who to send it to. Pick the chat in WhatsApp (your message is ready) \u2014 or tell me their name and I\'ll find them.";
            }
            String n = number.replaceAll("[^0-9]", "");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://wa.me/" + n + "?text=" + Uri.encode(message)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            String who = name != null ? name : number;
            HappyReddyA11y svc = HappyReddyA11y.get();
            if (svc != null) {
                new Thread(() -> svc.autoSend(message)).start();
                return "Sending to " + who + " on WhatsApp\u2026";
            }
            return "Opened WhatsApp for " + who + " \u2014 message ready, tap Send.";
        } catch (Exception e) {
            return "WhatsApp isn\'t installed on this phone.";
        }
    }

    private static String alarm(Context ctx, int hour, int minute, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return String.format(Locale.ROOT,
                "Alarm set for %02d:%02d (%s)", hour, minute, label);
    }

    private static String timer(Context ctx, int seconds, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, seconds))
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "Timer started: " + seconds + " seconds";
    }

    private static String youtube(Context ctx, String query) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.youtube.com/results?search_query=" + Uri.encode(query)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "YouTube opened for: " + query;
    }

    private static String website(Context ctx, String query) {
        String url = query.trim();
        if (!url.startsWith("http") && url.contains(".") && !url.contains(" ")) {
            url = "https://" + url;
        } else if (!url.startsWith("http")) {
            url = "https://www.google.com/search?q=" + Uri.encode(query);
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "Opened " + url;
    }

    private static String settingsPanel(Context ctx, String panel) {
        String action;
        switch (panel == null ? "" : panel.toLowerCase(Locale.ROOT)) {
            case "wifi":      action = Settings.Panel.ACTION_WIFI; break;
            case "internet":  action = Settings.Panel.ACTION_INTERNET_CONNECTIVITY; break;
            case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display":   action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound":     action = Settings.Panel.ACTION_VOLUME; break;
            case "battery":   action = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
            default:          action = Settings.ACTION_SETTINGS; break;
        }
        Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "Opened " + (panel == null || panel.isEmpty() ? "settings" : panel)
                + " panel";
    }

    /**
     * Runs a shell line in Termux and WAITS for its output, so Mr Happy can
     * read the real result back (e.g. a directory listing). Uses Termux's
     * RUN_COMMAND_PENDING_INTENT result channel: Termux runs the command in
     * the background, then broadcasts stdout/stderr/exit code back to us.
     * Must be called off the main thread (it blocks up to ~20s).
     */
    private static String runTermux(Context ctx, String command) {
        if (command == null || command.trim().isEmpty()) return "No command given";

        // If Termux isn't installed there's no point waiting 20s for a result
        // that can never arrive — tell the user the fix up front instead.
        boolean termuxInstalled = false;
        try {
            ctx.getPackageManager().getPackageInfo("com.termux", 0);
            termuxInstalled = true;
        } catch (Exception ignored) { }
        if (!termuxInstalled) {
            return "Termux isn't installed on this phone, so I can't run that. " +
                    "Install Termux, then in Termux run:  " +
                    "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties  " +
                    "and then:  termux-reload-settings. Then ask me to run it again.";
        }

        // Termux must be RUNNING or its RUN_COMMAND service silently drops the
        // request (the usual cause of "no result after Ns"). Wake the service
        // so it's up, without pulling the user out of Mr Happy's screen.
        try {
            Intent wake = new Intent().setClassName("com.termux", "com.termux.app.RunCommandService");
            try { ctx.startService(wake); } catch (Exception ignored) { }
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        } catch (Exception ignored) { }

        final String action = "com.axzora.happyreddy.TERMUX_RESULT."
                + Math.abs(command.hashCode()) + "_" + SystemClock.uptimeMillis();
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {null};

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                try {
                    Bundle b = intent.getBundleExtra("result");
                    if (b != null) {
                        String so = safe(b.getString("stdout", ""));
                        String se = safe(b.getString("stderr", ""));
                        int code = b.getInt("exitCode", -1);
                        StringBuilder sb = new StringBuilder();
                        if (!so.isEmpty()) sb.append(so);
                        if (!se.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "")
                                .append("[stderr] ").append(se);
                        if (sb.length() == 0) sb.append("(command finished, no output; exit ")
                                .append(code).append(")");
                        else if (code != 0) sb.append("\n(exit ").append(code).append(")");
                        out[0] = clip(sb.toString(), 3500);
                    }
                } catch (Exception ignored) { }
                latch.countDown();
            }
        };

        try {
            registerReceiverCompat(ctx, receiver, new IntentFilter(action));
            Intent result = new Intent(action).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent i = new Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
            if (Build.VERSION.SDK_INT >= 26) {
                try { ctx.startForegroundService(i); } catch (Exception fgs) { ctx.startService(i); }
            } else ctx.startService(i);

            boolean done = latch.await(8, TimeUnit.SECONDS);
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            if (!done) return "Ran in Termux but no result after 8s — check Termux's " +
                    "\"allow-external-apps\" setting, then I'll try again.";
            return out[0] != null ? ("$ " + command + "\n" + out[0])
                    : ("Ran in Termux: " + command + " (no result returned)");
        } catch (Exception e) {
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
            return "Couldn't reach Termux (" + e.getMessage() + "). Install Termux, "
                    + "then in Termux run:  mkdir -p ~/.termux && echo "
                    + "'allow-external-apps=true' >> ~/.termux/termux.properties && "
                    + "termux-reload-settings";
        }
    }

    @SuppressWarnings({"deprecation", "UnspecifiedRegisterReceiverFlag"})
    private static void registerReceiverCompat(Context ctx, BroadcastReceiver r,
                                               IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(r, f);
        }
    }

    // ── Maps / navigation / location ─────────────────────────────────────────

    private static String modeChar(String mode) {
        switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "walking": case "walk":     return "w";
            case "bicycling": case "cycle":  return "b";
            case "transit": case "bus":      return "r";
            default:                          return "d";   // driving
        }
    }

    private static String navigate(Context ctx, String dest, String mode, boolean live) {
        if (dest == null || dest.trim().isEmpty()) return "No destination given";
        String m = modeChar(mode);
        Uri uri;
        if (live) {
            uri = Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=" + m);
        } else {
            String tm = m.equals("w") ? "walking" : m.equals("b") ? "bicycling"
                    : m.equals("r") ? "transit" : "driving";
            uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination="
                    + Uri.encode(dest) + "&travelmode=" + tm);
        }
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.google.android.apps.maps");
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));   // fall back to any maps app
        }
        return (live ? "Navigating to " : "Showing the route to ") + dest;
    }

    private static String findPlaces(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) return "What should I look for?";
        Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "Showing " + query + " nearby on the map";
    }

    // ── Real-time web intelligence ──────────────────────────────────────────

    /** Looks a query up on Wikipedia (keyless) and returns titles + summaries as text. */
    private static String webSearch(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) return "What should I look up?";
        try {
            String q = java.net.URLEncoder.encode(query.trim(), "UTF-8");
            String body = httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search"
                    + "&srsearch=" + q + "&format=json&utf8=1&srlimit=4");
            JSONObject j = new JSONObject(body);
            JSONArray hits = j.optJSONObject("query") != null
                    ? j.getJSONObject("query").optJSONArray("search") : null;
            if (hits == null || hits.length() == 0) {
                return "I couldn't find anything on the web for \"" + query + "\".";
            }
            StringBuilder sb = new StringBuilder("Here's what I found:\n");
            int n = Math.min(hits.length(), 4);
            for (int i = 0; i < n; i++) {
                JSONObject h = hits.optJSONObject(i);
                if (h == null) continue;
                String title = stripTags(h.optString("title"));
                String snip = stripTags(h.optString("snippet"));
                sb.append("\u2022 ").append(title)
                  .append(snip.isEmpty() ? "" : " \u2014 " + snip).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "I couldn't reach the web for that (" + errMsg(e) + ").";
        }
    }

    /** Geocodes a place (keyless) and returns its live current weather as text. */
    private static String weatherNow(Context ctx, String place) {
        if (place == null || place.trim().isEmpty()) return "Which place? Say \"weather in <place>\".";
        try {
            String name = java.net.URLEncoder.encode(place.trim(), "UTF-8");
            JSONObject geo = new JSONObject(
                    httpGet("https://geocoding-api.open-meteo.com/v1/search?name=" + name
                            + "&count=1&language=en"));
            JSONArray results = geo.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return "I couldn't find a place called \"" + place + "\".";
            }
            JSONObject r = results.getJSONObject(0);
            double lat = r.getDouble("latitude");
            double lon = r.getDouble("longitude");
            String label = r.optString("name")
                    + (r.optString("country").isEmpty() ? "" : ", " + r.optString("country"));
            JSONObject cur = new JSONObject(
                    httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto"))
                    .optJSONObject("current");
            if (cur == null) return "I couldn't read the live weather for " + label + ".";
            String temp = cur.optString("temperature_2m");
            String code = cur.optString("weather_code");
            return "Right now in " + label + ": " + temp + "\u00b0C, " + wmoDesc(code) + ".";
        } catch (Exception e) {
            return "I couldn't get the weather for \"" + place + "\" (" + errMsg(e) + ").";
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("\\<[^>]*>", "").trim();
    }

    /** WMO weather-code descriptions (subset; see open-meteo.com/en/docs). */
    private static String wmoDesc(String code) {
        String c = code == null ? "" : code;
        switch (c) {
            case "0": return "clear sky";
            case "1": return "mainly clear";
            case "2": return "partly cloudy";
            case "3": return "overcast";
            case "45": case "48": return "fog";
            case "51": case "53": case "55": return "drizzle";
            case "61": case "63": case "65": return "rain";
            case "66": case "67": return "freezing rain";
            case "71": case "73": case "75": return "snow";
            case "80": case "81": case "82": return "rain showers";
            case "95": return "thunderstorm";
            default: return "code " + c;
        }
    }

    private static String errMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static String currentLocation(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            askPerms(ctx, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 23);
            return "I need the Location permission — please allow it, then ask again.";
        }
        try {
            LocationManager lm = (LocationManager)
                    ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return "I can't get a location fix yet — make sure GPS "
                    + "is on and try again in a moment.";
            double lat = best.getLatitude(), lon = best.getLongitude();
            try {
                Geocoder g = new Geocoder(ctx, Locale.getDefault());
                List<Address> addrs = g.getFromLocation(lat, lon, 1);
                if (addrs != null && !addrs.isEmpty()) {
                    Address a = addrs.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(a.getAddressLine(i));
                    }
                    return "You're at: " + sb;
                }
            } catch (Exception ignored) { }
            return String.format(Locale.ROOT, "You're at %.5f, %.5f", lat, lon);
        } catch (Exception e) {
            return "Couldn't read location: " + e.getMessage();
        }
    }

    // ── Messaging / media / system ───────────────────────────────────────────

    private static String sendSms(Context ctx, String to, String message) {
        if (to == null || to.trim().isEmpty()) return "Who should I text?";
        if (message == null) message = "";
        String number = to.trim();
        if (!number.matches("[+0-9()\\-\\s]{3,}")) {          // a name → look it up
            String[] hit = lookupContact(ctx, number);
            if (hit == null) return "I couldn't find '" + to + "' in your contacts.";
            number = hit[1];
        }
        number = number.replaceAll("[^+0-9]", "");
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            askPerms(ctx, new String[]{Manifest.permission.SEND_SMS}, 24);
            return "I need the SMS permission — please allow it, then ask me again.";
        }
        try {
            SmsManager sms = ctx.getSystemService(SmsManager.class);
            if (sms == null) sms = SmsManager.getDefault();
            if (message.length() > 140) {
                sms.sendMultipartTextMessage(number, null,
                        sms.divideMessage(message), null, null);
            } else {
                sms.sendTextMessage(number, null, message, null, null);
            }
            return "Text sent to " + number + ": \"" + message + "\"";
        } catch (Exception e) {
            return "Couldn't send the SMS: " + e.getMessage();
        }
    }

    private static String media(Context ctx, String action) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int key;
        switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
            case "play": case "resume":  key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": case "stop":   key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next":                 key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": case "prev":key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default:                     key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
        }
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return "Media: " + (action == null || action.isEmpty() ? "play/pause" : action);
    }

    private static String ringer(Context ctx, String mode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try {
            switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
                case "silent":  am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                                return "Ringer set to silent";
                case "vibrate": am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                                return "Ringer set to vibrate";
                default:        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                                return "Ringer set to normal";
            }
        } catch (SecurityException e) {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Exception ignored) { }
            return "Silent/vibrate needs Do-Not-Disturb access — I opened that "
                    + "settings page; enable Mr Happy, then try again.";
        }
    }

    private static String createEvent(Context ctx, JSONObject args) {
        String title = args.optString("title", "Event");
        String start = args.optString("start", "");
        int minutes = args.optInt("minutes", 60);
        Intent i = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        long millis = parseWhen(start);
        if (millis > 0) {
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis);
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                    millis + Math.max(1, minutes) * 60000L);
        }
        ctx.startActivity(i);
        return "Calendar opened with \"" + title + "\"" + (millis > 0 ? " at " + start : "")
                + " — user confirms to save.";
    }

    private static long parseWhen(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        String[] fmts = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm"};
        for (String f : fmts) {
            try {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat(f, Locale.ROOT);
                java.util.Date d = sdf.parse(s.trim());
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    private static String takePhoto(Context ctx, boolean selfie) {
        Intent i = new Intent("android.media.action.IMAGE_CAPTURE")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (selfie) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1);
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
        }
        try {
            ctx.startActivity(i);
            return "Camera opened" + (selfie ? " (selfie)" : "") + " — tap the shutter.";
        } catch (Exception e) {
            return "Couldn't open the camera app.";
        }
    }

    private static String shareText(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return "What should I share?";
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        ctx.startActivity(Intent.createChooser(send, "Share via")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Opened the share sheet";
    }

    private static String openGames(Context ctx) {
        try {
            ctx.startActivity(new Intent().setClassName(ctx.getPackageName(),
                    "com.axzora.os.games.GamesActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return "Opened Games";
        } catch (Exception e) { return "Couldn't open Games: " + e.getMessage(); }
    }

    private static String openXr(Context ctx) {
        try {
            ctx.startActivity(new Intent().setClassName(ctx.getPackageName(),
                    "com.axzora.os.games.XrRoomActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return "Opened the XR Room";
        } catch (Exception e) { return "Couldn't open the XR Room: " + e.getMessage(); }
    }

    private static String openGame(Context ctx, String game) {
        try {
            Intent i = new Intent().setClassName(ctx.getPackageName(),
                    "com.axzora.os.games.LudoActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            return "Opened Ludo";
        } catch (Exception e) { return "Couldn't open the game: " + e.getMessage(); }
    }

    private static String openSources(Context ctx, String section) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx.getPackageName(),
                    "com.axzora.os.sources.OpenSourcesActivity");
            if (section != null && !section.trim().isEmpty())
                i.putExtra("axzora.sources.path", section.trim());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            return "Opened Open Sources";
        } catch (Exception e) {
            return "Couldn't open Open Sources: " + e.getMessage();
        }
    }

    private static String openOpenRoom(Context ctx, String app) {
        try {
            String path = com.axzora.os.openroom.OpenRoom.INSTANCE.pathFor(app);
            Intent i = new Intent();
            i.setClassName(ctx.getPackageName(),
                    "com.axzora.os.openroom.OpenRoomActivity");
            if (!path.isEmpty()) i.putExtra("axzora.openroom.path", path);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            return app == null || app.trim().isEmpty()
                    ? "Opened OpenRoom" : "Opened OpenRoom — " + app.trim();
        } catch (Exception e) {
            return "Couldn't open OpenRoom: " + e.getMessage();
        }
    }

    private static String waitSeconds(int seconds) {
        int s = Math.max(1, Math.min(5, seconds));
        try { Thread.sleep(s * 1000L); } catch (InterruptedException ignored) { }
        return "Waited " + s + "s.";
    }

    private static String playChess(JSONObject args) {
        String action = args.optString("action", "move").toLowerCase(Locale.ROOT);
        switch (action) {
            case "new": {
                String m = args.optString("move", "");
                boolean userWhite = !(m.contains("black") || m.contains("second"));
                return ChessEngine.newGame(userWhite);
            }
            case "board": return ChessEngine.boardText();
            default:      return ChessEngine.userMove(args.optString("move"));
        }
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)";
    }

    private static String status(Context ctx) {
        StringBuilder sb = new StringBuilder();

        Intent bi = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st    = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
            sb.append("Battery: ").append(level * 100 / Math.max(1, scale))
              .append("%").append(charging ? " (charging)" : "").append(". ");
        }

        ActivityManager am = (ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append("RAM: ").append(mi.availMem / (1024 * 1024)).append(" MB free of ")
          .append(mi.totalMem / (1024 * 1024)).append(" MB. ");

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        sb.append("Storage: ").append(fs.getAvailableBytes() / (1024 * 1024 * 1024L))
          .append(" GB free of ")
          .append(fs.getTotalBytes() / (1024 * 1024 * 1024L)).append(" GB.");

        return sb.toString();
    }
}
