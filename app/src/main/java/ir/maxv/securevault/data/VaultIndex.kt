package ir.maxv.securevault.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import ir.maxv.securevault.core.Labels
import ir.maxv.securevault.core.VaultPaths
import ir.maxv.securevault.core.VaultSql
import java.io.File
import java.text.Collator
import java.util.Locale

/** One row of `meta.sqlite.files`. */
data class VaultRow(
    val id: Long,
    val path: String,
    val blobId: String?,
    val isDir: Boolean,
    val size: Long,
    val sensitivity: String,
    val mtime: Long,
    /** The vault's emoji label for this path (plaintext metadata, `null` on an older mirror). */
    val emoji: String? = null,
) {
    val name: String get() = VaultPaths.nameOf(path)
    val title: String get() = if (isDir) name else VaultPaths.titleOf(path)
    val isSecret: Boolean get() = sensitivity != "normal"

    /** What the lists show: the emoji label in front of the name, when the vault has one. */
    val label: String get() = Labels.withEmoji(title, emoji)
}

/** A folder's or file's note (stored inside the encrypted `secure.store`). */
typealias NoteMap = Map<String, String>

/**
 * The plaintext metadata index (`meta.sqlite`) plus the notes out of the decrypted content
 * store, held in memory.
 *
 * The whole table is ~2.8k rows for Max's vault, so loading it once per unlock is simpler and
 * far more robust than building `LIKE` queries over paths that contain `_` (a LIKE wildcard)
 * and every Persian letter.
 */
