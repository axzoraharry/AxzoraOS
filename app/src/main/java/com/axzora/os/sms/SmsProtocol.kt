package com.axzora.os.sms

/**
 * A compact, dependency-free wire protocol for Mr Happy SMS messages.
 *
 * Two message kinds:
 *   HP     — a Happy Paisa value transfer ("offline economy over SMS").
 *   IMPACT — a mission/impact verification ("MISSION COMPLETE / ID / LOCATION /
 *            REWARD") that the AI can turn into a reward.
 *
 * The encoding is deliberately simple, versioned with a head marker, so a real
 * SmsGateway (send via SmsManager, receive via an SMS_RECEIVED BroadcastReceiver)
 * can emit and parse these without a JSON parser. Keep field order + escaping in
 * sync with the encoder; the codec is the single source of truth for the format.
 */
object SmsProtocol {

    const val MARKER = "MRHAPPY"
    private const val SEP = "|"

    /** A parsed Mr Happy SMS message. */
    sealed class Message {
        data class Hp(
            val amount: Double,
            val toId: String,
            val fromId: String,
            val note: String,
        ) : Message()

        data class Impact(
            val missionId: String,
            val status: String,   // e.g. "COMPLETE"
            val location: String,
            val reward: Double,
        ) : Message()

        object Unknown : Message()
    }

    /** Encode a Happy Paisa transfer message. */
    fun encodeHp(amount: Double, toId: String, fromId: String = "", note: String = ""): String =
        listOf(MARKER, "HP", amount.toString(), esc(toId), esc(fromId), esc(note)).joinToString(SEP)

    /** Encode an impact/mission verification message. */
    fun encodeImpact(missionId: String, status: String, location: String, reward: Double): String =
        listOf(MARKER, "IMPACT", esc(missionId), esc(status), esc(location), reward.toString()).joinToString(SEP)

    /** Parse any Mr Happy SMS message into its typed form, or [Message.Unknown]. */
    fun parse(text: String): Message {
        if (text.isBlank()) return Message.Unknown
        val parts = text.split(SEP)
        if (parts.size < 3 || parts[0].trim() != MARKER) return Message.Unknown
        return when (parts[1].trim().uppercase()) {
            "HP" -> if (parts.size >= 6) {
                Message.Hp(
                    amount = parts[2].trim().toDoubleOrNull() ?: 0.0,
                    toId = unesc(parts[3]),
                    fromId = unesc(parts[4]),
                    note = unesc(parts.getOrElse(5) { "" }),
                )
            } else Message.Unknown

            "IMPACT" -> if (parts.size >= 6) {
                Message.Impact(
                    missionId = unesc(parts[2]),
                    status = unesc(parts[3]).uppercase(),
                    location = unesc(parts[4]),
                    reward = parts[5].trim().toDoubleOrNull() ?: 0.0,
                )
            } else Message.Unknown

            else -> Message.Unknown
        }
    }

    /** Escape the '|' separator and backslash in a free-text field. */
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace(SEP, "\\u007C")

    private fun unesc(s: String): String =
        s.replace("\\u007C", SEP).replace("\\\\", "\\")
}
