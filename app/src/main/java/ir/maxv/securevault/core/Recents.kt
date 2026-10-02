package ir.maxv.securevault.core

import org.json.JSONArray
import org.json.JSONObject

/** One file the user opened before, as this device remembers it. */
data class RecentEntry(
    val path: String,
    val openedAt: Long,
    /** How many times this path was opened here — the "frequently used" half of the signal. */
    val opens: Int,
)

/**
 * The rules of the "recently opened" list, kept free of Android so they can be tested on the JVM.
 *
 * It is a list of *paths* — never bodies, never secrets — recorded on this device only: the reader
 * is read-only towards the vault and the bucket, so nothing about what was opened is ever written
 * back into the vault.
 */
object RecentList {

    /** Enough to be useful, small enough to stay a shortcut and not become a database. */
    const val MAX_ENTRIES = 40

    private const val FORMAT_VERSION = 1
    private const val FIELD_VERSION = "v"
    private const val FIELD_ENTRIES = "entries"
    private const val FIELD_PATH = "path"
    private const val FIELD_OPENED_AT = "opened_at"
    private const val FIELD_OPENS = "opens"

    /**
     * Move [path] to the front, bump its counter, drop the tail beyond [limit].
     *
     * Re-opening a file never duplicates it: the newest open wins the position and the count keeps
     * growing, which is what makes the list useful for "the files I actually work with".
     */
    fun record(
        previous: List<RecentEntry>,
        path: String,
        at: Long,
        limit: Int = MAX_ENTRIES,
    ): List<RecentEntry> {
        if (path.isBlank() || limit <= 0) return previous
        val opens = previous.firstOrNull { it.path == path }?.opens ?: 0
        val updated = ArrayList<RecentEntry>(minOf(limit, previous.size + 1))
        updated.add(RecentEntry(path, at, opens + 1))
        for (entry in previous) {
            if (entry.path == path) continue
            if (updated.size >= limit) break
            updated.add(entry)
        }
        return updated
    }

    fun encode(entries: List<RecentEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put(FIELD_PATH, entry.path)
                    .put(FIELD_OPENED_AT, entry.openedAt)
                    .put(FIELD_OPENS, entry.opens)
            )
        }
        return JSONObject().put(FIELD_VERSION, FORMAT_VERSION).put(FIELD_ENTRIES, array).toString()
    }

    /** A missing, truncated or hand-edited file yields "nothing yet" rather than an exception. */
    fun parse(json: String): List<RecentEntry> = try {
        val array = JSONObject(json).optJSONArray(FIELD_ENTRIES) ?: JSONArray()
        val out = ArrayList<RecentEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString(FIELD_PATH, "")
            if (path.isBlank()) continue
            out.add(
                RecentEntry(
                    path = path,
                    openedAt = item.optLong(FIELD_OPENED_AT, 0L),
                    opens = item.optInt(FIELD_OPENS, 1).coerceAtLeast(1),
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}
