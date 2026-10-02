package ir.maxv.securevault.core

/**
 * Literal (substring) search over decrypted note bodies.
 *
 * The desktop client's index is a token FTS index, which cannot answer an exact
 * substring query (`یخساز` inside `یخسازها`, a partial path, a code snippet). This
 * matcher is deliberately literal, and normalises the Persian letter variants that
 * would otherwise hide a hit (Arabic yeh/kaf, diacritics, ZWNJ, tatweel, digits).
 *
 * Normalisation is strictly 1:1 in length: every removed character becomes
 * `\u0000`, so a match offset in the normalised text is the same offset in the
 * original text and snippets stay aligned.
 */
object PersianText {

    private const val DROP = '\u0000'

    /** Map every character to its canonical form, or [DROP] when it carries no meaning for search. */
    fun canonical(c: Char): Char = when (c) {
        // Arabic letter variants → Persian forms
        '\u064A', '\u0649', '\u06D2' -> '\u06CC'    // ي / ى / ے → ی
        '\u0643' -> '\u06A9'                        // ك → ک
        '\u0629' -> '\u0647'                        // ة → ه
        '\u0623', '\u0625', '\u0622', '\u0671', '\u0670' -> '\u0627' // أ إ آ ٱ → ا
        '\u0624' -> '\u0648'                        // ؤ → و
        '\u0626' -> '\u06CC'                         // ئ → ی
        '\u06C0' -> '\u0647'                        // ۀ → ه
        // digits (Arabic-Indic and Extended Arabic-Indic) → ASCII
        in '\u0660'..'\u0669' -> '0' + (c.code - 0x0660)
        in '\u06F0'..'\u06F9' -> '0' + (c.code - 0x06F0)
        // spaces that must behave like a plain space
        '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2009', '\u200A', '\u202F' -> ' '
        // zero-width / formatting marks carry no searchable meaning
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF' -> DROP
        '\u0640' -> DROP                            // tatweel
        // Arabic diacritics and Quranic marks
        in '\u064B'..'\u065F' -> DROP
        in '\u06D6'..'\u06ED' -> DROP
        '\u0610', '\u0611', '\u0612', '\u0613', '\u0614', '\u0615', '\u0616', '\u0617' -> DROP
        else -> if (c in 'A'..'Z') (c.code + 32).toChar() else c
    }

    /**
     * Normalise a whole string into its searchable form.
     *
     * The result is *shorter* than the input: characters that carry no searchable meaning
     * (ZWNJ, diacritics, tatweel, zero-width marks) are dropped, so `مى‌خواهم` and `میخواهم`
     * are the same string. Use [normalizeWithMap] when offsets must be mapped back to the
     * original text.
     */
    fun normalize(text: CharSequence): String {
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = canonical(text[i])
            if (c != DROP) sb.append(c)
        }
        return sb.toString()
    }

    /** A normalised string plus, per character, the index it came from in the original. */
    data class Normalized(val text: String, val map: IntArray)

    /** Normalise while remembering where every surviving character came from. */
    fun normalizeWithMap(text: CharSequence): Normalized {
        val sb = StringBuilder(text.length)
        val map = IntArray(text.length)
        for (i in text.indices) {
            val c = canonical(text[i])
            if (c == DROP) continue
            map[sb.length] = i
            sb.append(c)
        }
        return Normalized(sb.toString(), map.copyOf(sb.length))
    }

    /** True when the string carries at least one right-to-left letter (Hebrew or Arabic block). */
    fun hasRtlChars(text: String): Boolean = text.any { it.isRtlChar() }

    fun Char.isRtlChar(): Boolean = this in '\u0590'..'\u05FF' ||
        this in '\u0600'..'\u06FF' ||
        this in '\u0750'..'\u077F' ||
        this in '\u08A0'..'\u08FF' ||
        this in '\uFB1D'..'\uFB4F' ||
        this in '\uFB50'..'\uFDFF' ||
        this in '\uFE70'..'\uFEFF'

    /**
     * Whether a block of text should be laid out right-to-left.
     *
     * Max's rule: a line that contains even one Persian/Arabic letter is RTL; a purely
     * Latin line (and code) stays LTR.
     */
    fun isRtlText(text: String): Boolean = hasRtlChars(text)
}

/** One literal match inside a note body. */
data class LiteralHit(
    val offset: Int,
    val length: Int,
    val line: Int,
    val lineText: String,
    val contextStart: Int,
    val contextEnd: Int,
)

