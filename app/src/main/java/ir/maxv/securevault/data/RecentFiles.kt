package ir.maxv.securevault.data

import android.content.Context
import ir.maxv.securevault.core.RecentEntry
import ir.maxv.securevault.core.RecentList
import java.io.File
import java.io.FileOutputStream

/**
 * The "recently opened" list on disk.
 *
 * Device-local by design: this client never writes to the vault or the bucket, and the vault's own
 * `access_log` belongs to the desktop. So the list is a small JSON file next to `settings.json`,
 * holding nothing but the paths opened *on this phone*, when, and how often — no decrypted text, no
 * bodies — and it goes away with «پاک‌کردن نسخهٔ محلی» like every other local trace.
 *
 * The rules themselves live in [RecentList] (so they are testable off-device); this class is the
 * file: read, write, delete.
 */
class RecentFilesStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** Newest first; a missing or unreadable file simply means "nothing yet". */
    fun load(): List<RecentEntry> = try {
        if (!file.isFile) emptyList() else RecentList.parse(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        emptyList()
    }

    /** Add one open, bump its counter, and keep the list bounded. */
    fun record(
        path: String,
        at: Long = System.currentTimeMillis(),
        limit: Int = RecentList.MAX_ENTRIES,
    ): List<RecentEntry> {
        val updated = RecentList.record(load(), path, at, limit)
        save(updated)
        return updated
    }

    fun clear() {
        file.delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
    }

    private fun save(entries: List<RecentEntry>) {
        val bytes = RecentList.encode(entries).toByteArray(Charsets.UTF_8)
        // temp + rename: a half-written list must never replace a good one
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "recent.json"
    }
}
