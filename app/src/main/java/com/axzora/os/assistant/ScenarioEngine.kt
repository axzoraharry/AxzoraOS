package com.axzora.os.assistant

/**
 * A lightweight, faithful adaptation of JAICF's scenario/activator pattern for
 * Mr Happy. Instead of a flat rule list, the assistant is a **state machine**:
 * a [Scenario] of [State]s, each with [Activator]s (how you get here) and an
 * [Action] (what to do when you're here). The [ScenarioEngine] is the run loop
 * that picks the first state whose activator matches and runs it.
 *
 * This mirrors JAICF (just-ai) closely but is tailored to Mr Happy's on-device,
 * deterministic, no-network behavior. It's what makes the assistant feel like a
 * real conversational state machine rather than an if/else chain:
 *
 *   Scenario { state("main") { activators { regex("/hello") } action { ... } } }
 *
 * @see com.axzora.os.assistant.Scenario
 * @see com.axzora.os.assistant.State
 * @see com.axzora.os.assistant.Activator
 */

/** A request being handled. [text] is the user's utterance; [data] carries slots. */
data class BotRequest(val text: String, val data: Map<String, String> = emptyMap())

/** The outcome of a state. [reply] is what Mr Happy says; [handled] = consumed a state. */
data class BotReply(val reply: String, val handled: Boolean, val state: String = "")

/** How an utterance reaches a state — a matcher over the text. */
interface Activator {
    /** Does this activator understand [request] at all? */
    fun canHandle(request: BotRequest): Boolean
    /** Extract slots/args from [request]; returned to the action. */
    fun parse(request: BotRequest): Map<String, String>
}

/** A regex activator — the JAICF `RegexActivator` analogue. */
class RegexActivator(private val regex: String, private val flags: RegexOption = RegexOption.IGNORE_CASE) : Activator {
    private val pattern = Regex(regex, flags)
    override fun canHandle(request: BotRequest) = pattern.containsMatchIn(request.text)
    override fun parse(request: BotRequest): Map<String, String> {
        val m = pattern.find(request.text) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        // Named groups (e.g. (?<arg>...)) map to slot keys.
        Regex("\\(\\?<([a-zA-Z_][a-zA-Z0-9_]*)>").findAll(regex)
            .map { it.groupValues[1] }.toList()
            .forEachIndexed { i, name -> m.groupValues.getOrNull(i + 1)?.let { if (it.isNotBlank()) out[name] = it } }
        // Positional groups beyond the named ones are exposed too, under "arg1..".
        var pos = 1
        m.groupValues.drop(1).forEach { v ->
            if (v.isNotBlank() && pos > 0 && !out.containsKey("arg$pos")) out["arg$pos"] = v
            pos++
        }
        return out
    }

    /**
     * The single group of the pattern (used when there's exactly one capture),
     * i.e. JAICF's `regexArgument`/`fullRequestArgument`.
     */
    fun argument(request: BotRequest): String {
        val m = pattern.find(request.text) ?: return ""
        return m.groupValues.getOrNull(1) ?: ""
    }
}

/** An action to run when a state activates. Returns what Mr Happy says. */
typealias Action = (request: BotRequest, args: Map<String, String>) -> BotReply

/** One node in the scenario graph. */
class State(
    val name: String,
    val activators: List<Activator>,
    val action: Action,
)

/** A built scenario — a named collection of states. */
class Scenario(val name: String) {
    private val states = LinkedHashMap<String, State>()
    fun state(name: String, activators: List<Activator>, action: Action) {
        states[name] = State(name, activators, action)
    }
    fun states(): Collection<State> = states.values
    fun state(name: String): State? = states[name]
}

/**
 * The run loop (JAICF's `BotEngine`). Given a request, it walks the states in
 * declaration order and runs the action of the first state whose activator
 * matches. Returns a polite "not handled" reply when nothing matches.
 */
class ScenarioEngine(private val scenario: Scenario) {

    fun process(request: BotRequest): BotReply {
        // First state whose activators understand the request wins.
        for (state in scenario.states()) {
            val activator = state.activators.firstOrNull { it.canHandle(request) }
            if (activator != null) {
                val args = activator.parse(request)
                return state.action(request, args)
            }
        }
        return BotReply("", handled = false)
    }

    /** Convenience: build from a DSL lambda. */
    companion object {
        fun build(name: String, block: ScenarioBuilder.() -> Unit): Scenario {
            val b = ScenarioBuilder(name)
            b.block()
            return b.scenario
        }
    }
}

/** The DSL that lets you write `Scenario { state(..) {} }`. */
class ScenarioBuilder(val name: String) {
    val scenario = Scenario(name)

    fun state(stateName: String, activators: List<Activator>, action: Action) {
        scenario.state(stateName, activators, action)
    }

    fun state(
        stateName: String,
        activator: Activator,
        action: Action,
    ) = state(stateName, listOf(activator), action)
}
