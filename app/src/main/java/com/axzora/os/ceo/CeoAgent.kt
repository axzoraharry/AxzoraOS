package com.axzora.os.ceo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The CEO agent — the orchestrator, and the "intelligence" that decides.
 *
 * A request comes in as natural text ("create a cafe marketing campaign", "build
 * a smart city"). The CEO:
 *  1. **understands** it (intent keyword routing)
 *  2. **reasons** about it (a real LLM briefing when a model is reachable)
 *  3. **decomposes** it into tasks
 *  4. **routes** each task to the best-fit [SubAgents] specialist
 *  5. **runs** them and **self-evaluates**
 *  6. **remembers** into the shared [KnowledgeGraph] and the world store
 *  7. **aggregates** the results into one report
 *
 * The CEO holds the live executive state — active agents, running tasks, the
 * current world, and a task log — which the [CeoDashboard] renders.
 */
class CeoAgent(private val context: Context) {

    /** The employed specialists. */
    val agents: List<Agent> = SubAgents.all

    /** Currently-active task ids (for the dashboard "tasks running" count). */
    private val _activeTasks = CopyOnWriteArrayList<String>()
    val activeTasks: List<String> get() = _activeTasks.toList()

    /** A live world the World Builder produced, shown on the dashboard. */
    var world: WorldEngine.WorldState? = null
        private set

    /** Recent CEO decisions / outcomes, newest last (bounded). */
    private val ledger = ArrayDeque<String>()

    /** Number of tasks the CEO has dispatched (monotonic, for the dashboard). */
    var tasksDispatched: Int = 0
        private set

    /** Count of how many times a task was routed to each agent, for stats. */
    private val usage = mutableMapOf<String, Int>()

    /** The last executive briefing the model produced (or "" offline). */
    var lastBriefing: String = ""
        private set

    fun usageOf(agentId: String): Int = usage[agentId] ?: 0

    fun feed(): List<String> = ledger.toList().reversed()

    /**
     * Run a CEO-level request and return the aggregated report text.
     * [worldName] seeds a world explicitly; [voice] records a spoken request.
     */
    suspend fun run(request: String, worldName: String? = null, voice: Boolean = false): String =
        withContext(Dispatchers.IO) {
            ledger.clear()
            val sb = StringBuilder(if (voice) "🎙 MR HAPPY CEO (voice)\n" else "🧠 MR HAPPY CEO\n")
            sb.append("Request: $request\n")

            // 1) Real reasoning: ask a model for an executive read, so the CEO's
            //    plan is grounded rather than a keyword lookup.
            lastBriefing = Reasoner.think(
                context,
                "You are the CEO of Axzora, an on-device multi-agent system. " +
                    "Give a 3-bullet executive plan for the request, concise.",
                request,
                300,
            ) ?: ""
            if (lastBriefing.isNotBlank()) sb.append("\n📋 Executive briefing:\n").append(lastBriefing).append("\n")

            // 2) World build (priority) + persistence + knowledge graph.
            if (understand(request, worldName).worldRequest) {
                val name = worldName ?: "Elysium"
                world = WorldStore.load(context) ?: WorldEngine.build(name)
                tasksDispatched++
                ledger.addLast("World '$name' live (${world!!.citizens.size} citizens)")
                WorldEngine.step(world!!, 3)
                WorldStore.save(context, world!!)
                // record the world into the shared knowledge graph
                KnowledgeGraph.addEntity(context, "world", world!!.name, mapOf(
                    "citizens" to world!!.citizens.size.toString(),
                    "day" to world!!.day.toString()))
                sb.append("🌍 World Engine: \"${world!!.name}\" — ${world!!.citizens.size} citizens, " +
                    "${world!!.buildings.size} buildings, ₪${world!!.economy.balance.toInt()}.\n")
                sb.append(WorldEngine.map(world!!)).append("\n")
            }

            // 3) Decompose → route → run → evaluate.
            val subTasks = decompose(request)
            for (task in subTasks) {
                val picked = SubAgents.route(task).firstOrNull() ?: SubAgents.coding
                val tid = TaskIds.next(picked.id)
                _activeTasks.add(tid)
                tasksDispatched++
                usage[picked.id] = usageOf(picked.id) + 1
                try {
                    picked.goal = task
                    val result = picked.work(task)
                    val eval = picked.evaluate(result)
                    picked.memory.addLast("$tid ok=${result.success} score=${"%.2f".format(eval.score)}")
                    if (result.success) {
                        picked.learned.add("$tid $task : ok")
                        KnowledgeGraph.addEntity(context, "task", task.take(40), mapOf("agent" to picked.name))
                    }
                    sb.append("${picked.emoji} [${picked.name}] → ${result.output.trim().take(160)}\n")
                    if (eval.score < 0.4) sb.append("   ⚠ self-eval: ${eval.note}\n")
                    ledger.addLast("Routed '$task' to ${picked.name} (score %.2f)".format(eval.score))
                } catch (t: Throwable) {
                    sb.append("${picked.emoji} [${picked.name}] failed: ${t.message}\n")
                    ledger.addLast("'$task' to ${picked.name} FAILED: ${t.message}")
                } finally {
                    _activeTasks.remove(tid)
                }
            }

            sb.append("\n📊 Summary: ${agents.size} agents · ${tasksDispatched} task(s) dispatched · ")
            sb.append("${KnowledgeGraph.countEntities(context)} world fact(s) remembered.\n")
            sb.append("🎙 Ask me anything, or \"run [task]\" to delegate.\n")
            sb.toString()
        }

    private data class Intent(val worldRequest: Boolean, val keywords: List<String>)

    private fun understand(request: String, worldName: String?): Intent {
        val t = request.lowercase()
        val worldish = listOf("world", "city", "simulate", "sim", "build a", "create a", "virtual")
        val worldRequest = worldName != null || worldish.any { t.contains(it) }
        val keywords = t.split(Regex("\\s+")).filter { it.length > 3 }.distinct().take(5)
        return Intent(worldRequest, keywords)
    }

    private fun decompose(request: String): List<String> {
        val t = request.lowercase()
        val tasks = mutableListOf<String>()
        if (listOf("campaign", "marketing", "advertise", "social", "launch").any { t.contains(it) }) {
            tasks.add("build a marketing campaign for " + quoted(request))
            tasks.add("assess the budget and cost for the campaign")
            tasks.add("design a poster spec for the campaign")
        }
        if (listOf("app", "script", "automate", "software", "code", "bot").any { t.contains(it) }) {
            tasks.add("plan and scaffold a small app for " + quoted(request))
        }
        if (listOf("secure", "audit", "privacy", "protect", "threat").any { t.contains(it) }) {
            tasks.add("run a security audit for " + quoted(request))
        }
        if (listOf("customer", "support", "feedback", "respond").any { t.contains(it) }) {
            tasks.add("draft a customer response for " + quoted(request))
        }
        if (listOf("world", "city", "simulate", "sim", "build a").any { t.contains(it) }) {
            tasks.add("build the simulated world")
        }
        if (tasks.isEmpty()) tasks.add("research and summarise " + quoted(request))
        return tasks.distinct().take(4)
    }

    private fun quoted(request: String): String = "'" + request.trim().take(60) + "'"

    fun worldSummary(): String {
        val w = world ?: return "No world yet — say \"build a city\"."
        return "${w.name} · day ${w.day} · ${w.citizens.size} citizens · ₪${w.economy.balance.toInt()} · ${w.weather.current}"
    }
}
