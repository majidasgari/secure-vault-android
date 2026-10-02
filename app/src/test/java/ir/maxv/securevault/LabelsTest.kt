package ir.maxv.securevault

import ir.maxv.securevault.core.Labels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The emoji label is metadata on the path, exactly like the name: it is shown in front of the
 * name in the lists, and a vault that has no label for a path shows the plain name.
 */
class LabelsTest {

    @Test
    fun `a label is shown in front of the name`() {
        assertEquals("🏢 کار", Labels.withEmoji("کار", "🏢"))
    }

    @Test
    fun `a missing label leaves the name alone`() {
        assertEquals("کار", Labels.withEmoji("کار", null))
        assertEquals("کار", Labels.withEmoji("کار", ""))
        assertEquals("کار", Labels.withEmoji("کار", "   "))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("🔐 رمزها", Labels.withEmoji("رمزها", " 🔐 "))
    }

    @Test
    fun `a multi-codepoint label is kept whole`() {
        // A flag is a regional-indicator pair and a family is joined with ZWJ: neither may be
        // cut down to its first code point.
        assertEquals("🇮🇷 ایران", Labels.withEmoji("ایران", "🇮🇷"))
        assertEquals("👨‍👩‍👧 خانه", Labels.withEmoji("خانه", "👨‍👩‍👧"))
    }

    @Test
    fun `latin names are labelled the same way`() {
        assertEquals("📱 Tablet", Labels.withEmoji("Tablet", "📱"))
    }

    @Test
    fun `clean returns the glyph or an empty string`() {
        assertEquals("🪙", Labels.clean(" 🪙 "))
        assertEquals("", Labels.clean(null))
        assertEquals("", Labels.clean(""))
    }
}
