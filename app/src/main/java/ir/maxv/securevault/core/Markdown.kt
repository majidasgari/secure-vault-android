package ir.maxv.securevault.core

/** One block of a markdown document. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<Item>) : MdBlock() {
        data class Item(val text: String, val level: Int)
    }
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

/** One run of inline markdown. */
sealed class Inline {
    data class Text(val text: String) : Inline()
    data class Bold(val text: String) : Inline()
    data class Italic(val text: String) : Inline()
    data class Code(val text: String) : Inline()
    data class Link(val text: String, val url: String) : Inline()
    data class Image(val alt: String, val url: String) : Inline()
}

/**
 * A small, dependency-free markdown reader.
 *
 * It covers what the vault actually contains (headings, fenced code — often indented,
 * lists, quotes, tables, rules, links, images, emphasis) and deliberately does not try
 * to be CommonMark. Keeping it in Kotlin means the exact RTL rule (a line with a Persian
 * letter is laid out RTL, code never is) is decided by us, not by a third-party renderer.
 */
object Markdown {

    private val FENCE = Regex("""^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_+#.\-]*)""")
    private val ATX = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val RULE = Regex("""^ {0,3}((?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$""")
    private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // ── fenced code (the fence may be indented by a list step) ──────────────
            val fence = FENCE.find(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val indent = line.indexOf(marker)
                val language = fence.groupValues[2]
                val body = ArrayList<String>()
                i++
                var closed = false
                while (i < lines.size) {
                    val current = lines[i]
                    val trimmed = current.trimStart()
                    if (trimmed.startsWith(marker.take(3)) &&
                        trimmed.takeWhile { it == marker[0] }.length >= marker.length
                    ) {
                        closed = true
                        i++
                        break
                    }
                    body.add(stripIndent(current, minOf(indent, current.takeWhile { it == ' ' }.length)))
                    i++
                }
                if (!closed && body.isNotEmpty() && body.last().isBlank()) {
                    // the file ended with a newline after the last code line, not a blank line
                    body.removeAt(body.size - 1)
                }
                blocks.add(MdBlock.Code(language, body.joinToString("\n")))
                continue
            }

            // ── ATX heading ─────────────────────────────────────────────────────────
            val atx = ATX.find(line)
            if (atx != null) {
                blocks.add(MdBlock.Heading(atx.groupValues[1].length, atx.groupValues[2].trim()))
                i++
                continue
            }

            // ── setext heading (=== underline) ──────────────────────────────────────
            if (i + 1 < lines.size && lines[i + 1].matches(Regex("^ {0,3}={3,}\\s*$"))) {
                blocks.add(MdBlock.Heading(1, line.trim()))
                i += 2
                continue
            }

            // ── horizontal rule ─────────────────────────────────────────────────────
            if (RULE.matches(line)) {
                blocks.add(MdBlock.Rule)
                i++
                continue
            }

            // ── table ───────────────────────────────────────────────────────────────
            if (line.contains('|') && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val header = splitRow(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }

            // ── blockquote ──────────────────────────────────────────────────────────
            if (line.trimStart().startsWith(">")) {
                val collected = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    collected.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdBlock.Quote(collected.joinToString("\n")))
                continue
            }

