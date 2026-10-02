package ir.maxv.securevault

import ir.maxv.securevault.core.RecentEntry
import ir.maxv.securevault.core.RecentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the "recently opened" list.
 *
 * Worth testing because the list is a *shortcut*: a duplicate entry, a lost counter or an unbounded
 * file would quietly make it useless, and the display order is the whole point of the feature.
 */
class RecentsTest {

    private fun paths(entries: List<RecentEntry>): List<String> = entries.map { it.path }

    @Test
    fun `the newest open goes to the front`() {
        var list = RecentList.record(emptyList(), "a.md", 1_000)
        list = RecentList.record(list, "b.md", 2_000)
        list = RecentList.record(list, "c.md", 3_000)
        assertEquals(listOf("c.md", "b.md", "a.md"), paths(list))
    }

    @Test
    fun `re-opening a file moves it up and counts, never duplicates`() {
        var list = RecentList.record(emptyList(), "a.md", 1_000)
        list = RecentList.record(list, "b.md", 2_000)
        list = RecentList.record(list, "a.md", 3_000)
        assertEquals(listOf("a.md", "b.md"), paths(list))
        assertEquals(2, list.first().opens)
        assertEquals(3_000L, list.first().openedAt)
        assertEquals(1, list.last().opens)
        assertEquals(2, list.size)
    }

    @Test
    fun `the list stays bounded and keeps the most recent`() {
        var list = emptyList<RecentEntry>()
        for (i in 1..60) list = RecentList.record(list, "note-$i.md", i.toLong(), limit = 5)
        assertEquals(5, list.size)
        assertEquals(listOf("note-60.md", "note-59.md", "note-58.md", "note-57.md", "note-56.md"), paths(list))
    }

    @Test
    fun `a blank path is ignored instead of creating an empty row`() {
        val list = RecentList.record(emptyList(), "   ", 1_000)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `the file round-trips through json`() {
        var list = RecentList.record(emptyList(), "رمزها/بانک/کارت.md", 1_700_000_000_000)
        list = RecentList.record(list, "تحقیقات/منتورینگ/علی.md", 1_700_000_001_000)
        list = RecentList.record(list, "رمزها/بانک/کارت.md", 1_700_000_002_000)

        val restored = RecentList.parse(RecentList.encode(list))
        assertEquals(list, restored)
        assertEquals(2, restored.first().opens)
        assertEquals("رمزها/بانک/کارت.md", restored.first().path)
    }

    @Test
    fun `a broken or foreign file reads as empty, never as a crash`() {
        assertTrue(RecentList.parse("").isEmpty())
        assertTrue(RecentList.parse("not json").isEmpty())
        assertTrue(RecentList.parse("{}").isEmpty())
        assertTrue(RecentList.parse("""{"v":1,"entries":"nope"}""").isEmpty())
        // rows without a path are dropped, rows with a nonsense counter are clamped
        val salvaged = RecentList.parse(
            """{"v":1,"entries":[{"opened_at":5},{"path":"ok.md","opens":0}]}"""
        )
        assertEquals(1, salvaged.size)
        assertEquals("ok.md", salvaged.first().path)
        assertEquals(1, salvaged.first().opens)
    }
}