class VaultIndex(
    val rows: List<VaultRow>,
    val tags: Map<Long, List<String>>,
    val folderNotes: NoteMap,
    val fileNotes: Map<Long, String>,
) {

    private val byPath: Map<String, VaultRow> = rows.associateBy { it.path }

    private val byId: Map<Long, VaultRow> = rows.associateBy { it.id }

    private val childrenMap: Map<String, List<VaultRow>> = rows
        .filter { !it.isDir }
        .groupBy { VaultPaths.parentOf(it.path) }
        .toMutableMap()
        .apply {
            rows.filter { it.isDir }.groupBy { VaultPaths.parentOf(it.path) }
                .forEach { (parent, dirs) -> merge(parent, dirs) { a, b -> a + b } }
        }

    private val collator: Collator = try {
        Collator.getInstance(Locale("fa", "IR"))
    } catch (e: Exception) {
        Collator.getInstance()
    }

    val fileCount: Int = rows.count { !it.isDir }

    val folderCount: Int = rows.count { it.isDir }

    fun row(id: Long): VaultRow? = byId[id]

    fun row(path: String): VaultRow? = byPath[VaultPaths.normalize(path)]

    fun folderNote(path: String): String? = folderNotes[VaultPaths.normalize(path)]

    fun fileNote(id: Long): String? = fileNotes[id]

    fun tagsOf(id: Long): List<String> = tags[id].orEmpty()

    /** Children of a folder: folders first, then notes, each name-sorted with a Persian collator. */
    fun children(folder: String): List<VaultRow> {
        val key = VaultPaths.normalize(folder)
        val list = childrenMap[key].orEmpty()
        val comparator = Comparator<VaultRow> { a, b ->
            val byName = collator.compare(a.name, b.name)
            if (byName != 0) byName else a.name.compareTo(b.name)
        }
        val dirs = list.filter { it.isDir }.sortedWith(comparator)
        val files = list.filter { !it.isDir }.sortedWith(comparator)
        return dirs + files
    }

    fun folderCount(folder: String): Int = children(folder).count { it.isDir }

    fun fileCountIn(folder: String): Int = children(folder).count { !it.isDir }

    /** Every note-like file that can be read as text (search corpus / prefetch candidates). */
    fun textFiles(maxBytes: Long? = null): List<VaultRow> = rows.filter { row ->
        !row.isDir && row.sensitivity == "normal" && row.blobId != null &&
            VaultPaths.isTextLike(row.path) &&
            (maxBytes == null || row.size <= maxBytes)
    }

    /** Every normal blob (used by "download everything"). */
    fun normalFiles(): List<VaultRow> = rows.filter { !it.isDir && it.sensitivity == "normal" && it.blobId != null }

    fun childrenCountRecursive(folder: String): Int {
        val prefix = VaultPaths.normalize(folder)
        return rows.count { row ->
            row.path.startsWith(if (prefix.isEmpty()) "" else "$prefix/") &&
                row.path != prefix
        }
    }

    companion object {
        private const val TAG = "SecureVault"

        /** Read a metadata database. The caller owns the connection. */
        fun load(db: SQLiteDatabase): VaultIndex {
            val rows = ArrayList<VaultRow>()
            // The mirror's schema is whatever the desktop pushed, so the query is built from the
            // columns that actually exist (see VaultSql) instead of failing on a missing one.
            val cols = columns(db, "files")
            require(VaultSql.hasUsableKey(cols)) { VaultSql.unusableKeyMessage(cols) }
            Log.i(TAG, "meta files columns: " + cols.sorted().joinToString(", "))
            try {
                readRows(db, VaultSql.filesSelect(cols), rows)
            } catch (e: Exception) {
                // A WITHOUT ROWID table has no rowid to stand in for a missing id.
                Log.w(TAG, "files select failed, retrying with literals: ${e.message}")
                rows.clear()
                readRows(db, VaultSql.filesSelectWithoutRowId(cols), rows)
            }
            val tags = HashMap<Long, MutableList<String>>()
            try {
                db.rawQuery(
                    "SELECT ft.file_id, t.name FROM file_tags ft JOIN tags t ON t.id = ft.tag_id",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        tags.getOrPut(cursor.getLong(0)) { ArrayList() }.add(cursor.getString(1) ?: "")
                    }
                }
            } catch (e: Exception) {
                // tags are optional metadata
            }
            return VaultIndex(rows, tags, emptyMap(), emptyMap())
        }

        /** Notes live in the decrypted content store (`secure.store`). */
        fun loadNotes(store: SQLiteDatabase): Pair<NoteMap, Map<Long, String>> {
            val folderNotes = HashMap<String, String>()
            try {
                store.rawQuery("SELECT folder_path, note_text FROM folder_notes", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        folderNotes[VaultPaths.normalize(cursor.getString(0) ?: "")] = cursor.getString(1) ?: ""
                    }
                }
            } catch (e: Exception) {
                // an older store may not have the table
            }
            val fileNotes = HashMap<Long, String>()
            try {
                store.rawQuery("SELECT file_id, note_text FROM file_notes", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        fileNotes[cursor.getLong(0)] = cursor.getString(1) ?: ""
                    }
                }
            } catch (e: Exception) {
                // ditto
            }
            return folderNotes to fileNotes
        }

        /** Copy a database file into a writable working directory before opening it. */
        fun workingCopy(source: File, workDir: File, name: String): File {
            workDir.mkdirs()
            val target = File(workDir, name)
            if (target.exists()) target.delete()
            source.copyTo(target, overwrite = true)
            // A stale WAL from a previous run would shadow the fresh copy.
            File(workDir, "$name-wal").delete()
            File(workDir, "$name-shm").delete()
            return target
        }

        /** Read `files` rows into `into`, in the order VaultSql.FILE_COLUMNS lists them. */
        private fun readRows(db: SQLiteDatabase, sql: String, into: MutableList<VaultRow>) {
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    into.add(
                        VaultRow(
                            id = cursor.getLong(0),
                            path = cursor.getString(1) ?: "",
                            blobId = cursor.getString(2),
                            isDir = cursor.getInt(3) != 0,
                            size = cursor.getLong(4),
                            sensitivity = cursor.getString(5) ?: "normal",
                            mtime = cursor.getLong(6),
                            emoji = cursor.getString(7),
                        )
                    )
                }
            }
        }

        /** Columns of a table — used to stay compatible with an older mirror's schema. */
        fun columns(db: SQLiteDatabase, table: String): Set<String> {
            val names = HashSet<String>()
            try {
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        names.add(cursor.getString(1) ?: "")
                    }
                }
            } catch (e: Exception) {
                // Unreadable schema: the caller falls back to the columns it knows.
            }
            return names
        }
    }
}
