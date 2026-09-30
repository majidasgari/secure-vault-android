package ir.maxv.securevault.core

import org.json.JSONObject

/** One file the client knows about, from the last successful sync. */
data class SyncEntry(val size: Long, val etag: String?, val syncedAt: Long)

/** What a sync run should do. */
data class SyncPlan(
    val downloads: List<String>,
    val deletes: List<String>,
    val unchanged: Int,
)

/**
 * The pull side of the vault's three-way mirror, restricted to a reader:
 * nothing is ever uploaded and the write lock is never taken.
 */
object SyncPlanner {

    fun plan(
        remote: Map<String, S3Object>,
        base: Map<String, SyncEntry>,
        localPresent: Set<String>,
    ): SyncPlan {
        val downloads = ArrayList<String>()
        val deletes = ArrayList<String>()
        var unchanged = 0
        for ((relative, obj) in remote) {
            val known = base[relative]
            val matches = known != null &&
                localPresent.contains(relative) &&
                (
                    (!obj.etag.isNullOrEmpty() && obj.etag == known.etag) ||
                        (obj.etag.isNullOrEmpty() && known.size == obj.size)
                    )
            if (matches) unchanged++ else downloads.add(relative)
        }
        for (relative in base.keys) {
            if (!remote.containsKey(relative)) deletes.add(relative)
        }
        return SyncPlan(downloads, deletes, unchanged)
    }

    /** Keep only vault-home members we are allowed to mirror (defensive, mirrors the desktop exclusions). */
    fun eligible(relative: String): Boolean =
        !VaultPaths.isExcluded(relative) && !relative.contains("..") && !relative.startsWith("/")
}

/** Machine-local record of the last successful sync. */
class SyncState(private val entries: MutableMap<String, SyncEntry> = LinkedHashMap()) {

    val size: Int get() = entries.size

    fun entry(relative: String): SyncEntry? = entries[relative]

    fun all(): Map<String, SyncEntry> = entries.toMap()

    fun record(relative: String, size: Long, etag: String?, now: Long = System.currentTimeMillis()) {
        entries[relative] = SyncEntry(size, etag, now)
    }

    fun forget(relative: String) {
        entries.remove(relative)
    }

    fun lastSyncAt(): Long = entries.values.maxOfOrNull { it.syncedAt } ?: 0L

    fun toJson(): String {
        val root = JSONObject()
        for ((relative, entry) in entries) {
            root.put(
                relative,
                JSONObject()
                    .put("size", entry.size)
                    .put("etag", entry.etag ?: JSONObject.NULL)
                    .put("synced_at", entry.syncedAt)
            )
        }
        return root.toString()
    }

    companion object {
        fun fromJson(text: String?): SyncState {
            val state = SyncState()
            if (text.isNullOrBlank()) return state
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                return state
            }
            for (relative in root.keys()) {
                val item = root.optJSONObject(relative) ?: continue
                state.entries[relative] = SyncEntry(
                    size = item.optLong("size", 0L),
                    etag = item.optString("etag", "").ifEmpty { null },
                    syncedAt = item.optLong("synced_at", 0L),
                )
            }
            return state
        }
    }
}
