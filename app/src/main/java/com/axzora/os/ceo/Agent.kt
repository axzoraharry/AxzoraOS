package com.axzora.os.ceo

import java.util.concurrent.atomic.AtomicInteger

/**
 * The CEO architecture: a real on-device multi-agent intelligence for Mr Happy.
 *
 * Every [Agent] is a first-class worker with the properties the PRD requires —
 * **memory, skills, goals, tools, self-evaluation, and task planning** — exactly as
 * a software employee would have. Agents run on the phone (no network, no GPU,
 * no server). The CEO ([CeoAgent]) is the orchestrator that decomposes a request
 * into a plan and routes each task to the specialist whose skills fit.
 *
 * These are "honest" agents: they plan and act procedurally (deterministic,
 * inspectable, safe), and their results are plain text you can read. Where a real
 * model is wired in, an agent's [think] can be backed by DeepSeek — but the
 * orchestration, routing, memory, and self-evaluation are real and useful even
 * offline.
 */
interface Agent {

    /** Stable id, e.g. "research". */
    val id: String

    /** Display name, e.g. "Research Agent". */
    val name: String

    /** Emoji for the dashboard / chat. */
    val emoji: String

    /** What this agent is good at (used to route tasks). */
    val skills: Set<String>

    /** Current goal, if the CEO assigned one. */
    var goal: String

    /**
     * What this agent did on its last [work] run — its working memory. Bounded so
     * an agent can't grow memory pressure.
     */
    val memory: ArrayDeque<String>

    /**
     * Uses the agent's skills to do [task]. Returns a plain-text result. Each
     * agent is responsible for its own safety (refuse clearly rather than act
     * destructively) and for self-evaluating its outcome.
     */
    fun work(task: String): AgentResult

    /** Self-evaluation: after [work], score how well it did (0..1) + a note. */
    fun evaluate(result: AgentResult): AgentEval

    /** A short plan of the steps this agent would take for [task]. */
    fun plan(task: String): List<String>

    /** Reusable knowledge this agent accumulated (its "procedural memory"). */
    val learned: MutableList<String>
}

/** Result of an agent doing a task: the outcome text + whether it succeeded. */
data class AgentResult(
    val task: String,
    val output: String,
    val success: Boolean,
    val meta: Map<String, String> = emptyMap(),
)

/** Self-evaluation of an agent's outcome. Higher [score] = better. */
data class AgentEval(
    val score: Double,           // 0.0..1.0
    val note: String,
)

/**
 * Shared helper that lets every agent remember a bounded number of outcomes and
 * reflect on them. This is the agent's long-term memory, kept per-agent so the
 * self-improving loop has a durable substrate even without a model.
 */
class AgentMemory(private val max: Int = 20) {
    private val entries = ArrayDeque<String>()

    @Synchronized fun remember(text: String) {
        if (text.isBlank()) return
        if (entries.size >= max) entries.removeFirst()
        entries.addLast(text)
    }

    @Synchronized fun first(n: Int = 5): List<String> = entries.takeLast(n).toList()

    @Synchronized fun all(): List<String> = entries.toList()

    @Synchronized fun clear() = entries.clear()
}

/** A globally-unique task id, so dashboard "tasks running" can reference them. */
object TaskIds {
    private val seq = AtomicInteger(0)
    fun next(agentId: String): String = "$agentId-${seq.incrementAndGet()}"
}
