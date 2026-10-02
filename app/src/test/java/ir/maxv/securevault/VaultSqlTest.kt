package ir.maxv.securevault

import ir.maxv.securevault.core.VaultSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metadata query is built from the columns a mirror actually has, so a mirror pushed by an
 * older desktop build still opens instead of failing with "no such column".
 */
class VaultSqlTest {

    private val full = setOf(
        "id", "logical_path", "blob_id", "is_dir", "size",
        "encrypted", "sensitivity", "mtime", "created", "source", "emoji",
    )

    @Test
    fun `a current mirror is read with its emoji column`() {
        assertEquals(
            "SELECT id, logical_path, blob_id, is_dir, size, sensitivity, mtime, emoji FROM files",
            VaultSql.filesSelect(full),
        )
    }

    @Test
    fun `a mirror without the emoji column still reads`() {
        val older = full - "emoji"
        assertEquals(
            "SELECT id, logical_path, blob_id, is_dir, size, sensitivity, mtime, NULL FROM files",
            VaultSql.filesSelect(older),
        )
    }

    @Test
    fun `a table without an id column falls back to rowid`() {
        val noId = full - "id"
        val sql = VaultSql.filesSelect(noId)
        assertTrue(sql, sql.startsWith("SELECT rowid AS id, logical_path,"))
        assertTrue(sql, sql.endsWith(", emoji FROM files"))
    }

    @Test
    fun `a table that names the key path is read from it`() {
        val renamed = full - "logical_path" + "path"
        val sql = VaultSql.filesSelect(renamed)
        assertTrue(sql, sql.contains("path AS logical_path"))
    }

    @Test
    fun `an unreadable schema falls back to the plain query`() {
        assertEquals(
            "SELECT id, logical_path, blob_id, is_dir, size, sensitivity, mtime, emoji FROM files",
            VaultSql.filesSelect(emptySet()),
        )
    }

    @Test
    fun `a rowid-less table has a second query with literals only`() {
        val noId = full - "id" - "emoji"
        val sql = VaultSql.filesSelectWithoutRowId(noId)
        assertFalse(sql, sql.contains("rowid"))
        assertEquals(
            "SELECT -1, logical_path, blob_id, is_dir, size, sensitivity, mtime, NULL FROM files",
            sql,
        )
    }

    @Test
    fun `the key column decides whether the mirror is browsable`() {
        assertTrue(VaultSql.hasUsableKey(full))
        assertTrue(VaultSql.hasUsableKey(setOf("path", "blob_id")))
        assertTrue(VaultSql.hasUsableKey(emptySet()))
        assertFalse(VaultSql.hasUsableKey(setOf("blob_id", "size")))
        assertTrue(VaultSql.unusableKeyMessage(setOf("size", "blob_id")).contains("blob_id, size"))
    }
}
