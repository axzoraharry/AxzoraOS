package com.axzora.happyreddy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mr Happy's memory: everything the user says, kept on the phone.
 *
 * Two layers, because "remember" means two things. The journal is the raw
 * record — every utterance and typed line, timestamped, so "what did I say
 * about the invoice" has something to search. Facts are the deliberate layer:
 * "remember my locker code is 4412" survives forever and is repeated back on
 * demand.
 *
 * Everything lives in the app's private files; nothing is uploaded. The only
 * place memory leaves the phone is the slice injected into the live session's
 * system prompt — which is what lets Mr Happy <em>use</em> it in conversation.
 */
public final class MemoryStore {

    private static final int JOURNAL_MAX_LINES = 4000;
    private static final int PROMPT_JOURNAL_LINES = 40;
    private static final long UTTERANCE_GAP_MS = 4000;

    private final File journal;
    private final File facts;

    /** Speech arrives in fragments; they are stitched into one utterance. */
    private final StringBuilder pending = new StringBuilder();
    private long pendingAt = 0L;

    public MemoryStore(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "mrhappy");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        journal = new File(dir, "journal.jsonl");
        facts = new File(dir, "facts.json");
    }

    // ------------------------------------------------------------- listening

    /** A fragment of live speech. Buffered until the utterance ends. */
    public synchronized void noteSpeech(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (pending.length() > 0 && now - pendingAt > UTTERANCE_GAP_MS) {
            flushLocked();
        }
        if (pending.length() > 0) pending.append(' ');
        pending.append(chunk.trim());
        pendingAt = now;
    }

    /** A full typed line — no stitching needed. */
    public synchronized void noteTyped(String text) {
        flushLocked();
        if (text != null && !text.trim().isEmpty()) {
            append("typed", text.trim());
            captureFactFrom(text.trim());
        }
    }

    /**
     * Ends the current utterance and stores it.
     *
     * @return a fact captured from it ("remember …"), or null.
     */
    public synchronized String flush() {
        return flushLocked();
    }

    private String flushLocked() {
        if (pending.length() == 0) return null;
        String utterance = pending.toString().trim();
        pending.setLength(0);
        pendingAt = 0L;
        if (utterance.isEmpty()) return null;
        append("voice", utterance);
        return captureFactFrom(utterance);
    }

    /** "remember (that) X" spoken or typed becomes a durable fact. */
    private String captureFactFrom(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        int i = t.indexOf("remember ");
        if (i < 0) return null;
        String fact = text.substring(i + "remember ".length()).trim();
        if (fact.toLowerCase(Locale.ROOT).startsWith("that ")) fact = fact.substring(5).trim();
        if (fact.length() < 3) return null;
        addFact(fact);
        return fact;
    }

    // ----------------------------------------------------------------- facts

    public synchronized void addFact(String fact) {
        List<String> all = readFacts();
        // Re-remembering replaces rather than duplicates.
        String lower = fact.toLowerCase(Locale.ROOT);
        all.removeIf(f -> f.toLowerCase(Locale.ROOT).equals(lower));
        all.add(fact);
        while (all.size() > 200) all.remove(0);
        writeFacts(all);
    }

    public synchronized boolean forget(String what) {
        String needle = what.toLowerCase(Locale.ROOT).trim();
        if (needle.isEmpty()) return false;
        List<String> all = readFacts();
        boolean removed = all.removeIf(f -> f.toLowerCase(Locale.ROOT).contains(needle));
        if (removed) writeFacts(all);
        return removed;
    }

    public synchronized List<String> allFacts() {
        return readFacts();
    }

    // ---------------------------------------------------------------- recall

    /**
     * Answers a memory question locally, or returns null if this isn't one.
     * Runs before the AI, so memory works offline and costs nothing.
     */
    public synchronized String recallAnswer(String raw) {
        if (raw == null) return null;
        String t = raw.toLowerCase(Locale.ROOT).trim();

        if (t.matches(".*what (do|did) you remember.*") || t.contains("your memory")
                || t.matches(".*what have i told you.*")) {
            List<String> f = readFacts();
            if (f.isEmpty()) {
                return "Nothing saved yet. Say \"remember ...\" and I will keep it.";
            }
            StringBuilder sb = new StringBuilder(
                    "I'm keeping " + f.size() + (f.size() == 1 ? " thing:" : " things:"));
            for (int i = Math.max(0, f.size() - 8); i < f.size(); i++) {
                sb.append('\n').append("- ").append(f.get(i));
            }
            return sb.toString();
        }

        if (t.startsWith("forget ")) {
            String what = raw.substring(7).trim();
            return forget(what) ? "Forgotten." : "I wasn't keeping anything about that.";
        }

        // "what did I say about X" / "did I mention X"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:what did i say about|did i (?:mention|say anything about)|what do you know about)\\s+(.+)")
                .matcher(t);
        if (m.find()) {
            String topic = m.group(1).replaceAll("[?.!]+$", "").trim();
            List<String> hits = search(topic, 5);
            if (hits.isEmpty()) return "I don't have anything about " + topic + " yet.";
            StringBuilder sb = new StringBuilder("Here's what I have about " + topic + ":");
            for (String h : hits) sb.append('\n').append("- ").append(h);
            return sb.toString();
        }

        return null;
    }

    /** Search facts first, then the journal, newest first. */
    public synchronized List<String> search(String topic, int max) {
        List<String> out = new ArrayList<>();
        String needle = topic.toLowerCase(Locale.ROOT);
        List<String> f = readFacts();
        for (int i = f.size() - 1; i >= 0 && out.size() < max; i--) {
            if (f.get(i).toLowerCase(Locale.ROOT).contains(needle)) out.add(f.get(i));
        }
        List<JSONObject> lines = readJournal();
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault());
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject o = lines.get(i);
            String text = o.optString("t", "");
            if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add("\"" + text + "\" (" + fmt.format(new Date(o.optLong("ts"))) + ")");
            }
        }
        return out;
    }

    // ------------------------------------------------------- prompt injection

    /**
     * The slice of memory the live session is given, so the AI answers with
     * what it knows about this user rather than as a stranger.
     */
    public synchronized String promptBlock() {
        StringBuilder sb = new StringBuilder();
        List<String> f = readFacts();
        if (!f.isEmpty()) {
            sb.append("\n\nThings the user asked you to remember (treat as true):\n");
            for (int i = Math.max(0, f.size() - 40); i < f.size(); i++) {
                sb.append("- ").append(f.get(i)).append('\n');
            }
        }
        List<JSONObject> lines = readJournal();
        if (!lines.isEmpty()) {
            sb.append("\nRecent things the user said (newest last):\n");
            for (int i = Math.max(0, lines.size() - PROMPT_JOURNAL_LINES); i < lines.size(); i++) {
                sb.append("- ").append(lines.get(i).optString("t", "")).append('\n');
            }
        }
        if (sb.length() > 0) {
            sb.append("\nWhen the user says \"remember ...\", confirm briefly. ")
                    .append("Use this memory naturally; don't recite it unasked.");
        }
        return sb.toString();
    }

    public synchronized int journalCount() {
        return readJournal().size();
    }

    // ------------------------------------------------------------------ disk

    private void append(String kind, String text) {
        try {
            JSONObject o = new JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("k", kind)
                    .put("t", text);
            try (FileWriter w = new FileWriter(journal, true)) {
                w.write(o.toString());
                w.write('\n');
            }
            trimIfHuge();
        } catch (Exception ignored) { }
    }

    private void trimIfHuge() {
        if (journal.length() < 700_000) return;
        List<JSONObject> lines = readJournal();
        int from = Math.max(0, lines.size() - JOURNAL_MAX_LINES / 2);
        try (FileWriter w = new FileWriter(journal, false)) {
            for (int i = from; i < lines.size(); i++) {
                w.write(lines.get(i).toString());
                w.write('\n');
            }
        } catch (Exception ignored) { }
    }

    private List<JSONObject> readJournal() {
        List<JSONObject> out = new ArrayList<>();
        if (!journal.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(journal))) {
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    out.add(new JSONObject(line));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private List<String> readFacts() {
        List<String> out = new ArrayList<>();
        if (!facts.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(facts))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void writeFacts(List<String> all) {
        try (FileWriter w = new FileWriter(facts, false)) {
            JSONArray arr = new JSONArray();
            for (String f : all) arr.put(f);
            w.write(arr.toString());
        } catch (Exception ignored) { }
    }
}
