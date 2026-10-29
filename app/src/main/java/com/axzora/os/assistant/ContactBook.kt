package com.axzora.os.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Reads the on-device address book into a name→number map, so "message Mom",
 * "call Grandma" or "WhatsApp John" can resolve to an actual number instead of
 * opening an empty compose screen.
 *
 * Nothing is uploaded: the map lives only in this process and is rebuilt from
 * the system Contacts provider. If READ_CONTACTS isn't granted the map is
 * empty and callers degrade gracefully (they ask for the number instead of
 * guessing).
 */
class ContactBook(private val context: Context) {

    private var cache: List<Pair<String, String>>? = null

    fun hasPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Name → phone number for every contact that has a number. */
    fun all(): List<Pair<String, String>> {
        cache?.let { return it }
        val list = if (!hasPermission()) emptyList() else runCatching {
            val resolver = context.contentResolver
            val out = mutableListOf<Pair<String, String>>()
            val cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null,
            )
            cursor?.use { c ->
                val idI = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameI = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getString(idI) ?: continue
                    val name = c.getString(nameI) ?: continue
                    val num = phone(id)
                    if (!num.isNullOrBlank()) out.add(name to num)
                }
            }
            out
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    private fun phone(contactId: String): String? = runCatching {
        val resolver = context.contentResolver
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(contactId), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Matches a spoken name against the address book. Returns a [Pair] of the
     * best-matching contact name and its number, or (input, null) when no
     * contact matches. Matching is case/space/pipe-insensitive.
     */
    fun resolve(name: String): Pair<String, String?> {
        val q = normalize(name)
        if (q.isEmpty()) return Pair(name, null)
        val hits = all().filter { (n, _) ->
            val nq = normalize(n)
            nq == q || nq.contains(q) || q.contains(nq)
        }
        if (hits.isEmpty()) return Pair(name, null)
        // Prefer the shortest full match so "John" wins over "John Johnson".
        val best = hits.minByOrNull { it.first.length } ?: hits.first()
        return best
    }

    /** Contact names, for feeding the model so it uses real names as recipients. */
    fun names(limit: Int = 40): List<String> =
        all().map { it.first }.distinct().sorted().take(limit)

    private fun normalize(s: String): String =
        s.trim().lowercase().filter { it.isLetterOrDigit() }
}
