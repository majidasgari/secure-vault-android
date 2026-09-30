package ir.maxv.securevault

import ir.maxv.securevault.core.LiteralSearch
import ir.maxv.securevault.core.PersianText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The literal matcher: exact substrings, Persian letter variants, offsets and snippets. */
class SearchTest {

    @Test
    fun `the matcher is literal, not token based`() {
        val text = "یخساز خراب است"
        assertTrue(LiteralSearch.contains(text, "یخس"))
        assertTrue(LiteralSearch.contains(text, "ساز خر"))
        assertFalse(LiteralSearch.contains(text, "یخچال"))
    }

    @Test
    fun `Arabic and Persian letter variants are the same letter`() {
        val text = "كتاب من كوچك است"           // Arabic kaf
        assertTrue(LiteralSearch.contains(text, "کوچک"))   // Persian kaf
        val arabicYeh = "علي رضا"
        assertTrue(LiteralSearch.contains(arabicYeh, "علی"))
    }

    @Test
    fun `ZWNJ, diacritics and tatweel do not hide a hit`() {
        val withZwnj = "می‌خواهم بروم"          // ZWNJ
        assertTrue(LiteralSearch.contains(withZwnj, "میخواهم"))
        assertTrue(LiteralSearch.contains(withZwnj, "می‌خواهم"))
        val withDiacritics = "مُحَمَّد رضا"
        assertTrue(LiteralSearch.contains(withDiacritics, "محمد"))
        val tatweel = "کتــــاب"
        assertTrue(LiteralSearch.contains(tatweel, "کتاب"))
    }

    @Test
    fun `digits are compared across both numeral systems`() {
        assertTrue(LiteralSearch.contains("سال ۱۴۰۵", "1405"))
        assertTrue(LiteralSearch.contains("سال 1405", "۱۴۰۵"))
    }

    @Test
    fun `case folding only applies to latin letters`() {
        assertTrue(LiteralSearch.contains("DeepSeek V4", "deepseek"))
        assertTrue(LiteralSearch.contains("deepseek v4", "DEEPSEEK"))
    }

    @Test
    fun `offsets point back into the original text`() {
        val text = "خط اول\nیخساز اینجاست\nخط سوم"
        val hits = LiteralSearch.findAll(text, "یخساز")
        assertEquals(1, hits.size)
        val hit = hits.first()
        assertEquals(2, hit.line)
        assertEquals("یخساز اینجاست", hit.lineText)
        assertEquals(text.substring(hit.offset, hit.offset + hit.length), "یخساز")
    }

    @Test
    fun `a snippet carries ellipses only where the text was cut`() {
        val text = "مقدمهٔ طولانی ".repeat(20) + "هدف" + " دنباله".repeat(20)
        val hit = LiteralSearch.findAll(text, "هدف").first()
        val snippet = LiteralSearch.snippet(text, hit)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("هدف"))
    }

    @Test
    fun `an empty query matches nothing instead of everything`() {
        assertTrue(LiteralSearch.findAll("متن", "").isEmpty())
        assertFalse(LiteralSearch.contains("متن", ""))
    }

    @Test
    fun `normalisation drops meaningless characters but keeps offsets traceable`() {
        val text = "مُحَمَّد‌رضا ۱۴۰۵"
        assertTrue(PersianText.normalize(text).length < text.length)
        val normalized = PersianText.normalizeWithMap(text)
        assertEquals(normalized.text.length, normalized.map.size)
        for (i in normalized.text.indices) {
            assertEquals(normalized.text[i], PersianText.canonical(text[normalized.map[i]]))
        }
        // a hit inside a word that carries diacritics still points at the original text
        val hits = LiteralSearch.findAll(text, "محمد")
        assertEquals(1, hits.size)
        assertEquals("مُحَمَّد", text.substring(hits[0].offset, hits[0].offset + hits[0].length))
    }

    @Test
    fun `direction detection follows the content`() {
        assertTrue(PersianText.isRtlText("متن فارسی"))
        assertTrue(PersianText.isRtlText("mixed متن"))
        assertFalse(PersianText.isRtlText("apt install nfs-kernel-server"))
    }

    @Test
    fun `searching the fixture note finds a phrase that a token index would miss`() {
        val text = Fixture.files().first { it.getString("path").contains("سفر") }.getString("text")
        assertTrue(LiteralSearch.contains(text, "یخساز"))            // exact word
        assertTrue(LiteralSearch.contains(text, "یخسا"))             // partial word
        assertTrue(LiteralSearch.contains(text, "سه روز با گلزار"))   // phrase across tokens
        val hits = LiteralSearch.findAll(text, "چادر")
        assertEquals(1, hits.size)
        assertEquals(7, hits.first().line)
    }
}