            // ── lists ───────────────────────────────────────────────────────────────
            val ordered = ORDERED.find(line) != null
            if (ordered || BULLET.find(line) != null) {
                val items = ArrayList<MdBlock.ListBlock.Item>()
                while (i < lines.size) {
                    val current = lines[i]
                    val m = if (ordered) ORDERED.find(current) else BULLET.find(current)
                    if (m == null) {
                        // a continuation line belongs to the previous item
                        if (current.isNotBlank() && items.isNotEmpty() && current.startsWith("  ")) {
                            val last = items.removeAt(items.size - 1)
                            items.add(last.copy(text = last.text + " " + current.trim()))
                            i++
                            continue
                        }
                        break
                    }
                    val indent = m.groupValues[1].length
                    items.add(MdBlock.ListBlock.Item(text = m.groupValues[3].trim(), level = indent / 2))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered = ordered, items = items))
                continue
            }

            // ── paragraph ───────────────────────────────────────────────────────────
            val paragraph = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() &&
                FENCE.find(lines[i]) == null && ATX.find(lines[i]) == null &&
                !(lines[i].trimStart().startsWith(">")) &&
                BULLET.find(lines[i]) == null && ORDERED.find(lines[i]) == null &&
                !RULE.matches(lines[i])
            ) {
                paragraph.add(lines[i].trim())
                i++
            }
            if (paragraph.isNotEmpty()) blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n")))
        }
        return blocks
    }

    private fun stripIndent(line: String, count: Int): String =
        if (count <= 0) line else line.drop(minOf(count, line.length))

    private fun splitRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split('|').map { it.trim() }
    }

    /** Split one inline string into runs. Never throws; unknown syntax stays literal text. */
    fun inline(text: String): List<Inline> {
        val out = ArrayList<Inline>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(Inline.Text(buffer.toString()))
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]

            // image: ![alt](url)
            if (c == '!' && i + 1 < text.length && text[i + 1] == '[') {
                val close = text.indexOf(']', i + 2)
                if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    if (end > close) {
                        flush()
                        out.add(
                            Inline.Image(
                                text.substring(i + 2, close),
                                unwrap(text.substring(close + 2, end)),
                            )
                        )
                        i = end + 1
                        continue
                    }
                }
            }

            // link: [text](url)
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    if (end > close) {
                        flush()
                        out.add(
                            Inline.Link(
                                text.substring(i + 1, close),
                                unwrap(text.substring(close + 2, end)),
                            )
                        )
                        i = end + 1
                        continue
                    }
                }
            }

            // inline code: `code`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    out.add(Inline.Code(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // emphasis: **bold** / *italic* / __bold__ / _italic_
            if ((c == '*' || c == '_') && i + 1 < text.length) {
                val double = i + 1 < text.length && text[i + 1] == c
                val marker = if (double) "" + c + c else "" + c
                // An underscore inside a word is not emphasis: `snake_case_name` stays literal.
                val intraword = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                val end = if (intraword) -1 else text.indexOf(marker, i + marker.length)
                if (end > i) {
                    val inner = text.substring(i + marker.length, end)
                    val closesCleanly = end + marker.length >= text.length ||
                        !text[end + marker.length].isLetterOrDigit()
                    if (inner.isNotBlank() && !inner.startsWith(" ") && closesCleanly) {
                        flush()
                        out.add(if (double) Inline.Bold(inner) else Inline.Italic(inner))
                        i = end + marker.length
                        continue
                    }
                }
            }

            buffer.append(c)
            i++
        }
        flush()
        return out
    }

    /** Strip the `<…>` wrapper some exporters put around a URL. */
    private fun unwrap(url: String): String {
        var value = url.trim()
        if (value.startsWith("<") && value.endsWith(">")) value = value.substring(1, value.length - 1).trim()
        if (value.startsWith("\"") && value.endsWith("\"") && value.length > 1) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }

    /** Plain text of a markdown string (used for list previews and search snippets). */
    fun toPlainText(text: String, maxChars: Int = 240): String {
        val sb = StringBuilder()
        for (block in parse(text)) {
            val chunk = when (block) {
                is MdBlock.Heading -> block.text
                is MdBlock.Paragraph -> block.text
                is MdBlock.Code -> block.code
                is MdBlock.ListBlock -> block.items.joinToString(" • ") { it.text }
                is MdBlock.Quote -> block.text
                is MdBlock.Table -> block.header.joinToString(" | ")
                is MdBlock.Rule -> ""
            }
            if (chunk.isBlank()) continue
            val plain = inline(chunk).joinToString("") {
                when (it) {
                    is Inline.Text -> it.text
                    is Inline.Bold -> it.text
                    is Inline.Italic -> it.text
                    is Inline.Code -> it.text
                    is Inline.Link -> it.text
                    is Inline.Image -> it.alt.ifEmpty { "" }
                }
            }
            sb.append(plain.replace('\n', ' ')).append(' ')
            if (sb.length >= maxChars) break
        }
        return sb.toString().trim().take(maxChars)
    }
}