object LiteralSearch {

    /**
     * How much of a line a hit keeps for display.
     *
     * A note can legitimately be one multi-megabyte line (exports, base64, one long paragraph).
     * Slicing that whole line per hit is what made content search die with an OutOfMemoryError on
     * the device, so a hit carries a window around itself — which is all the UI ever showed anyway
     * (`take(160)`).
     */
    private const val LINE_BEFORE = 120
    private const val LINE_AFTER = 280

    /**
     * Find every literal occurrence of [query] in [text].
     *
     * Matching happens on the normalised form (see [PersianText.normalize]); the offsets in the
     * result always refer to [text], so a snippet or a line number can be shown for a match that
     * spanned a removed character (a ZWNJ, a diacritic).
     */
    fun findAll(text: String, query: String, maxHits: Int = 200, contextChars: Int = 60): List<LiteralHit> {
        if (query.isEmpty()) return emptyList()
        val hay = PersianText.normalizeWithMap(text)
        val needle = PersianText.normalize(query)
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<LiteralHit>()
        var from = 0
        // Hits come out in increasing offset order, so line number and line bounds are tracked in a
        // single forward pass; walking backwards from every hit is O(n) *per hit* on a huge line.
        var lineNo = 1
        var lineStart = 0
        var cursor = 0
        while (hits.size < maxHits) {
            val idx = hay.text.indexOf(needle, from)
            if (idx < 0) break
            val lastIndex = idx + needle.length - 1
            val start = hay.map[idx]
            val end = hay.map[lastIndex] + 1
            while (cursor < start) {
                if (text[cursor] == '\n') {
                    lineNo++
                    lineStart = cursor + 1
                }
                cursor++
            }
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            hits.add(
                LiteralHit(
                    offset = start,
                    length = end - start,
                    line = lineNo,
                    lineText = lineWindow(text, lineStart, lineEnd, start),
                    contextStart = maxOf(0, start - contextChars),
                    contextEnd = minOf(text.length, end + contextChars),
                )
            )
            from = idx + needle.length
        }
        return hits
    }

    fun firstHit(text: String, query: String): LiteralHit? = findAll(text, query, maxHits = 1).firstOrNull()

    /** Does the text contain the query at all (cheap, no offsets)? */
    fun contains(text: String, query: String): Boolean {
        if (query.isEmpty()) return false
        return PersianText.normalize(text).contains(PersianText.normalize(query))
    }

    /** 1-based line number of an offset. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        var i = 0
        val limit = minOf(offset, text.length)
        while (i < limit) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

    /**
     * The line that contains [offset], **clipped to a window** around it: a short line comes back
     * whole, a megabyte-long one comes back as `…` + the part worth reading + `…`.
     */
    fun lineText(text: String, offset: Int): String {
        val safe = minOf(offset, maxOf(0, text.length - 1)).coerceAtLeast(0)
        val start = text.lastIndexOf('\n', safe).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', safe).let { if (it < 0) text.length else it }
        return lineWindow(text, start, end, offset)
    }

    private fun lineWindow(text: String, lineStart: Int, lineEnd: Int, offset: Int): String {
        if (lineStart >= lineEnd) return ""
        val from = maxOf(lineStart, offset - LINE_BEFORE)
        val to = minOf(lineEnd, offset + LINE_AFTER)
        if (from == lineStart && to == lineEnd) return text.substring(lineStart, lineEnd)
        val head = if (from > lineStart) "…" else ""
        val tail = if (to < lineEnd) "…" else ""
        return head + text.substring(from, to) + tail
    }

    /** Build a snippet around a hit, with `…` markers instead of raw slicing. */
    fun snippet(text: String, hit: LiteralHit): String {
        val head = if (hit.contextStart > 0) "…" else ""
        val tail = if (hit.contextEnd < text.length) "…" else ""
        val raw = text.substring(hit.contextStart, hit.contextEnd).replace('\n', ' ')
        return head + raw.trim() + tail
    }

    /** Where the query sits inside [snippet] (for highlighting), or -1. */
    fun matchStartInSnippet(text: String, hit: LiteralHit): Int {
        val raw = text.substring(hit.contextStart, hit.contextEnd)
        val lead = raw.length - raw.trimStart().length
        val relative = hit.offset - hit.contextStart - lead
        return if (relative < 0) -1 else relative
    }
}
