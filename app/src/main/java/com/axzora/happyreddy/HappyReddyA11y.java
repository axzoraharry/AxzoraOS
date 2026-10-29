package com.axzora.happyreddy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Happy Reddy's full control of the phone.
 *
 * A standard Android AccessibilityService — the user enables it once in
 * Settings, after which Happy Reddy can tap, swipe, scroll, type, read the
 * screen, and use system actions (home / back / recents / notifications /
 * quick settings / lock / screenshot). This is the same mechanism automation
 * assistants use; nothing here bypasses Android security — the user grants it.
 */
public class HappyReddyA11y extends AccessibilityService {

    private static HappyReddyA11y instance;

    public static boolean isRunning() { return instance != null; }
    public static HappyReddyA11y get() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* passive */ }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    // ── Global actions ───────────────────────────────────────────────────────

    String globalAction(String which) {
        int action;
        switch (which) {
            case "home":           action = GLOBAL_ACTION_HOME; break;
            case "back":           action = GLOBAL_ACTION_BACK; break;
            case "recents":        action = GLOBAL_ACTION_RECENTS; break;
            case "notifications":  action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock":
                if (Build.VERSION.SDK_INT >= 28) action = GLOBAL_ACTION_LOCK_SCREEN;
                else return "Lock needs Android 9+";
                break;
            case "power_dialog":   action = GLOBAL_ACTION_POWER_DIALOG; break;
            case "screenshot":
                if (Build.VERSION.SDK_INT >= 28) action = GLOBAL_ACTION_TAKE_SCREENSHOT;
                else return "Screenshot needs Android 9+";
                break;
            default: return "Unknown system action: " + which;
        }
        boolean ok = performGlobalAction(action);
        return ok ? ("Done: " + which) : ("Could not perform " + which);
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    String tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        return dispatchGesture(g, null, null)
                ? "Tapped (" + (int) x + "," + (int) y + ")"
                : "Tap failed";
    }

    String swipe(String direction) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        float x1, y1, x2, y2;
        switch (direction == null ? "" : direction.toLowerCase()) {
            case "up":    x1 = x2 = w / 2f; y1 = h * 0.75f; y2 = h * 0.25f; break;
            case "down":  x1 = x2 = w / 2f; y1 = h * 0.30f; y2 = h * 0.80f; break;
            case "left":  y1 = y2 = h / 2f; x1 = w * 0.80f; x2 = w * 0.20f; break;
            case "right": y1 = y2 = h / 2f; x1 = w * 0.20f; x2 = w * 0.80f; break;
            default: return "Direction must be up/down/left/right";
        }
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 280))
                .build();
        return dispatchGesture(g, null, null)
                ? "Swiped " + direction : "Swipe failed";
    }

    /** Scroll (up to 7 screens) until an item with this text shows, then tap it. */
    public String scrollToAndTap(String label) {
        if (label == null || label.trim().isEmpty()) return "No label given";
        String needle = label.toLowerCase();
        for (int attempt = 0; attempt < 7; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo hit = findClickableByText(root, needle);
                if (hit != null) {
                    AccessibilityNodeInfo click = hit;
                    while (click != null && !click.isClickable()) click = click.getParent();
                    (click != null ? click : hit)
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return "Tapped '" + label + "'"
                            + (attempt > 0 ? " (after scrolling)" : "");
                }
            }
            if (attempt < 6) { swipe("up"); nap(550); }
        }
        return "Couldn't find '" + label + "' even after scrolling. Try read_screen "
                + "to see what's actually shown.";
    }

    private void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ── Node operations ──────────────────────────────────────────────────────

    public String tapText(String label) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No active screen to read";
        AccessibilityNodeInfo hit = findClickableByText(root, label.toLowerCase());
        if (hit == null) return "Couldn't find '" + label + "' on screen";
        AccessibilityNodeInfo click = hit;
        while (click != null && !click.isClickable()) click = click.getParent();
        if (click == null) { hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return "Tapped '" + label + "'"; }
        click.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return "Tapped '" + label + "'";
    }

    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo node,
                                                      String needle) {
        if (node == null) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && t.toString().toLowerCase().contains(needle))
                || (d != null && d.toString().toLowerCase().contains(needle))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findClickableByText(node.getChild(i), needle);
            if (r != null) return r;
        }
        return null;
    }

    public String typeText(String text) {
        if (text == null) text = "";
        AccessibilityNodeInfo field = findEditTarget();
        if (field == null) {
            return "No text box is focused. Tap the text field first (say "
                    + "'tap <the box>'), then ask me to type. For Termux, ask me "
                    + "to RUN the command instead — the terminal can't be typed into.";
        }

        // 1) ACTION_SET_TEXT — append to whatever is already there, cursor to end
        CharSequence cur = field.getText();
        String base = cur != null ? cur.toString() : "";
        String combined = base + text;
        Bundle set = new Bundle();
        set.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    combined.length());
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    combined.length());
            field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            return "Typed: " + text;
        }

        // 2) Fallback — put text on the clipboard and PASTE it into the field
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("mrhappy", text));
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return "Typed (pasted): " + text;
                }
            }
        } catch (Exception ignored) { }

        return "That field wouldn't accept typing (some apps block it). Try "
                + "tapping the box first, or for Termux ask me to run the command.";
    }

    /** The editable node with input focus, else the first editable on screen. */
    private AccessibilityNodeInfo findEditTarget() {
        // Input-focused node is the correct target (works across app windows)
        AccessibilityNodeInfo f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null && f.isEditable()) return f;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo rf = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (rf != null && rf.isEditable()) return rf;
        return findFirstEditable(root);
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFirstEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Fills the chat box with [body] and taps Send. Best-effort: depends on the
     * app exposing an editable field and a Send button in a form Accessibility
     * can read (WhatsApp does). Returns a truthful result string — "Sent: …"
     * only when a Send tap actually happened.
     */
    public String autoSend(String body) {
        try {
        if (body == null || body.trim().isEmpty()) return "Nothing to send";

        // 1) Wait for the chat box to appear (up to ~12s).
        AccessibilityNodeInfo field = null;
        for (int i = 0; i < 24; i++) {
            field = findEditTarget();
            if (field != null) break;
            nap(500);
        }
        if (field == null) return "The chat didn't show a message box";

        // 2) Focus the box, then make sure the text is in it. Some apps need a
        //    tap to make the field editable before SET_TEXT takes, and wa.me may
        //    or may not have pre-filled it.
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        AccessibilityNodeInfo label = field;
        while (label != null && !label.isClickable()) label = label.getParent();
        (label != null ? label : field).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        nap(250);

        CharSequence cur = field.getText();
        String existing = cur == null ? "" : cur.toString().trim();
        field = findEditTarget() != null ? findEditTarget() : field;
        if (existing.isEmpty() || !existing.contains(body)) {
            Bundle set = new Bundle();
            set.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, body);
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set);
        }
        nap(350);

        // 3) Find the Send button and tap it. It only appears once there's text,
        //    so retry for a few seconds as it comes up.
        AccessibilityNodeInfo send = null;
        for (int i = 0; i < 14; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            send = root == null ? null : findSendButton(root);
            if (send != null) break;
            nap(400);
        }
        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "Sent: " + body;
        }
        String preview = body.length() > 24 ? body.substring(0, 24) + "…" : body;
        return "\"" + preview + "\" is in the box — tap Send (the arrow) to send it. "
                + "If it keeps not sending, the WhatsApp version changed; tell me and I'll retune.";
        } catch (Exception e) {
            return "I couldn't reach the send button. Tap Send (the arrow) to send it manually.";
        }
    }

    /**
     * The clickable node that sends a message: labelled "Send"/"Send message",
     * or a short label containing "send". Tolerant of WhatsApp's
     * version-specific content-descriptions.
     */
    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) {
            CharSequence d = node.getContentDescription();
            CharSequence t = node.getText();
            String label = d != null && d.toString().trim().length() > 0
                    ? d.toString().trim()
                    : (t != null ? t.toString().trim() : null);
            if (label != null) {
                String low = label.toLowerCase(Locale.ROOT);
                if (low.equals("send")) return node;
                if (low.equals("send message")) return node;
                // Short labels like "Send" as an arrow or icon.
                if (low.contains("send") && low.length() <= 12) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findSendButton(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    public String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "Screen is empty or protected";
        List<String> out = new ArrayList<>();
        collectText(root, out);
        if (out.isEmpty()) return "No readable text on screen";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(70, out.size()); i++) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(out.get(i));
        }
        sb.append("\n(items marked [tap] are clickable; [on]/[off] are toggles)");
        return sb.toString();
    }

    private void collectText(AccessibilityNodeInfo n, List<String> out) {
        if (n == null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String label = t != null && t.toString().trim().length() > 0 ? t.toString().trim()
                : (d != null && d.toString().trim().length() > 0 ? d.toString().trim() : null);
        if (label != null) {
            String tag = "";
            if (n.isCheckable()) tag = n.isChecked() ? " [on]" : " [off]";
            else if (isClickableSelf(n)) tag = " [tap]";
            String s = label + tag;
            if (!out.contains(s) && !out.contains(label)) out.add(s);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), out);
    }

    private boolean isClickableSelf(AccessibilityNodeInfo n) {
        if (n.isClickable()) return true;
        AccessibilityNodeInfo p = n.getParent();
        return p != null && p.isClickable();
    }
}
