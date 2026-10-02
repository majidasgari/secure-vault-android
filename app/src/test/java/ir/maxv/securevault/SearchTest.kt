package ir.maxv.securevault

import ir.maxv.securevault.core.LiteralSearch
import ir.maxv.securevault.core.PersianText
import ir.maxv.securevault.core.SearchScope
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

    @Test
    fun `a hit on a giant single line keeps a window instead of the whole line`() {
        // a 2M-char line: slicing it whole is what blew the heap on the device
        val line = "مقدمه ".repeat(300_000) + "یخساز" + " دنباله".repeat(300_000)
        assertEquals("the fixture is one single line", 0, line.count { it == '\n' })
        val hit = LiteralSearch.findAll(line, "یخساز").first()
        assertTrue("line number must be 1", hit.line == 1)
        assertTrue("window must stay small, was ${hit.lineText.length}", hit.lineText.length < 500)
        assertTrue(hit.lineText.contains("یخساز"))
        assertTrue(hit.lineText.startsWith("…"))
        assertTrue(hit.lineText.endsWith("…"))
        // the offset still points into the original text
        assertEquals(line.substring(hit.offset, hit.offset + hit.length), "یخساز")
    }

    @Test
    fun `line numbers stay right across many hits in one pass`() {
        val text = (1..40).joinToString("\n") { "خط $it دارد نشانه" }
        val hits = LiteralSearch.findAll(text, "نشانه")
        assertEquals(40, hits.size)
        assertEquals((1..40).toList(), hits.map { it.line })
        assertEquals("خط 7 دارد نشانه", hits[6].lineText)
    }

    @Test
    fun `every title is searchable while only normal text bodies are decrypted`() {
        // normal + text-like: title and body
        assertEquals(SearchScope.TITLES or SearchScope.BODY, SearchScope.of("normal", isTextLike = true))
        assertFalse(SearchScope.titlesOnly(SearchScope.of("normal", isTextLike = true)))

        // a normal image is found by name only
        assertEquals(SearchScope.TITLES, SearchScope.of("normal", isTextLike = false))

        // secret and secretfile: title only, never the body
        assertEquals(SearchScope.TITLES, SearchScope.of("secret", isTextLike = true))
        assertEquals(SearchScope.TITLES, SearchScope.of("secretfile", isTextLike = true))
        assertTrue(SearchScope.titlesOnly(SearchScope.of("secret", isTextLike = true)))
    }

    @Test
    fun `a body is only scanned when it is normal, text-like and not absurdly big`() {
        assertTrue(SearchScope.scansBody("normal", isTextLike = true, size = 1024))
        assertFalse(SearchScope.scansBody("normal", isTextLike = true, size = SearchScope.MAX_BODY_BYTES + 1))
        assertTrue(SearchScope.scansBody("normal", isTextLike = true, size = SearchScope.MAX_BODY_BYTES))
        assertFalse(SearchScope.scansBody("normal", isTextLike = false, size = 1024))
        assertFalse(SearchScope.scansBody("secret", isTextLike = true, size = 1024))
        assertFalse("an unknown size must not be trusted", SearchScope.scansBody("normal", isTextLike = true, size = -1))
    }

    @Test
    fun `a secret file's title matches the way any other title does`() {
        val title = "کارت بانکی — رمزها"
        assertTrue(LiteralSearch.contains(title, "کارت"))
        assertTrue(LiteralSearch.contains(title, "بانکی — رم"))
        val hit = LiteralSearch.firstHit(title, "رمزها")
        assertEquals(title.indexOf("رمزها"), hit!!.offset)
    }
}
