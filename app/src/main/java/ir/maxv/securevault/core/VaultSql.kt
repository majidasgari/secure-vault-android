package ir.maxv.securevault.core

/**
 * SQL for the plaintext metadata database (`meta.sqlite`), built from the columns a mirror
 * actually has.
 *
 * The mirror on this device is whatever the desktop last pushed to the bucket, so its schema is
 * not guaranteed to be the one this app was written against: an older push has no `emoji`
 * column, and a mirror restored from a different snapshot may differ more than that. Selecting a
 * hard-coded column list turns any of those into "no such column: …" and nothing opens at all, so
 * the list is derived from `PRAGMA table_info` and every missing column is replaced by something
 * that still reads well:
 *
 * * `id` → `rowid` (every ordinary SQLite table has one, so tags and notes keep lining up),
 * * `logical_path` → `path` when that is what the table calls it,
 * * anything else → a literal (NULL / 0 / 'normal'), so browsing still works.
 */
object VaultSql {

    /** Columns of `files` this app knows how to read, in the order it reads them. */
    val FILE_COLUMNS: List<String> = listOf(
        "id",
        "logical_path",
        "blob_id",
        "is_dir",
        "size",
        "sensitivity",
        "mtime",
        "emoji",
    )

    /** What a missing column is replaced with, when there is no smarter substitute. */
    private val FALLBACKS: Map<String, String> = mapOf(
        "blob_id" to "NULL",
        "is_dir" to "0",
        "size" to "0",
        "sensitivity" to "'normal'",
        "mtime" to "0",
        "emoji" to "NULL",
    )

    /** The plain query, used when the schema cannot be read at all. */
    private val PLAIN_SELECT: String = "SELECT " + FILE_COLUMNS.joinToString(", ") + " FROM files"

    /** Column names a mirror may use for the file's path. */
    private val PATH_COLUMNS: List<String> = listOf("logical_path", "path")

    /**
     * Build `SELECT … FROM files` for a mirror whose `files` table has `columns`.
     *
     * An empty `columns` means the schema could not be read at all; the plain column list is used
     * then, which is the same query this app has always run.
     */
    fun filesSelect(columns: Set<String>): String {
        if (columns.isEmpty()) return PLAIN_SELECT
        val selected = FILE_COLUMNS.joinToString(", ") { name ->
            when {
                columns.contains(name) -> name
                name == "id" -> "rowid AS id"
                name == "logical_path" -> {
                    val alias = PATH_COLUMNS.firstOrNull { columns.contains(it) }
                    if (alias == null) name else "$alias AS logical_path"
                }
                else -> FALLBACKS[name] ?: "NULL"
            }
        }
        return "SELECT $selected FROM files"
    }

    /**
     * The same query for a table that has no `rowid` (a `WITHOUT ROWID` table): a missing `id`
     * becomes the literal `-1` instead of `rowid`.
     */
    fun filesSelectWithoutRowId(columns: Set<String>): String {
        if (columns.isEmpty()) return PLAIN_SELECT
        val selected = FILE_COLUMNS.joinToString(", ") { name ->
            when {
                columns.contains(name) -> name
                name == "id" -> "-1"
                else -> FALLBACKS[name] ?: "NULL"
            }
        }
        return "SELECT $selected FROM files"
    }

    /** True when the mirror's `files` table is usable for browsing (it must have the path). */
    fun hasUsableKey(columns: Set<String>): Boolean =
        columns.isEmpty() || PATH_COLUMNS.any { columns.contains(it) }

    /** A readable description of a schema this app cannot browse, for the error banner. */
    fun unusableKeyMessage(columns: Set<String>): String =
        "جدول files در این آینه ستون مسیر را ندارد (ستون‌ها: " +
            columns.sorted().joinToString(", ") + ")"
}
