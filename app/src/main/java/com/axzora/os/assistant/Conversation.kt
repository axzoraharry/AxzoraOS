package com.axzora.os.assistant

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.random.Random

/**
 * The talking half of the assistant.
 *
 * [CommandEngine] answers instructions — open this, call that. This handles
 * everything else: greetings, questions about itself, arithmetic, and the
 * remember/recall vocabulary that gives it continuity between conversations.
 *
 * It is rule-based and runs entirely on the phone. That is a deliberate
 * trade: it will never hold an open-ended conversation the way a cloud model
 * does, but it costs nothing, works with no signal, and no word of it leaves
 * the device. Where it does not understand, it says so rather than inventing
 * an answer.
 */
class Conversation(private val memory: Memory) {

    data class Reply(val text: String, val handled: Boolean)

    private fun pick(vararg options: String) = options[Random.nextInt(options.size)]

    fun respond(raw: String, userName: String): Reply {
        // Matching happens on the lowered text, but values are pulled from the
        // original so "KA05 1234" doesn't come back as "ka05 1234".
        val original = raw.trim().trim('.', '!', '?', ' ')
        val t = original.lowercase()
        if (t.isBlank()) return Reply("", false)

        val name = userName.trim().takeIf { it.isNotBlank() }

        // ---- memory ----

        Regex("^remember (?:that )?(?:my |the )?(.+?) (?:is|are|=) (.+)$", RegexOption.IGNORE_CASE)
            .find(original)?.let { m ->
            val subject = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            memory.remember(subject, value)
            return Reply("Got it — I'll remember your $subject is $value.", true)
        }

        Regex("^(?:what(?:'s| is) )?my (.+?)\\??$").find(t)?.let { m ->
            val subject = m.groupValues[1]
            val known = memory.recall(subject)
            if (known != null) return Reply("Your $subject is $known.", true)
            // Fall through: "my location" and similar are commands, not recall.
            if (subject !in listOf("location", "balance", "id", "name", "phone")) {
                return Reply("You haven't told me your $subject yet. Say \"remember my $subject is …\".", true)
            }
        }

        Regex("^forget (?:my |the )?(.+)$").find(t)?.let { m ->
            val ok = memory.forget(m.groupValues[1])
            return Reply(
                if (ok) "Forgotten." else "I wasn't holding anything about that.",
                true,
            )
        }

        if (t in listOf("what do you remember", "what do you know", "your memory")) {
            val facts = memory.all()
            return Reply(
                if (facts.isEmpty()) "Nothing yet. Tell me something with \"remember …\"."
                else "I'm holding:\n" + facts.take(8).joinToString("\n") { "• your ${it.subject} is ${it.value}" },
                true,
            )
        }

        // ---- who and what ----

        if (t.matches(Regex("(who are you|what are you|your name).*"))) {
            return Reply(
                "I'm Axzora — the assistant built into this phone's home screen. " +
                    "I work without internet, so nothing you say to me is sent anywhere.",
                true,
            )
        }

        if (t.matches(Regex("(what can you do|help|what do you do|how do you work).*"))) {
            return Reply(
                "I can open apps, call and message people, send Happy Paisa, show your " +
                    "family's map, set alarms and timers, do sums, and remember things you " +
                    "tell me. Try \"open YouTube\", \"remember my bike number is KA05 1234\", " +
                    "or just talk to me.",
                true,
            )
        }

        if (t.contains("are you real") || t.contains("are you human") || t.contains("are you ai")) {
            return Reply(
                "I'm a program, not a person — rules running on this phone rather than " +
                    "a mind. I'd rather say that plainly than pretend otherwise.",
                true,
            )
        }

        // ---- greetings and small talk ----

        if (t.matches(Regex("(hi|hey|hello|yo|hola|namaste|good (morning|afternoon|evening|night))\\b.*"))) {
            val greeting = timeGreeting()
            return Reply(
                if (name != null) "$greeting, $name. What do you need?"
                else "$greeting. What do you need?",
                true,
            )
        }

        if (t.matches(Regex("(how are you|how's it going|how do you do|kaise ho|wassup|what's up).*"))) {
            return Reply(
                pick(
                    "Running fine and fully offline. What can I do?",
                    "All good here. What do you need?",
                    "Working away. What's next?",
                ),
                true,
            )
        }

        if (t.matches(Regex("(thanks|thank you|thx|shukriya|dhanyavad|nice|great|good job|well done).*"))) {
            return Reply(pick("Any time.", "Happy to help.", "You're welcome."), true)
        }

        if (t.matches(Regex("(bye|goodbye|good night|see you|later).*"))) {
            return Reply(
                if (name != null) "See you, $name." else "See you.",
                true,
            )
        }

        if (t.matches(Regex("(sorry|my bad|oops).*"))) {
            return Reply("No need — what would you like to do?", true)
        }

        if (t.contains("joke")) {
            return Reply(
                pick(
                    "I'd tell you a UDP joke, but you might not get it.",
                    "There are 10 kinds of people: those who read binary and those who don't.",
                    "I asked the phone for its opinion. It said it was still processing.",
                ),
                true,
            )
        }

        if (t.contains("i love you") || t.contains("love you")) {
            return Reply("That's kind. I'm here whenever you need something.", true)
        }

        if (t.matches(Regex("(who made you|who built you|who created you).*"))) {
            return Reply("I was built for Axzora OS, and I run entirely on this phone.", true)
        }

        // ---- arithmetic ----

        maths(t)?.let { return Reply(it, true) }

        // ---- nothing matched ----
        return Reply("", false)
    }

    /** Handles "what is 12 x 7" and the plain "12 + 7" form. */
    private fun maths(t: String): String? {
        val cleaned = t
            .replace("what is", "").replace("what's", "").replace("calculate", "")
            .replace("plus", "+").replace("minus", "-")
            .replace("times", "*").replace("multiplied by", "*").replace(" x ", " * ")
            .replace("divided by", "/").trim()

        val m = Regex("""^(-?\d+(?:\.\d+)?)\s*([+\-*/])\s*(-?\d+(?:\.\d+)?)$""").find(cleaned)
            ?: return null

        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[3].toDoubleOrNull() ?: return null

        val result = when (m.groupValues[2]) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> {
                if (abs(b) < 1e-12) return "You can't divide by zero."
                a / b
            }
            else -> return null
        }
        // Integers should read as integers rather than "35.0".
        return if (abs(result - result.toLong()) < 1e-9) "${result.toLong()}"
        else "%.4f".format(result).trimEnd('0').trimEnd('.')
    }

    private fun timeGreeting(): String = when (LocalDateTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
}
