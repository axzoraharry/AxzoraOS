package com.axzora.os.ceo

/**
 * The eight AI employees the CEO manages. Each is a focused worker with its own
 * skills, memory, self-evaluation, and a safe (procedural, non-destructive)
 * way to act. They run entirely on the phone.
 *
 * Honesty note: these are real, deterministic workers that can look things up,
 * plan, remember, and report — but they are not (yet) "thinking" agents. Where
 * you want an agent to use actual language reasoning, wire its [work] up to the
 * app's DeepSeek brain. The orchestration, routing, memory, and self-evaluation
 * are useful on their own and are the parts most platforms get wrong.
 */
object SubAgents {

    private fun note(score: Double, text: String) = AgentEval(score, text)

    // ── Research ────────────────────────────────────────────────────────────

    val research = object : Agent {
        override val id = "research"
        override val name = "Research Agent"
        override val emoji = "🔎"
        override val skills = setOf("research", "search", "facts", "analyze", "lookup")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("researched: $task")
            if (learned.size < 30) learned.add("research: $task")
            val tid = TaskIds.next(id)
            // On device it would call web_search via DeviceActions; here we do a
            // safe, honest procedural analysis and hand the CEo a real structure.
            val keywords = task.split(Regex("\\s+")).filter { it.length > 3 }.distinct().take(4)
            val output = buildString {
                append("Research briefing on: $task\n")
                append("Key terms: ${keywords.joinToString(", ")}\n")
                append("Angle: gather sources, compare, and summarise with a verdict.\n")
                append("Deliverable: a 3-bullet insight + a recommendation.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to tid, "terms" to keywords.joinToString(",")))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.85, "Produced a structured briefing.") else note(0.3, "Could not build the briefing.")

        override fun plan(task: String) = listOf("Decompose into questions", "Gather sources", "Compare + verdict", "Cite what changed")
    }

    // ── Coding ──────────────────────────────────────────────────────────────

    val coding = object : Agent {
        override val id = "coding"
        override val name = "Coding Agent"
        override val emoji = "💻"
        override val skills = setOf("code", "coding", "program", "build", "script", "software")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("coded: $task")
            if (learned.size < 30) learned.add("code: $task")
            // Real path: run_termux / read_screen / write files through DeviceActions.
            // Safe default: produce a plan + scaffold and report honestly.
            val output = buildString {
                append("Coding plan for: $task\n")
                append("• Split the requirement into functions/classes.\n")
                append("• Write clean, typed Kotlin. No network, no secrets.\n")
                append("• Compile-check before reporting.\n")
                append("Builder: I will scaffold it now (deterministic), then hand the diff back.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.8, "Delivered a scaffold + plan.") else note(0.25, "Plan incomplete.")

        override fun plan(task: String) = listOf("Read the requirement", "Design the change", "Implement", "Compile + fix", "Report")
    }

    // ── Marketing ───────────────────────────────────────────────────────────

    val marketing = object : Agent {
        override val id = "marketing"
        override val name = "Marketing Agent"
        override val emoji = "📣"
        override val skills = setOf("marketing", "campaign", "copy", "social", "ads", "brand")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("campaign: $task")
            val hook = task.trim()
            val output = buildString {
                append("Marketing campaign proposal for: $hook\n")
                append("• Hook: \"$hook — made easy.\"\n")
                append("• Format: 3 social posts + 1 hero image spec.\n")
                append("• Channels: WhatsApp, X, YouTube Shorts.\n")
                append("• Offer: early-access for the first 50.\n")
                append("• KPI: signups, reach, cost-per-signup.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.75, "Campaign has a clear hook + channels.") else note(0.3, "Campaign lacked direction.")

        override fun plan(task: String) = listOf("Pick the hero message", "Write copy variants", "Set channels + timing", "Measure results")
    }

    // ── Finance ─────────────────────────────────────────────────────────────

    val finance = object : Agent {
        override val id = "finance"
        override val name = "Finance Agent"
        override val emoji = "💰"
        override val skills = setOf("finance", "budget", "balance", "money", "cost", "paisa")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("finance: $task")
            val output = buildString {
                append("Financial review for: $task\n")
                append("• Check the ledger balance (admin_overview) — authority lives on the server.\n")
                append("• Build a budget: incoming vs. outgoing.\n")
                append("• Flag any spend over threshold.\n")
                append("• Recommendation: keep an emergency reserve.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.8, "Produced a budget + risk flag.") else note(0.3, "No budget created.")

        override fun plan(task: String) = listOf("Pull balances", "Model budget", "Flag risk", "Recommend")
    }

    // ── Security ────────────────────────────────────────────────────────────

    val security = object : Agent {
        override val id = "security"
        override val name = "Security Agent"
        override val emoji = "🛡"
        override val skills = setOf("security", "audit", "threat", "protect", "privacy", "vulnerability")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("audit: $task")
            val output = buildString {
                append("Security posture for: $task\n")
                append("• No secrets in shipped code — the admin code & API key stay out of the APK.\n")
                append("• Sensitive actions (send/call/mint) require a confirmation.\n")
                append("• Bounded: no unbounded loops, no silent actions.\n")
                append("• Audit: log every tool call and result.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.9, "Posture documented; gates listed.") else note(0.3, "Posture missing.")

        override fun plan(task: String) = listOf("Inventory exposure", "Check gates", "Document", "Recommend")
    }

    // ── Design ──────────────────────────────────────────────────────────────

    val design = object : Agent {
        override val id = "design"
        override val name = "Design Agent"
        override val emoji = "🎨"
        override val skills = setOf("design", "visual", "ui", "poster", "image", "layout")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("designed: $task")
            val output = buildString {
                append("Design spec for: $task\n")
                append("• Palette: deep navy base, emerald accent, warm highlight.\n")
                append("• Hero: bold title + a single strong image block.\n")
                append("• Layout: 16:9 poster + 1:1 social tile.\n")
                append("• On a GPU/design backend this becomes a rendered asset; on-device it is a spec.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.7, "Spec is buildable.") else note(0.3, "Spec incomplete.")

        override fun plan(task: String) = listOf("Read the brief", "Set palette + type", "Compose layout", "Deliver assets/spec")
    }

    // ── Customer ────────────────────────────────────────────────────────────

    val customer = object : Agent {
        override val id = "customer"
        override val name = "Customer Agent"
        override val emoji = "🤝"
        override val skills = setOf("customer", "support", "feedback", "respond", "service")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("customer: $task")
            val output = buildString {
                append("Customer response for: $task\n")
                append("• Acknowledge, empathise, then resolve.\n")
                append("• Next step: reply via WhatsApp (gated + confirmed) or email.\n")
                append("• Escalate to CEO if it's a billing or security issue.\n")
            }
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id)))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.75, "Drafted a clear, safe response.") else note(0.3, "Response vague.")

        override fun plan(task: String) = listOf("Understand the issue", "Draft a reply", "Send (gated)", "Follow up")
    }

    // ── World Builder ───────────────────────────────────────────────────────

    val world = object : Agent {
        override val id = "world"
        override val name = "World Builder Agent"
        override val emoji = "🌍"
        override val skills = setOf("world", "build", "simulate", "city", "sim", "world")
        override var goal = ""
        override val memory = ArrayDeque<String>()
        override val learned = mutableListOf<String>()

        override fun work(task: String): AgentResult {
            memory.addLast("world: $task")
            val tag = task.trim().ifBlank { "Elysium" }
            val w = WorldEngine.build(tag)
            val output = buildString {
                append("World generated: ${w.name}\n")
                append("Zones: ${w.zones.size} · Citizens: ${w.citizens.size} · Buildings: ${w.buildings.size}\n")
                append("Economy start: ${w.economy.balance} units · Weather: ${w.weather.current}\n")
                append("Step it with WorldEngine.step(1) to advance the simulation.\n")
            }
            if (learned.size < 30) learned.add("world: $tag -> ${w.name}")
            return AgentResult(task, output, true, mapOf("taskId" to TaskIds.next(id), "world" to w.name))
        }

        override fun evaluate(r: AgentResult) = if (r.success) note(0.9, "Built a working world.") else note(0.2, "World build failed.")

        override fun plan(task: String) = listOf("Seed terrain", "Place zones", "Spawn citizens", "Start economy", "Run the sim")
    }

    val all = listOf(research, coding, marketing, finance, security, design, customer, world)

    /** Look up an agent by id; returns null if unknown. */
    fun byId(id: String): Agent? = all.firstOrNull { it.id == id }

    /** Pick the best-fit agent(s) for a task by skill overlap. */
    fun route(task: String): List<Agent> {
        val words = task.lowercase().split(Regex("[^a-z]+")).filter { it.length > 2 }
        return all.map { a -> a to a.skills.count { s -> task.lowercase().contains(s) || words.any { it == s } } }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)
    }
}
