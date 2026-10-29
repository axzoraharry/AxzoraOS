package com.axzora.os.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One actionable step in a multi-step task.
 *
 * `action` is a name from [TaskEngine.lexicon] (e.g. "openApp", "setTimer").
 * `arg` is a single string argument, empty when the action needs none
 * (e.g. "flashlight" has no arg, "openApp" takes the app name).
 */
data class PlanStep(val action: String, val arg: String)

/** A task the model has decomposed into ordered, independent steps. */
class TaskPlan(val goal: String, val steps: List<PlanStep>) {
    val total: Int get() = steps.size
}

/**
 * Mr Happy's planner/executor for complex jobs.
 *
 * The user says something like "open WhatsApp, set a 10-minute timer and turn
 * off Wi-Fi". Instead of answering in one shot, we ask the model (on-device
 * first, then the server) to break it into a JSON list of steps, then run each
 * step through the phone's real action dispatcher and report progress.
 *
 * It is deliberately decoupled from [CommandEngine]: the model call and the
 * action dispatch are injected as lambdas, so planning logic stays testable and
 * the phone actions stay where they already live.
 */
class TaskEngine(
    private val context: Context,
    /** Asks a model for free text given a prompt (on-device then server). */
    private val brainstorm: suspend (prompt: String) -> String?,
    /** Runs one action; returns a short human result, or null if unknown. */
    private val dispatch: (action: String, arg: String) -> String?,
    /** The user's contacts, so the model uses real names as recipients. */
    private val contactContext: () -> String = { "" },
) {

    /** The only actions the plan may use — everything maps to a real phone action. */
    val lexicon: List<String> = listOf(
        "openApp", "setTimer", "setAlarm", "flashlight", "flashlightOff",
        "volumeUp", "volumeDown", "mute", "webSearch", "navigate", "dial",
        "message", "whatsapp", "email", "play", "youtube", "spotify", "skipTrack", "recentApps",
    )

    /**
     * True when this utterance is a multi-step job that should be planned and
     * run rather than answered as one reply. Kept conservative so a simple
     * question ("what's the weather") is never misrouted here.
     */
    fun wantsPlanning(input: String): Boolean {
        val t = input.lowercase()
        val triggers = listOf(
            "open ", "play ", "set a ", "set timer", "set alarm", "turn on ", "turn off ",
            "mute", "flashlight", "torch", "navigate ", "call ", "message ", "email ",
            "volume ", "search ",
        )
        val hits = triggers.count { t.contains(it) }
        val repeated = hits >= 2
        val joined = t.contains(" and ") || t.contains(" and then ") ||
            (t.contains(" then ") && hits >= 1)
        // Only trust the explicit words when they're actually actionful, so a
        // benign question like "what's your plan for the weekend" never becomes
        // a task plan.
        val explicit = t.contains("automate") || t.contains("step by step") ||
            t.contains("do all") || t.contains("do these") ||
            (t.contains("plan") && hits >= 1)
        return (repeated && joined) || explicit
    }

    /**
     * Ask a model to break the task into steps and parse its JSON answer.
     * Returns null when the model is unavailable or the answer isn't a plan.
     */
    suspend fun plan(input: String): TaskPlan? {
        val prompt = buildString {
            appendLine("You are Axzora's task planner running on this phone.")
            appendLine("The user wants a job done. Break it into independent steps.")
            appendLine("Use ONLY these action names (the only things this phone can do):")
            append(lexicon.joinToString(", "))
            appendLine("")
            appendLine("For message/whatsapp/dial, arg is \"recipient|message\" (message may be").appendLine("empty). Use the recipient's EXACT name from the contact list, or their number.")
            val names = contactContext().takeIf { it.isNotBlank() }
            if (!names.isNullOrBlank()) {
                appendLine("The user's contacts: $names.")
            }
            appendLine("Each step is {\"action\":\"<name>\",\"arg\":\"<value>\"}.")
            appendLine("Set arg to \"\" when the action takes none.")
            appendLine("Reply with ONLY a JSON array. No prose, no markdown. Example:")
            appendLine("""[{"action":"openApp","arg":"WhatsApp"},{"action":"setTimer","arg":"10 minutes"}]""")
            appendLine("Task: $input")
        }
        val text = runCatching { brainstorm(prompt) }.getOrNull() ?: return null
        val steps = parseSteps(text, input)
        return if (steps.isEmpty()) null else TaskPlan(input, steps)
    }

    /**
     * Runs every step in order, calling [onStep] before each one. If a step
     * fails (the action errored or couldn't find its target), it asks the model
     * up to [MAX_REPLANS] times for a corrected single step and runs that, so a
     * task can adapt instead of stopping on the first hiccup. A step that
     * already succeeded is never re-run.
     */
    suspend fun execute(plan: TaskPlan, onStep: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        val transcript = mutableListOf<String>()
        var replans = 0
        plan.steps.forEachIndexed { i, step ->
            var label = "Step ${i + 1}/${plan.total}"
            var action = step.action
            var arg = step.arg
            var out = dispatch(action, arg)

            // A failed step can be adapted once (not retried into a loop).
            if (isFailure(out) && replans < MAX_REPLANS) {
                onStep("$label — $action ${arg.ifBlank { "" }} came back: $out → re-planning once")
                val corrected = replan(plan.goal, action, arg, out, transcript)
                val sameAsFailed = corrected != null &&
                    normalize(corrected.action) == normalize(action) && corrected.arg == arg
                if (corrected != null && !sameAsFailed) {
                    replans++
                    label = "Step ${i + 1}/${plan.total} (corrected)"
                    action = corrected.action
                    arg = corrected.arg
                    out = dispatch(action, arg)
                }
            }

            val rendered = out ?: "couldn't run ${action}${if (arg.isNotBlank()) " $arg" else ""}"
            sb.append(label).append(": ").append(rendered).appendLine()
            transcript.add("$action ${arg.ifBlank { "" }} -> $rendered")
        }
        sb.append("Task complete.")
        return sb.toString().trim()
    }

    /**
     * Asks the model for ONE corrected action given a failed step and the
     * transcript so far. Returns a [PlanStep] only when the answer parses to a
     * known action; otherwise null (so we just report the original failure).
     */
    private suspend fun replan(
        goal: String, action: String, arg: String, result: String?,
        transcript: List<String>,
    ): PlanStep? {
        val prompt = buildString {
            appendLine("A step of the task failed. Give ONE corrected action to finish it.")
            appendLine("Goal: $goal")
            appendLine("Failed step: action=$action arg=$arg → result: ${result ?: "no result"}")
            if (transcript.isNotEmpty()) appendLine("So far: ${transcript.joinToString("; ")}")
            appendLine("Allowed actions: ${lexicon.joinToString(", ")}")
            appendLine("Reply with ONLY a JSON object {\"action\":\"<name>\",\"arg\":\"<value>\"}, no prose.")
        }
        val text = runCatching { brainstorm(prompt) }.getOrNull() ?: return null
        return parseSingle(text)
    }

    /** Parses a single JSON object step out of the model's reply. */
    private fun parseSingle(text: String): PlanStep? {
        val cleaned = text.replace(Regex("```[a-zA-Z]*"), "").replace("```", "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = org.json.JSONObject(cleaned.substring(start, end + 1))
            val action = normalize(o.optString("action"))
            if (action !in normalizeAll(lexicon)) return null
            PlanStep(action, o.optString("arg").trim().ifBlank { "" })
        }.getOrNull()
    }

    /** A step is a failure when it produced nothing or reported a dead end. */
    private fun isFailure(out: String?): Boolean {
        if (out == null) return true
        val t = out.lowercase()
        return listOf("couldn't", "could not", "i couldn't", "not found", "isn't installed",
            "isn't on this phone", "blocked", "can't", "didn't", "incorrect")
            .any { t.contains(it) }
    }

    /**
     * Pulls a JSON array out of the model's reply (tolerating markdown fences
     * and surrounding prose) and maps each element to a [PlanStep]. Only steps
     * whose action is in the known lexicon are kept, so the phone never tries
     * to run something it can't.
     */
    private fun parseSteps(text: String, goal: String): List<PlanStep> {
        val cleaned = text.replace(Regex("```[a-zA-Z]*"), "").replace("```", "")
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = cleaned.substring(start, end + 1)
        return runCatching {
            val arr = JSONArray(array)
            val steps = mutableListOf<PlanStep>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val action = normalize(o.optString("action"))
                if (action !in normalizeAll(lexicon)) continue
                val arg = o.optString("arg").trim().ifBlank { "" }
                steps.add(PlanStep(action, arg))
            }
            steps
        }.getOrElse { emptyList() }
    }

    private fun normalize(name: String): String =
        name.trim().lowercase().replace("_", "").replace(" ", "")

    private fun normalizeAll(names: List<String>): Set<String> = names.map { normalize(it) }.toSet()

    private companion object {
        const val MAX_REPLANS = 2
    }
}
