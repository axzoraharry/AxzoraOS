package com.axzora.os.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import com.axzora.os.launcher.AppInfo
import com.axzora.os.launcher.AppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fully on-device intent parser. No network, no cloud model.
 *
 * Pipeline: normalise -> strip filler -> match an intent by verb/keyword ->
 * extract the entity -> fire an Android intent and reply.
 *
 * App matching is deliberately forgiving because speech-to-text rarely returns
 * the exact label: "open you tube" and "opne youtub" both resolve to YouTube.
 */
class CommandEngine(
    private val context: Context,
    private val appRepo: AppRepository,
) {
    private val memory = Memory(context)
    private val conversation = Conversation(memory)
    private val phone = PhoneFacts(context)
    private val device = DeviceControl(context)
    private val contacts = ContactBook(context)

    /** Off-main-thread dispatcher for accessibility actions (auto-send, etc.). */
    private val sendScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** The model running on this phone, when one is installed. */
    @Volatile
    var localLlm: LocalLlm? = null

    /** Shown in replies when the assistant addresses the user by name. */
    @Volatile
    var userName: String = ""

    /**
     * When a Chat is scoped to an AI-agent persona (from the Agents hub), the
     * instruction the model should adopt for that conversation. Null = plain
     * Axzora. Prepended to whichever brain answers (on-device or server).
     */
    @Volatile
    var agentPersona: String? = null

    /** Latest known app list, refreshed by the UI so "open X" can resolve. */
    @Volatile
    var apps: List<AppInfo> = emptyList()

    data class Result(
        val reply: String,
        val navigateTo: String? = null,
        /** True when nothing on the phone understood it — worth asking the model. */
        val unmatched: Boolean = false,
    )

    /** Set by the UI once the server address is known. */
    @Volatile
    var brain: Brain? = null

    /** Planner/executor for multi-step jobs; built lazily on first use. */
    private val taskEngine: TaskEngine by lazy {
        TaskEngine(
            context = context,
            brainstorm = { prompt -> answerModel(prompt) },
            dispatch = { action, arg -> runAction(action, arg)?.reply },
            contactContext = { contacts.names().joinToString(", ") },
        )
    }

    /**
     * A JAICF-style scenario/activator state machine. Mr Happy's intents are a
     * conversation state machine, not a flat if/else: activators map an
     * utterance to a state, and each state runs an action with extracted slots.
     * [routeScenario] runs before the legacy rules so the DSL wins where it
     * defines a state (and the existing rules still cover the rest).
     */
    private val scenario: ScenarioEngine by lazy {
        val model = ScenarioEngine.build("Happy") {
            state("greet",
                RegexActivator("^(hi|hello|hey|yo|good (morning|afternoon|evening|night))\\b")) { _, _ ->
                BotReply("Hey! I'm Axzora. I can play music, open apps, set alarms, search, navigate, and more. Say \"help\" for the full list.", handled = true, state = "greet")
            }
            state("help",
                RegexActivator("\\b(help|what can you do|commands|how do you work)\\b")) { _, _ ->
                BotReply(HELP_TEXT, handled = true, state = "help")
            }

            // ---- commute ----
            state("setAddress",
                RegexActivator("^(?:set |(?:my |the )?)?(home|work|office|house)\\s+(?:is|=|to)\\s+(?<arg>.+)$")) { orig, args ->
                val place = Regex("(?:^|\\s)(home|work|office|house)\\b").find(orig.text)?.groupValues?.get(1) ?: "home"
                Traffic.setAddress(context, place, args["arg"] ?: "")
                BotReply("Saved — I'll remember your $place as ${args["arg"]}.", handled = true, state = "setAddress")
            }

            // ---- media (specific → generic) ----
            state("playYoutube",
                RegexActivator("^(?:play|put on|start playing)\\s+(?<arg>.+?)\\s+(?:on|in|using)\\s+youtube\\b")) { _, a ->
                toReply(youtubeSearch(a["arg"] ?: ""), "playYoutube")
            }
            state("playSpotify",
                RegexActivator("^(?:play|put on|start playing)\\s+(?<arg>.+?)\\s+(?:on|in|using)\\s+spotify\\b")) { _, a ->
                toReply(spotifySearch(a["arg"] ?: ""), "playSpotify")
            }
            state("searchYoutube",
                RegexActivator("^(?:search|find|look up)\\s+(?<arg>.+?)\\s+(?:on|in)\\s+youtube\\b")) { _, a ->
                toReply(youtubeSearch(a["arg"] ?: ""), "searchYoutube")
            }
            state("playMedia",
                RegexActivator("^(?:play|put on|start playing)\\s+(?<arg>.+)$")) { _, a ->
                toReply(playMedia(a["arg"] ?: ""), "playMedia")
            }
            state("pauseMedia",
                RegexActivator("\\b(pause|stop)\\b.*\\b(music|song|playback)\\b")) { _, _ -> toReply(mediaKey(false), "pause") }
            state("resumeMedia",
                RegexActivator("\\b(resume|continue)\\b.*\\b(music|song|playback)\\b")) { _, _ -> toReply(mediaKey(true), "resume") }
            state("nextTrack",
                RegexActivator("\\b(next|skip)\\b.*\\b(song|track)\\b")) { _, _ -> toReply(skipTrack(), "next") }

            // ---- apps ----
            state("openApp",
                RegexActivator("^(?:open|launch|start|run|go to)\\s+(?<arg>.+)$")) { _, a ->
                toReply(openApp(a["arg"] ?: ""), "openApp")
            }

            // ---- communication ----
            state("call",
                RegexActivator("^(?:call|dial|phone)\\s+(?<arg>.+)$")) { _, a -> toReply(dial(a["arg"] ?: ""), "call") }
            state("message",
                RegexActivator("^(?:message|text|whatsapp|send (?:a )?message to)\\s+(?<arg>.+)$")) { _, a ->
                toReply(message(a["arg"] ?: ""), "message")
            }

            // ---- clock ----
            state("alarm",
                RegexActivator("\\balarm\\b")) { orig, _ -> toReply(setAlarm(orig.text), "alarm") }
            state("timer",
                RegexActivator("\\btimer\\b")) { orig, _ -> toReply(setTimer(orig.text), "timer") }

            // ---- device ----
            state("flashlight",
                RegexActivator("\\b(flash ?light|torch)\\b")) { orig, _ ->
                toReply(toggleTorch(!orig.text.contains("off")), "torch")
            }
            state("volumeUp",
                RegexActivator("\\bvolume\\b.*\\b(up|increase|louder)\\b")) { _, _ -> toReply(volume(up = true), "volup") }
            state("volumeDown",
                RegexActivator("\\bvolume\\b.*\\b(down|decrease|lower|quieter)\\b")) { _, _ -> toReply(volume(up = false), "voldown") }
            state("mute",
                RegexActivator("\\b(mute|silent)\\b")) { _, _ -> toReply(mute(), "mute") }
            state("battery",
                RegexActivator("\\bbattery\\b")) { _, _ -> toReply(Result(batteryReply()), "battery") }

            // ---- info ----
            state("time",
                RegexActivator("\\b(what'?s? the time|what time is it|tell me the time|current time)\\b")) { _, _ ->
                BotReply("It's ${now("h:mm a")}.", handled = true, state = "time")
            }
            state("date",
                RegexActivator("\\b(what'?s? the date|what day is it|today'?s date)\\b")) { _, _ ->
                BotReply("Today is ${now("EEEE, d MMMM yyyy")}.", handled = true, state = "date")
            }

            // ---- web / maps ----
            state("search",
                RegexActivator("^(?:search|google|look up|find)(?: for)?\\s+(?<arg>.+)$")) { _, a ->
                toReply(webSearch(a["arg"] ?: ""), "search")
            }
            state("navigate",
                RegexActivator("^(?:navigate to|directions to|take me to|drive to)\\s+(?<arg>.+)$")) { _, a ->
                toReply(navigate(a["arg"] ?: ""), "navigate")
            }

            // ---- autonomous watches ----
            state("watch",
                RegexActivator("^(?:watch|monitor)\\s+(?<arg>\\S+)\\s+for\\s+(.+)$")) { _, args ->
                Watches.add(context, args["arg"] ?: "", args["arg1"] ?: "")
                BotReply("Watching ${args["arg"]} for the keyword.", handled = true, state = "watch")
            }
            state("listWatches",
                RegexActivator("\\b(show|list)\\b.*\\b(watches?|monitors?)\\b")) { _, _ -> toReply(listWatches(), "listWatches") }
            state("stopWatch",
                RegexActivator("\\b(stop|cancel|remove)\\b.*\\bwatch(?:ing)?\\b.*\\s(.+)$")) { _, a ->
                val url = a["arg1"] ?: ""
                toReply(Result(if (Watches.remove(context, url)) "Stopped watching $url." else "I couldn't find that watch."), "stopWatch")
            }
            state("clearWatches",
                RegexActivator("\\b(clear|forget)\\b.*\\bwatches?\\b")) { _, _ ->
                Watches.clear(context); BotReply("Cleared all watches.", handled = true, state = "clearWatches")
            }

            // ---- apps (recent / manage) ----
            state("recentApps",
                RegexActivator("\\b(recent|recently|last)\\b.*\\b(apps?|opened|used|launched)\\b")) { _, _ -> toReply(recentApps(), "recentApps") }
            state("uninstallApp",
                RegexActivator("^(?:uninstall|remove)\\s+(?<arg>.+)$")) { _, a -> toReply(appSettings(a["arg"] ?: ""), "uninstall") }
            state("appInfo",
                RegexActivator("^(?:app info|details) (?:for|of)\\s+(?<arg>.+)$")) { _, a -> toReply(appSettings(a["arg"] ?: ""), "appInfo") }

            // ---- communication: email ----
            state("email",
                RegexActivator("^(?:email|mail)\\s+(?<arg>.+)$")) { _, a -> toReply(email(a["arg"] ?: ""), "email") }

            // ---- clock: stopwatch ----
            state("stopwatch",
                RegexActivator("\\b(stopwatch)\\b")) { _, _ -> toReply(simple(AlarmClock.ACTION_SHOW_TIMERS, "Opening the clock."), "stopwatch") }

            // ---- device: settings panels ----
            state("wifi",
                RegexActivator("\\b(wi-?fi)\\b")) { _, _ -> toReply(simple(Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi settings."), "wifi") }
            state("bluetooth",
                RegexActivator("\\bbluetooth\\b")) { _, _ -> toReply(simple(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings."), "bluetooth") }
            state("displaySettings",
                RegexActivator("\\b(brightness|display) settings?\\b")) { _, _ -> toReply(simple(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings."), "display") }
            state("settings",
                RegexActivator("\\bsettings\\b")) { _, _ -> toReply(simple(Settings.ACTION_SETTINGS, "Opening Android settings."), "settings") }

            // ---- camera ----
            state("takePhoto",
                RegexActivator("\\b(take|shoot|snap)\\b.*\\b(photo|picture|selfie)\\b")) { _, _ ->
                toReply(simple(MediaStore.ACTION_IMAGE_CAPTURE, "Opening the camera."), "photo")
            }
            state("recordVideo",
                RegexActivator("\\brecord\\b.*\\bvideo\\b")) { _, _ ->
                toReply(simple(MediaStore.ACTION_VIDEO_CAPTURE, "Opening the video camera."), "video")
            }

            // ---- location ----
            state("myLocation",
                RegexActivator("\\b(where am i|my location|show my location|track (me|us|family))\\b")) { _, _ ->
                BotReply("Opening your location map.", handled = true, state = "location")
            }

            // ---- info: weather / news / translate ----
            state("weather",
                RegexActivator("\\bweather\\b")) { orig, _ ->
                toReply(webSearch(if (orig.text.contains(" in ")) orig.text else "weather today"), "weather")
            }
            state("translate",
                RegexActivator("^(?:translate)\\s+(?<arg>.+)$")) { _, a -> toReply(webSearch("translate ${a["arg"]}"), "translate") }
            state("news",
                RegexActivator("\\bnews\\b")) { _, _ -> toReply(webSearch("latest news"), "news") }

            // ---- commute: traffic key ----
            state("trafficKey",
                RegexActivator("^(?:set|save|use)?\\s*(?:my )?(?:google )?traffic (?:api )?key\\s*(?:to|is|=)?\\s*(?<arg>\\S+)$")) { _, a ->
                Traffic.setDirectionsKey(context, a["arg"] ?: "")
                BotReply("Traffic key set — I'll give you live drive times now.", handled = true, state = "trafficKey")
            }
        }
        ScenarioEngine(model)
    }

    /** Map a helper's [Result] into the scenario's [BotReply]. */
    private fun toReply(r: Result, state: String): BotReply = BotReply(r.reply, handled = true, state = state)

    /** Route through the scenario state machine first; null if no state matched. */
    private fun routeScenario(cleaned: String): Result? {
        val reply = scenario.process(BotRequest(cleaned))
        return if (reply.handled) Result(reply.reply) else null
    }

    // UI-provided actions so the assistant can drive the shell's own look &
    // feel — "make it dark", "ember theme", "animations off". Null until wired.
    @Volatile var onSetTheme: ((Int) -> Unit)? = null   // ThemeStyles index 0..4
    @Volatile var onSetDark: ((Boolean) -> Unit)? = null
    @Volatile var onSetAnim: ((Int) -> Unit)? = null    // 0 off .. 2 full

    /** Set by the UI so "my devices" can be answered from the live hub. */
    @Volatile
    var deviceHub: com.axzora.os.devices.DeviceHub? = null
    @Volatile
    var deviceUid: String = ""

    // ---------------------------------------------------------------- entry

    fun handle(rawInput: String): Result {
        val cleaned = clean(rawInput)
        if (cleaned.isBlank()) {
            return Result("Say or type a command — try \"play Blinding Lights\" or \"open YouTube\".")
        }

        // JAICF-style scenario/activator state machine first: the intents it
        // models route through a state+slot pattern (not a flat rule chain).
        routeScenario(cleaned)?.let { return it }

        // Conversation is tried first for the things it owns outright —
        // greetings, memory, arithmetic — because a phrase like "remember my
        // car is a Swift" would otherwise be swallowed by the "call" rule.
        // Teaching: "when I ask X say Y" / "the answer to X is Y".
        Regex(
            "^(?:when i (?:ask|say)|if i (?:ask|say)) (.+?)[,]? (?:say|answer|reply|tell me) (.+)$",
            RegexOption.IGNORE_CASE,
        ).find(cleaned)?.let { m ->
            memory.teach(m.groupValues[1], m.groupValues[2])
            return Result("Learned. Ask me that and I'll say so.")
        }
        Regex(
            "^(?:the )?answer to (.+?) is (.+)$", RegexOption.IGNORE_CASE,
        ).find(cleaned)?.let { m ->
            memory.teach(m.groupValues[1], m.groupValues[2])
            return Result("Learned.")
        }
        if (cleaned.matches(Regex("(what have you learned|what did i teach you).*"))) {
            val n = memory.taughtCount()
            return Result(
                if (n == 0) "Nothing yet. Teach me with \"when I ask … say …\"."
                else "You've taught me $n answer${if (n == 1) "" else "s"}.",
            )
        }

        // ---- locking the screen ----
        if (cleaned.matches(Regex("""(lock|lock the|lock my)( phone| screen| device)?\s*$""")) ||
            cleaned.matches(Regex("(go to sleep|sleep now|screen off|turn off the screen).*"))
        ) {
            return if (device.lock()) {
                Result("Locking now.")
            } else {
                Result(
                    "I need permission to lock the screen — Android only allows it " +
                        "for an app you've approved. Settings has the button.",
                    navigateTo = "settings",
                )
            }
        }

        if (cleaned.matches(Regex("""(unlock|unlock the|unlock my)( phone| screen| device)?\s*$"""))) {
            // No app can unlock a phone, and one that could would make the
            // lock worthless. The honest answer is to raise the prompt.
            return Result(
                if (device.isLocked) {
                    "I can bring up the unlock screen, but only your PIN, " +
                        "fingerprint or face can actually open it — that's the " +
                        "point of a lock."
                } else {
                    "It's already unlocked."
                },
            )
        }

        // ---- the assistant drives the shell's own look & feel ----
        osLookCommand(cleaned)?.let { return it }

        // Anything the user taught wins over every generic reply.
        memory.taught(rawInput)?.let { return Result(it) }

        // Things only this phone can answer — battery, storage, date, contacts.
        phone.answer(rawInput)?.let { return Result(it) }

        conversation.respond(rawInput, userName).let { if (it.handled) return Result(it.text) }

        for (rule in rules) {
            val m = rule.pattern.find(cleaned) ?: continue
            // group "arg" is the captured entity, when the rule defines one
            val arg = runCatching { m.groups["arg"]?.value }.getOrNull().orEmpty().trim()
            return rule.action(arg, cleaned)
        }

        // No verb matched — maybe the whole utterance is just an app name.
        findApp(cleaned)?.let { app ->
            return if (appRepo.launch(app.packageName)) Result("Opening ${app.label}.")
            else Result("I found ${app.label} but couldn't start it.")
        }

        return Result(
            "I didn't catch a command in \"$rawInput\".\n" +
                "Try \"play <song>\", \"open <app>\", \"call <number>\" or type \"help\".",
            unmatched = true,
        )
    }

    /**
     * Commands that change AxzoraOS's own appearance — day/night, the five
     * themes, and the animation level. Fires the UI-provided hooks and returns
     * a spoken confirmation; null if the phrase isn't a look-and-feel command.
     * All regex, so it works with no model, no network — instantly.
     */
    private fun osLookCommand(c: String): Result? {
        // Day / night first — "dark"/"night" are moods, not theme names.
        if (Regex("(dark mode|night mode|go dark|lights off|make it (dark|night)|turn on dark)").containsMatchIn(c)) {
            onSetDark?.invoke(true); return Result("Dark mode on.")
        }
        if (Regex("(light mode|day mode|lights on|make it (light|bright|day)|turn off dark)").containsMatchIn(c)) {
            onSetDark?.invoke(false); return Result("Light mode on.")
        }
        // A theme by name, but only when the sentence is clearly about the look.
        if (Regex("\\b(theme|mode|look|colou?r|style|skin|switch|change|set|use|make it)\\b").containsMatchIn(c)) {
            val themes = listOf("ember", "pitch", "midnight", "forest", "paper")
            themes.forEachIndexed { i, name ->
                if (Regex("\\b$name\\b").containsMatchIn(c)) {
                    onSetTheme?.invoke(i)
                    return Result("Switched to the ${name.replaceFirstChar { it.uppercase() }} theme.")
                }
            }
        }
        // Animations.
        if (Regex("(no|off|disable|turn off|stop|kill).{0,12}(animation|animations|motion|transition)").containsMatchIn(c)) {
            onSetAnim?.invoke(0); return Result("Animations off.")
        }
        if (Regex("(full|max|maximum|all|more|turn on|enable|smooth).{0,12}(animation|animations|motion)").containsMatchIn(c)) {
            onSetAnim?.invoke(2); return Result("Animations set to full.")
        }
        // Straight-to-a-screen shortcuts the rules list doesn't cover.
        if (Regex("(open|show|go to|launch).{0,12}(agent|ai hub|assistant hub)").containsMatchIn(c)) {
            return Result("Opening the AI Agents.", navigateTo = "agents")
        }
        if (Regex("(open|show|go to|launch).{0,12}(settings|preferences)").containsMatchIn(c)) {
            return Result("Opening Settings.", navigateTo = "settings")
        }
        if (Regex("(open|show|go to|launch).{0,12}(my )?(devices|phones|device hub)").containsMatchIn(c)) {
            return Result("Opening your devices.", navigateTo = "devices")
        }
        return null
    }

    /**
     * The full path: the phone answers what it can, and anything left over
     * goes to the model.
     *
     * Actions and memory are handled on-device first so they keep working with
     * no signal, and so "open YouTube" never costs a round trip. Only genuine
     * questions travel.
     */
    suspend fun ask(rawInput: String, history: List<Brain.Turn>): Result {
        val local = handle(rawInput)
        if (!local.unmatched) return local

        // Traffic / commute — run here (not in the rules) because it may need
        // the network, which handle() can't await. If no Directions key is set
        // it just opens the route in Maps, so it still works with no signal.
        if (Traffic.isTrafficLike(rawInput)) {
            val (from, to) = Traffic.parsePlaces(rawInput)
            val key = Traffic.directionsKey(context)
            val reply = if (key != null) Traffic.liveReply(context, from, to, key)
            else Traffic.syncReply(context, from, to)
            return Result(reply)
        }

        // Devices this account owns - answered from the live hub, since only
        // the server knows what else is online right now.
        deviceHub?.takeIf { deviceUid.isNotBlank() && looksLikeDeviceQuery(rawInput) }?.let { hub ->
            val devices = hub.list(deviceUid)
            if (devices.isNotEmpty()) {
                val online = devices.count { it.online }
                val parts = devices.joinToString(", ") { d ->
                    val where = if (d.online) "online" else "offline"
                    val batt = if (d.battery in 0..100) ", ${d.battery}%" else ""
                    "${d.name} ($where$batt)"
                }
                return Result("You have ${devices.size} device${if (devices.size == 1) "" else "s"}, " +
                    "$online online: $parts.")
            }
        }

        // Financial health — answered from the on-device FinanceAdvisor (no
        // network, no server): "how's my money", "my balance", "spending".
        if (looksLikeFinanceQuery(rawInput)) {
            val wallet = com.axzora.os.wallet.WalletStore(context)
            val balance = runCatching { wallet.currentBalance() }.getOrDefault(0.0)
            val txns = runCatching { wallet.state.first().txns }.getOrDefault(emptyList())
            val report = com.axzora.os.wallet.FinanceAdvisor.analyze(balance, txns)
            val verdict = when {
                report.score >= 80 -> "in great shape"
                report.score >= 60 -> "healthy"
                report.score >= 40 -> "okay, but could be tighter"
                else -> "spending nearly as fast as it comes in"
            }
            val summary = buildString {
                append("Your financial health is ${report.score.toInt()}/100 — $verdict. ")
                append("Balance ${"%.0f".format(report.balance)} HP. ")
                if (report.insights.isNotEmpty()) append(report.insights.first())
            }
            return Result(summary)
        }

        // Reputation / Human Value Score — read straight from the on-device store
        // so Mr Happy can answer "what's my score" without a model or network.
        if (looksLikeReputationQuery(rawInput)) {
            val rep = com.axzora.os.wallet.Reputation(context)
            val s = rep.summary()
            Perception.record(context, "agent", s.take(200))
            return Result(s)
        }

        // Contribution suggestions — recommend high-impact actions from categories.
        if (looksLikeSuggestionQuery(rawInput)) {
            val rep = com.axzora.os.wallet.Reputation(context)
            val m = rep.suggestMissions()
            val s = "Based on your profile, these create the most impact:\n" +
                m.mapIndexed { i, it -> "${i + 1}. $it" }.joinToString("\n")
            Perception.record(context, "agent", s.take(240))
            return Result(s)
        }

        // Happy Passport — portable contribution identity.
        if (looksLikePassportQuery(rawInput)) {
            val rep = com.axzora.os.wallet.Reputation(context)
            val s = rep.passport(userName)
            Perception.record(context, "agent", s.take(240))
            return Result(s)
        }

        // A multi-step job ("open WhatsApp, set a 10-minute timer and mute the
        // phone") gets planned and executed automatically rather than answered
        // in one shot. If the model is unreachable we just fall through to the
        // normal reply, so planning never blocks a plain question.
        if (taskEngine.wantsPlanning(rawInput)) {
            val plan = taskEngine.plan(rawInput)
            if (plan != null && plan.steps.isNotEmpty()) {
                val summary = taskEngine.execute(plan)
                Perception.record(context, "agent", summary.take(320))
                return Result(summary)
            }
        }

        val facts = memory.all().joinToString("; ") { "your ${it.subject} is ${it.value}" }
        // Real-time awareness: the model should know what's happening *now*, so
        // its answers aren't written in a vacuum. Time/date/battery/network are
        // read locally — nothing is uploaded, and no network call is made.
        val live = liveContext()
        // Record this turn into the perception buffer (so the model can see "what
        // just happened" on the next turn), and pull a snapshot of recent context
        // to fold into the prompt.
        Perception.record(context, "user", rawInput.take(200))
        val perception = Perception.snapshot(context)
        val learned = LearnedRules.lessons(context)

        // The phone's own model first: no network, nothing leaves the device,
        // and it works on a train. Only if there isn't one do we ask outside.
        localLlm?.takeIf { it.ready }?.let { llm ->
            llm.ask(
                prompt = rawInput,
                history = history,
                systemNote = (agentPersona?.let { "$it " } ?: "") + LocalLlm.SYSTEM_NOTE +
                    (if (userName.isNotBlank()) " You are speaking with $userName." else "") +
                    (if (facts.isNotBlank()) " Things they asked you to remember: $facts" else "") +
                    (if (live.isNotBlank()) " Right now: $live." else "") +
                    (if (perception.isNotBlank()) " $perception" else "") +
                    (if (learned.isNotBlank()) " $learned" else ""),
            )?.let { return Result(it) }
        }

        val reply = brain?.ask(
            history = history + Brain.Turn(rawInput, fromUser = true),
            userName = userName,
            memory = (agentPersona?.let { "$it " } ?: "") + facts +
                (if (live.isNotBlank()) "\nRight now: $live." else "") +
                (if (perception.isNotBlank()) "\n$perception" else "") +
                (if (learned.isNotBlank()) "\n$learned" else ""),
        )
        if (reply != null) {
            Perception.record(context, "agent", reply.take(200))
            return Result(reply)
        }

        // A question deserves an honest answer about why it went unanswered,
        // not "I didn't catch a command" — which reads as if it misheard.
        if (looksLikeAQuestion(rawInput)) {
            val missReply = when (brain?.lastMiss) {
                    Brain.Miss.NOT_CONFIGURED ->
                        "I can't answer that one yet. Install the on-device model in " +
                            "Settings and I'll answer open questions with no internet " +
                            "at all — or teach me directly: \"when I ask that, say …\"."
                    Brain.Miss.UNREACHABLE ->
                        "I couldn't reach the network for that. Install the on-device " +
                            "model in Settings and I won't need one. Anything on the " +
                            "phone still works — try \"open YouTube\" or \"battery\"."
                    else -> local.reply
                }
            Perception.record(context, "agent", missReply.take(200))
            return Result(missReply)
        }
        Perception.record(context, "agent", local.reply.take(200))
        return local
    }

    private fun looksLikeDeviceQuery(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("my device") || t.contains("my devices") ||
            t.contains("how many device") || t.contains("other phone") ||
            t.contains("other device") || t.contains("devices online") ||
            (t.contains("device") && (t.contains("online") || t.contains("battery")))
    }

    /** True for "how's my money / my balance / spending / financial health". */
    private fun looksLikeFinanceQuery(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("my money") || t.contains("how's my money") ||
            t.contains("my balance") || t.contains("financial health") ||
            t.contains("my spending") || t.contains("spending") ||
            t.contains("how much do i have") || t.contains("my finances")
    }

    /** True for "what's my reputation / score / impact / contribution". */
    private fun looksLikeReputationQuery(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("reputation") || t.contains("my score") ||
            t.contains("human value") || t.contains("my impact") ||
            t.contains("my contribution") || t.contains("hvs")
    }

    /** True for "suggest a mission / what should I contribute / what should i do". */
    private fun looksLikeSuggestionQuery(text: String): Boolean {
        val t = text.lowercase()
        return (t.contains("suggest") || t.contains("recommend")) &&
            (t.contains("mission") || t.contains("contribut") || t.contains("do")) ||
            t.contains("what should i contribute") || t.contains("what should i do")
    }

    /** True for "show my passport / my happy passport / my contribution identity". */
    private fun looksLikePassportQuery(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("passport") || t.contains("my identity") ||
            t.contains("contributor tier")
    }

    /**
     * A compact, always-available snapshot of "right now" on this phone, handed
     * to the model so its answers are grounded in the present rather than
     * generic. Reads only local, instant state — no network call, nothing leaves
     * the device. It's the real-time half of the assistant: open questions come
     * back aware of the time, the battery, and whether you're on Wi-Fi or mobile.
     */
    private fun liveContext(): String {
        val sb = StringBuilder()
        runCatching {
            sb.append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a")))
        }
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) sb.append("; battery ").append(pct).append('%')
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val ni = cm.activeNetworkInfo
            sb.append("; on ").append(if (ni != null && ni.isConnected) ni.typeName else "no network")
        }
        val h = Traffic.home(context)
        val w = Traffic.work(context)
        if (!h.isNullOrBlank() && !w.isNullOrBlank()) {
            sb.append("; your commute is $h \u2192 $w")
        }
        return sb.toString()
    }

    private fun looksLikeAQuestion(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.endsWith("?")) return true
        val openers = listOf(
            "what", "why", "how", "who", "when", "where", "which", "can you",
            "could you", "do you", "does", "is it", "are there", "tell me",
            "explain", "should i", "give me",
        )
        return openers.any { t.startsWith(it) }
    }

    // ------------------------------------------------------------- rules

    private class Rule(val pattern: Regex, val action: (arg: String, full: String) -> Result)

    private fun rule(regex: String, action: (String, String) -> Result) =
        Rule(Regex(regex, RegexOption.IGNORE_CASE), action)

    /** Order matters — the first match wins, so put specific rules first. */
    private val rules: List<Rule> by lazy {
        listOf(
            rule("^(hi|hello|hey|yo|good (morning|afternoon|evening|night))\\b") { _, _ ->
                Result("Hey! I'm Axzora. I can play music, open apps, set alarms, search, navigate, and more. Say \"help\" for the full list.")
            },
            rule("\\b(help|what can you do|commands|how do you work)\\b") { _, _ -> Result(HELP_TEXT) },

            // ---- commute: save home/work, then "traffic on my commute" ----
            rule("^(?:set |(?:my |the )?)?(home|work|office|house)\\s+(?:is|=|to)\\s+(?<arg>.+)$") { arg, full ->
                val place = Regex("(?:^|\\s)(home|work|office|house)\\b").find(full)?.groupValues?.get(1) ?: "home"
                Traffic.setAddress(context, place, arg.trim())
                Result("Saved \u2014 I'll remember your $place as ${arg.trim()}.")
            },
            rule("^(?:set|save|use)?\\s*(?:my )?(?:google )?traffic (?:api )?key\\s*(?:to|is|=)?\\s*(?<arg>\\S+)$") { arg, _ ->
                Traffic.setDirectionsKey(context, arg.trim())
                Result("Traffic key set \u2014 I'll give you live drive times now.")
            },

            // ---- autonomous watches (§14) ----
            rule("^(?:watch|monitor)\\s+(?<arg>\\S+)\\s+for\\s+(.+)$") { arg, full ->
                val k = Regex("for\\s+(.+)$", RegexOption.IGNORE_CASE).find(full)?.groupValues?.get(1)?.trim() ?: ""
                Result(Watches.add(context, arg.trim(), k))
            },
            rule("\\b(show|list)\\b.*\\b(watches?|monitors?)\\b") { _, _ -> listWatches() },
            rule("\\b(stop|cancel|remove)\\b.*\\bwatch(?:ing)?\\b.*\\s(.+)$") { a, _ ->
                val url = a.trim()
                Result(if (Watches.remove(context, url)) "Stopped watching $url." else "I couldn't find that watch.")
            },
            rule("\\b(clear|forget)\\b.*\\bwatches?\\b") { _, _ ->
                Watches.clear(context)
                Result("Cleared all watches.")
            },

            // ---- media: most specific first ----
            rule("^(?:play|put on|start playing)\\s+(?<arg>.+?)\\s+(?:on|in|using)\\s+youtube\\b") { a, _ -> youtubeSearch(a) },
            rule("^(?:play|put on|start playing)\\s+(?<arg>.+?)\\s+(?:on|in|using)\\s+spotify\\b") { a, _ -> spotifySearch(a) },
            rule("^(?:search|find|look up)\\s+(?<arg>.+?)\\s+(?:on|in)\\s+youtube\\b") { a, _ -> youtubeSearch(a) },
            rule("^(?:open|go to)\\s+youtube\\s+(?:and\\s+)?(?:play|search)\\s+(?<arg>.+)$") { a, _ -> youtubeSearch(a) },
            // Generic "play music" must beat the catch-all below, or it would
            // search for a track literally named "some music".
            rule("^(?:play|start)\\s+(?:some\\s+)?(?:music|songs?|a song)$") { _, _ -> playMedia("") },
            rule("^(?:play|put on|start playing)\\s+(?<arg>.+)$") { a, _ -> playMedia(a) },
            rule("\\b(pause|stop)\\b.*\\b(music|song|playback)\\b") { _, _ -> mediaKey(false) },
            rule("\\b(resume|continue)\\b.*\\b(music|song|playback)\\b") { _, _ -> mediaKey(true) },
            rule("\\b(next|skip)\\b.*\\b(song|track)\\b") { _, _ -> skipTrack() },

            // ---- apps ----
            // Recent-apps queries must precede the generic "open <app>" rule,
            // or "open recent apps" would be taken as an app literally called
            // that.
            rule("\\b(recent|recently|last)\\b.*\\b(apps?|opened|used|launched)\\b") { _, _ -> recentApps() },
            rule("\\b(what|which) apps (did i|have i|were) (use|open|launch|used|opened|launched)\\b.*") { _, _ -> recentApps() },
            rule("^(?:open|launch|start|run|go to)\\s+(?<arg>.+)$") { a, _ -> openApp(a) },
            rule("^(?:uninstall|remove)\\s+(?<arg>.+)$") { a, _ -> appSettings(a) },
            rule("^(?:app info|details) (?:for|of)\\s+(?<arg>.+)$") { a, _ -> appSettings(a) },

            // ---- communication ----
            // Learn a contact's number so "message Mom" works forever, then
            // route calls/messages/whatsapp to the real executors.
            rule("^(?:remember|save|note)\\s+(?:that\\s+)?(?<arg>.+?)\\s+(?:is|has|number is|=)\\s+(\\+?[\\d\\s\\-]{6,})$") { arg, full ->
                val num = Regex("(\\+?[\\d\\s\\-]{6,})$").find(full)
                    ?.groupValues?.get(1)?.filter { it.isDigit() || it == '+' } ?: ""
                val name = arg.trim()
                if (name.isNotEmpty() && num.length >= 6) {
                    memory.remember("contact:" + name.lowercase(), num)
                    Result("Remembered $name ($num). Next time just say \"message $name\" or \"call $name\".")
                } else {
                    Result("Tell me the number, like \"remember Mom is 9898989898\".")
                }
            },
            rule("^(?:call|dial|phone)\\s+(?<arg>.+)$") { a, _ -> dial(a) },
            rule("^(?:whatsapp|send (?:a )?whatsapp (?:message )?to)\\s+(?<arg>.+)$") { a, _ -> whatsapp(a) },
            rule("^(?:message|text|send (?:a )?message to)\\s+(?<arg>.+)$") { a, _ -> message(a) },

            // ---- Axzora Pay: opens the Pay screen; the user's own UPI app
            // completes the transfer after they confirm. Never automatic.
            rule("^(?:pay|send)\\s+(?:₹|rs\\.?\\s*)?\\d+(?:\\.\\d+)?\\b.*$") { _, _ ->
                Result("Opening Axzora Pay — fill in who to pay and confirm in your UPI app.", navigateTo = "pay")
            },
            rule("^(?:wallet|happy paisa|paisa|my (?:balance|coins)|balance|open wallet)\\b.*$") { _, _ ->
                Result("Opening your Happy Paisa wallet.", navigateTo = "wallet")
            },
            rule("\\b(coupons?|offers?|deals?)\\b") { _, _ ->
                Result("Here are your Happy Paisa offers.", navigateTo = "wallet")
            },
            rule("^(?:pay\\b|send money\\b|axzora pay\\b|open pay\\b).*$") { _, _ ->
                Result("Opening Axzora Pay.", navigateTo = "pay")
            },
            rule("^(?:open\\s+)?(?:messages|family chat|private chat)$") { _, _ ->
                Result("Opening your private messages.", navigateTo = "messages")
            },
            rule("^(?:email|mail)\\s+(?<arg>.+)$") { a, _ -> email(a) },

            // ---- clock ----
            rule("\\balarm\\b") { _, f -> setAlarm(f) },
            rule("\\btimer\\b") { _, f -> setTimer(f) },
            rule("\\b(stopwatch)\\b") { _, _ -> simple(AlarmClock.ACTION_SHOW_TIMERS, "Opening the clock.") },

            // ---- device ----
            rule("\\b(flash ?light|torch)\\b") { _, f -> toggleTorch(!f.contains("off")) },
            rule("\\bvolume\\b.*\\b(up|increase|louder)\\b") { _, _ -> volume(up = true) },
            rule("\\bvolume\\b.*\\b(down|decrease|lower|quieter)\\b") { _, _ -> volume(up = false) },
            rule("\\b(mute|silent)\\b") { _, _ -> mute() },
            rule("\\b(wi-?fi)\\b") { _, _ -> simple(Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi settings.") },
            rule("\\bbluetooth\\b") { _, _ -> simple(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.") },
            rule("\\b(brightness|display) settings?\\b") { _, _ -> simple(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.") },
            rule("\\bsettings\\b") { _, _ -> simple(Settings.ACTION_SETTINGS, "Opening Android settings.") },
            rule("\\bbattery\\b") { _, _ -> Result(batteryReply()) },

            // ---- info ----
            rule("\\b(what'?s? the time|what time is it|tell me the time|current time)\\b") { _, _ ->
                Result("It's ${now("h:mm a")}.")
            },
            rule("\\b(what'?s? the date|what day is it|today'?s date)\\b") { _, _ ->
                Result("Today is ${now("EEEE, d MMMM yyyy")}.")
            },
            rule("\\b(time)\\b(?!r)") { _, _ -> Result("It's ${now("h:mm a")}.") },
            rule("\\bdate\\b") { _, _ -> Result("Today is ${now("EEEE, d MMMM yyyy")}.") },
            rule("\\bweather\\b") { _, f -> webSearch(if (f.contains(" in ")) f else "weather today") },

            // ---- camera ----
            rule("\\b(take|shoot|snap)\\b.*\\b(photo|picture|selfie)\\b") { _, _ ->
                simple(MediaStore.ACTION_IMAGE_CAPTURE, "Opening the camera.")
            },
            rule("\\brecord\\b.*\\bvideo\\b") { _, _ ->
                simple(MediaStore.ACTION_VIDEO_CAPTURE, "Opening the video camera.")
            },

            // ---- web / maps ----
            rule("^(?:search|google|look up|find)(?: for)?\\s+(?<arg>.+)$") { a, _ -> webSearch(a) },
            rule("^(?:navigate to|directions to|take me to|drive to)\\s+(?<arg>.+)$") { a, _ -> navigate(a) },
            rule("^(?:where is)\\s+(?<arg>.+)$") { a, _ -> navigate(a) },
            rule("\\b(where am i|my location|show my location|track (me|us|family))\\b") { _, _ ->
                Result("Opening your location map.", navigateTo = "location")
            },
            rule("^(?:translate)\\s+(?<arg>.+)$") { a, _ -> webSearch("translate $a") },
            rule("\\bnews\\b") { _, _ -> webSearch("latest news") },
        )
    }

    // ------------------------------------------------------------ actions

    /**
     * "play <query>" — try, in order:
     *   1. the Android media-search intent (Spotify / YT Music actually autoplay)
     *   2. the YouTube app's search screen
     *   3. YouTube on the web
     */
    private fun playMedia(query: String): Result {
        if (query.isBlank()) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, "")
            if (fire(i)) return Result("Starting music.")
            return openAppByAlias("youtube") ?: Result("I couldn't find a music app.")
        }

        val media = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (fire(media)) return Result("Playing \"$query\".")
        return youtubeSearch(query)
    }

    /** Opens YouTube on a search for [query]. */
    private fun youtubeSearch(query: String): Result {
        if (query.isBlank()) return Result("What should I play?")

        val inApp = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PKG)
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fire(inApp)) return Result("Searching YouTube for \"$query\" — tap the first result to play.")

        val web = Intent(
            Intent.ACTION_VIEW,
            ("https://www.youtube.com/results?search_query=" + Uri.encode(query)).toUri()
        )
        if (fire(web)) return Result("Opening YouTube for \"$query\".")
        return Result("I couldn't open YouTube.")
    }

    private fun spotifySearch(query: String): Result {
        val deep = Intent(Intent.ACTION_VIEW, "spotify:search:${Uri.encode(query)}".toUri())
        if (fire(deep)) return Result("Searching Spotify for \"$query\".")
        return playMedia(query)
    }

    private fun mediaKey(play: Boolean): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = if (play) android.view.KeyEvent.KEYCODE_MEDIA_PLAY else android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return Result(if (play) "Resumed." else "Paused.")
    }

    private fun skipTrack(): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return Result("Skipping to the next track.")
    }

    private fun openApp(query: String): Result {
        if (query.isBlank()) return Result("Which app should I open?")
        val app = findApp(query)
            ?: return Result("I couldn't find an app called \"$query\". Check the App drawer for its exact name.")
        return if (appRepo.launch(app.packageName)) Result("Opening ${app.label}.")
        else Result("I found ${app.label} but couldn't start it.")
    }

    private fun openAppByAlias(alias: String): Result? =
        findApp(alias)?.takeIf { appRepo.launch(it.packageName) }?.let { Result("Opening ${it.label}.") }

    /** Answers "recent apps" — the launches recorded by the launcher itself. */
    private fun recentApps(): Result {
        val used = appRepo.recentPackages.value
            .mapNotNull { pkg -> apps.find { it.packageName == pkg } }
        if (used.isEmpty()) {
            return Result("You haven't opened anything from the home screen yet — open an app and I'll remember it.")
        }
        val names = used.take(6).joinToString(", ") { it.label }
        val more = if (used.size > 6) "…" else ""
        return Result("Recently used: $names$more.")
    }

    private fun listWatches(): Result {
        val list = Watches.list(context)
        if (list.isEmpty()) return Result("You aren't watching anything. Say \"watch <url> for <keyword>\".")
        return Result(
            "Watching:\n" + list.joinToString("\n") {
                val st = if (it.notified) "found ✓" else "waiting"
                "• ${it.url} — \"${it.keyword}\" ($st)"
            }
        )
    }

    private fun appSettings(query: String): Result {
        val app = findApp(query) ?: return Result("I couldn't find \"$query\".")
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.packageName}".toUri())
        return if (fire(i)) Result("Opening app info for ${app.label}.")
        else Result("Couldn't open settings for ${app.label}.")
    }

    private fun dial(who: String): Result {
        val number = resolveNumber(who)
        if (number.isBlank()) {
            return Result("I couldn't find a number for \"$who\". Add them as a contact, or say \"call\" with the number.")
        }
        return if (fire(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()))) Result("Dialing $number.")
        else Result("Couldn't open the dialler.")
    }

    private fun message(target: String): Result {
        val (recipient, body) = splitRecipientBody(target)
        val number = resolveNumber(recipient)
        if (number.isBlank()) {
            return Result("I couldn't find \"$recipient\" to message. Add them as a contact, or give me their number. (WhatsApp? say \"WhatsApp $recipient\".)")
        }
        val i = Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri())
            .apply { if (body.isNotBlank()) putExtra("sms_body", body) }
        if (!fire(i)) return Result("Couldn't open messaging for $recipient.")
        val a11y = com.axzora.happyreddy.HappyReddyA11y.get()
        return if (a11y != null && body.isNotBlank()) {
            sendScope.launch { runCatching { a11y.autoSend(body) } }
            Result("Sending to $recipient…")
        } else {
            Result("Composing a message to $recipient" +
                (if (body.isNotBlank()) " — tap Send" else "") + ".")
        }
    }

    /**
     * Opens a WhatsApp chat for a contact with the message pre-filled. Resolves
     * the name to a number (from the address book or the learned contacts) and
     * deep-links into the chat via wa.me — the reliable no-accessibility path.
     */
    private fun whatsapp(target: String): Result {
        val (recipient, body) = splitRecipientBody(target)
        val number = resolveNumber(recipient)
        if (number.isBlank()) {
            return Result("I couldn't find \"$recipient\" on WhatsApp. Add them as a contact, or give me their number.")
        }
        val wa = "https://wa.me/${number.replace("+", "")}" +
            (if (body.isNotBlank()) "?text=${Uri.encode(body)}" else "")
        if (!fire(Intent(Intent.ACTION_VIEW, wa.toUri()))) {
            return Result("WhatsApp isn't installed on this phone.")
        }
        // The chat is open. If Mr Happy's accessibility is enabled, actually
        // type the message and tap Send (off the UI thread so it never freezes).
        // Otherwise be honest: the message is pre-filled but needs the user's
        // tap to go out.
        val a11y = com.axzora.happyreddy.HappyReddyA11y.get()
        return if (a11y != null && body.isNotBlank()) {
            sendScope.launch { runCatching { a11y.autoSend(body) } }
            Result("Sending to $recipient on WhatsApp…")
        } else {
            Result("Opened WhatsApp for $recipient" +
                (if (body.isNotBlank()) " — message ready, tap Send" else "") +
                ". Enable Mr Happy's accessibility (Settings → Accessibility) to auto-send.")
        }
    }

    /**
     * Splits a "recipient|message" (or "recipient saying message", or
     * "recipient: message") target into its recipient and body.
     */
    private fun splitRecipientBody(target: String): Pair<String, String> {
        val t = target.trim()
        val pipe = t.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (pipe.size >= 2) return pipe[0] to pipe.subList(1, pipe.size).joinToString("|")
        Regex("^(.+?)\\s+(?:saying|with message|and say)\\s+(.+)$").find(t)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        Regex("^([^:]+):\\s*(.+)$").find(t)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        return t to ""
    }

    /**
     * Turns a recipient into a dialable number: a bare number works as-is; a
     * name is looked up in learned contacts first, then the address book. Any
     * successful look-up is remembered so the next time is instant and never
     * repeats the "who?" step.
     */
    private fun resolveNumber(recipient: String): String {
        val raw = recipient.trim()
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.length >= 3) return digits
        val mem = memory.recall("contact:" + raw.lowercase())
        if (!mem.isNullOrBlank()) return mem.filter { it.isDigit() || it == '+' }
        val (name, number) = contacts.resolve(raw)
        if (!number.isNullOrBlank()) {
            memory.remember("contact:" + raw.lowercase(), number)
            return number.filter { it.isDigit() || it == '+' }
        }
        return ""
    }

    private fun email(target: String): Result {
        val i = Intent(Intent.ACTION_SENDTO, "mailto:${target.filter { !it.isWhitespace() }}".toUri())
        return if (fire(i)) Result("Composing an email.") else Result("Couldn't open an email app.")
    }

    private fun setAlarm(input: String): Result {
        val m = Regex("(\\d{1,2})[:. ]?(\\d{2})?\\s*(am|pm)?").find(input)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        var label = "your alarm"
        if (m != null) {
            var hour = m.groupValues[1].toIntOrNull() ?: 7
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            when (m.groupValues[3]) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour)
            intent.putExtra(AlarmClock.EXTRA_MINUTES, minute)
            label = "an alarm for %02d:%02d".format(hour, minute)
        }
        return if (fire(intent)) Result("Setting $label.") else Result("Couldn't open the clock app.")
    }

    private fun setTimer(input: String): Result {
        val h = Regex("(\\d+)\\s*h").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("(\\d+)\\s*min").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val secs = Regex("(\\d+)\\s*sec").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var total = h * 3600 + mins * 60 + secs
        if (total == 0) {
            // "timer 5" with no unit -> assume minutes
            total = (Regex("timer\\s+(\\d+)").find(input)?.groupValues?.get(1)?.toIntOrNull() ?: 5) * 60
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (fire(intent)) Result("Timer set for ${humanDuration(total)}.")
        else Result("Couldn't set a timer.")
    }

    private fun toggleTorch(turnOn: Boolean): Result = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Result("This device has no flashlight.")
        cm.setTorchMode(id, turnOn)
        Result(if (turnOn) "Flashlight on." else "Flashlight off.")
    }.getOrElse { Result("Couldn't control the flashlight: ${it.message}") }

    private fun volume(up: Boolean): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        return Result(if (up) "Volume up." else "Volume down.")
    }

    private fun mute(): Result {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return Result("Muted.")
    }

    /**
     * Runs a single named action taken from a task plan. Normalises the name
     * (case, underscores, spaces) and hands it to the real executor. Returns
     * null for actions the phone doesn't have, so the planner can report
     * honestly instead of pretending it ran.
     */
    fun runAction(name: String, arg: String): Result? {
        val a = name.trim().lowercase().replace("_", "").replace(" ", "")
        return when (a) {
            "openapp", "open" -> if (arg.isNotBlank()) openApp(arg) else null
            "settimer", "timer" -> if (arg.isNotBlank()) setTimer(arg) else null
            "setalarm", "alarm" -> if (arg.isNotBlank()) setAlarm(arg) else null
            "flashlight", "torch", "flashlighton" -> toggleTorch(true)
            "flashlightoff", "torchoff" -> toggleTorch(false)
            "volumeup" -> volume(true)
            "volumedown" -> volume(false)
            "mute", "silent" -> mute()
            "websearch", "search" -> if (arg.isNotBlank()) webSearch(arg) else null
            "navigate", "maps" -> if (arg.isNotBlank()) navigate(arg) else null
            "dial", "call" -> if (arg.isNotBlank()) dial(arg) else null
            "message", "sms" -> if (arg.isNotBlank()) message(arg) else null
            "whatsapp", "whatsappmessage" -> if (arg.isNotBlank()) whatsapp(arg) else null
            "email", "mail" -> if (arg.isNotBlank()) email(arg) else null
            "play", "playmedia" -> if (arg.isNotBlank()) playMedia(arg) else null
            "youtube" -> if (arg.isNotBlank()) youtubeSearch(arg) else null
            "spotify" -> if (arg.isNotBlank()) spotifySearch(arg) else null
            "skiptrack", "next" -> skipTrack()
            "recentapps" -> recentApps()
            else -> null
        }
    }

    /**
     * One model call for the planner. On-device model first (works on a train,
     * nothing leaves the phone); only if there isn't one do we ask the server.
     * Mirrors the priority the main reply path uses.
     */
    private suspend fun answerModel(prompt: String): String? {
        val facts = memory.all().joinToString("; ") { "your ${it.subject} is ${it.value}" }
        val contactNote = contacts.names().run {
            if (isEmpty()) "" else " The user's contacts are: ${joinToString(", ")}."
        }
        localLlm?.takeIf { it.ready }?.let { llm ->
            llm.ask(
                prompt = prompt,
                history = emptyList(),
                systemNote = (agentPersona?.let { "$it " } ?: "") + LocalLlm.SYSTEM_NOTE +
                    (if (facts.isNotBlank()) " Things they told you: $facts." else "") + contactNote,
            )?.let { return it }
        }
        return brain?.ask(
            history = listOf(Brain.Turn(prompt, fromUser = true)),
            userName = userName,
            memory = (agentPersona?.let { "$it " } ?: "") + facts + contactNote,
        )
    }

    private fun batteryReply(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) "Battery is at $pct%." else "I can't read the battery level right now."
    }

    private fun webSearch(query: String): Result {
        if (query.isBlank()) return Result("What should I search for?")
        val i = Intent(Intent.ACTION_VIEW, ("https://www.google.com/search?q=" + Uri.encode(query)).toUri())
        return if (fire(i)) Result("Searching for \"$query\".") else Result("Couldn't open a browser.")
    }

    private fun navigate(place: String): Result {
        if (place.isBlank()) return Result("Where do you want to go?")
        val i = Intent(Intent.ACTION_VIEW, ("geo:0,0?q=" + Uri.encode(place)).toUri())
        if (fire(i)) return Result("Getting directions to $place.")
        return webSearch("directions to $place")
    }

    private fun simple(action: String, reply: String): Result =
        if (fire(Intent(action))) Result(reply) else Result("That isn't available on this device.")

    // ------------------------------------------------------- app matching

    /**
     * Forgiving app lookup. Tries, in order: exact label, normalised exact,
     * alias table, prefix, substring (both directions), then a bounded
     * edit-distance match so mild speech errors still resolve.
     */
    fun findApp(rawQuery: String): AppInfo? {
        val list = apps
        if (list.isEmpty() || rawQuery.isBlank()) return null

        val q = rawQuery.trim()
        val qn = q.lowercase().filter { it.isLetterOrDigit() }
        if (qn.isEmpty()) return null

        list.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.normalized == qn }?.let { return it }

        // Alias: "yt" -> "youtube", "insta" -> "instagram", ...
        ALIASES[qn]?.let { target ->
            list.firstOrNull { it.normalized == target }?.let { return it }
            list.firstOrNull { it.normalized.startsWith(target) }?.let { return it }
        }

        list.firstOrNull { it.normalized.startsWith(qn) }?.let { return it }
        list.firstOrNull { it.normalized.contains(qn) }?.let { return it }
        list.firstOrNull { qn.contains(it.normalized) && it.normalized.length >= 3 }?.let { return it }

        // Fuzzy: allow ~1 edit per 4 characters, min 1, max 3.
        val budget = (qn.length / 4).coerceIn(1, 3)
        return list
            .map { it to editDistance(qn, it.normalized, budget + 1) }
            .filter { it.second <= budget }
            .minByOrNull { it.second }
            ?.first
    }

    /** Levenshtein distance, abandoned early once it exceeds [limit]. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    // ------------------------------------------------------------ helpers

    /** Lowercase, strip punctuation and polite filler, collapse whitespace. */
    private fun clean(raw: String): String {
        var s = raw.lowercase().trim()
        // Apostrophes are deleted, not spaced out, so "what's" -> "whats"
        // instead of splitting into two tokens.
        s = s.replace("'", "").replace("’", "")
        s = s.replace(Regex("[?!.,;:\"]"), " ")
        s = s.replace(Regex("^(hey |ok |okay )?(axzora|assistant)\\b"), " ")
        s = s.replace(Regex("\\b(please|can you|could you|would you|i want to|i wanna|for me|now)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Starts an activity, returning false if nothing can handle the intent. */
    private fun fire(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun humanDuration(sec: Int): String = when {
        sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        sec >= 60 -> "${sec / 60} minute${if (sec / 60 == 1) "" else "s"}"
        else -> "$sec seconds"
    }

    private fun now(pattern: String): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern))

    companion object {
        private const val YOUTUBE_PKG = "com.google.android.youtube"

        /** Spoken/typed shorthand -> normalised app label. */
        private val ALIASES = mapOf(
            "yt" to "youtube", "youtub" to "youtube", "utube" to "youtube",
            "ytmusic" to "youtubemusic", "ytm" to "youtubemusic",
            "insta" to "instagram", "ig" to "instagram",
            "wa" to "whatsapp", "watsapp" to "whatsapp", "whatsap" to "whatsapp",
            "fb" to "facebook", "messenger" to "messenger",
            "gmail" to "gmail", "mail" to "gmail",
            "maps" to "maps", "gmaps" to "maps", "googlemaps" to "maps",
            "pics" to "photos", "gallery" to "photos", "gallary" to "photos",
            "chrome" to "chrome", "browser" to "chrome",
            "calc" to "calculator", "calender" to "calendar",
            "settings" to "settings", "setting" to "settings",
            "playstore" to "playstore", "store" to "playstore",
            "snap" to "snapchat", "tg" to "telegram",
            "x" to "x", "twitter" to "x",
        )

        private const val HELP_TEXT =
            "Here's what I can do, all on-device:\n\n" +
                "MUSIC\n" +
                "• \"play Blinding Lights\"\n" +
                "• \"play <song> on YouTube\" / \"on Spotify\"\n" +
                "• \"pause music\" · \"next song\"\n\n" +
                "APPS\n" +
                "• \"open YouTube\" — or just say the app name\n" +
                "• \"recent apps\" — what you've opened lately\n" +
                "• \"app info for Chrome\"\n\n" +
                "CONTACT\n" +
                "• \"call 9876543210\" · \"message 9876543210\"\n\n" +
                "DEVICE\n" +
                "• \"set alarm 7:30 am\" · \"timer 10 min\"\n" +
                "• \"flashlight on/off\" · \"volume up\" · \"mute\"\n" +
                "• \"wifi\" · \"bluetooth\" · \"settings\" · \"battery\"\n\n" +
                "INFO & PLACES\n" +
                "• \"what's the time / date\" · \"weather\"\n" +
                "• \"search <anything>\" · \"navigate to <place>\"\n" +
                "• \"where am I\" — your location map\n" +
                "• \"traffic from Kothapet to Secunderabad\" — or \"traffic on my commute\"\n" +
                "  after \"my home is …\" / \"my work is …\""
    }
}
