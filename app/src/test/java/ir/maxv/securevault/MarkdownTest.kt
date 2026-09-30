package ir.maxv.securevault

import ir.maxv.securevault.core.Inline
import ir.maxv.securevault.core.Markdown
import ir.maxv.securevault.core.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The markdown reader: what the vault actually contains, including the awkward cases. */
class MarkdownTest {

    @Test
    fun `indented fences are code, not prose`() {
        val text = "1. روی سرور:\n\n    ```bash\n    apt install nfs-kernel-server\n    ```\n\nتمام"
        val blocks = Markdown.parse(text)
        val code = blocks.filterIsInstance<MdBlock.Code>()
        assertEquals(1, code.size)
        assertEquals("bash", code[0].language)
        assertEquals("apt install nfs-kernel-server", code[0].code)
        // the fence markers and the language label must not survive as text
        val prose = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("\n") { it.text }
        assertTrue(!prose.contains("```"))
        assertTrue(!prose.contains("bash"))
    }

    @Test
    fun `an unterminated fence still renders as code`() {
        val blocks = Markdown.parse("متن\n\n```sh\nls -la\npwd\n")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("ls -la\npwd", code.code)
    }

    @Test
    fun `headings, lists, quotes, tables and rules are recognised`() {
        val text = """
            ## خریدها

            - چادر
            - چراغ قوه

            1. بلیت
            2. سوغاتی

            > یادم باشد یخساز

            ---

            | روز | جا |
            |---|---|
            | یک | رامسر |
        """.trimIndent()
        val blocks = Markdown.parse(text)
        assertEquals(2, blocks.filterIsInstance<MdBlock.Heading>().single().level)
        val bullets = blocks.filterIsInstance<MdBlock.ListBlock>().first { !it.ordered }
        assertEquals(listOf("چادر", "چراغ قوه"), bullets.items.map { it.text })
        val ordered = blocks.filterIsInstance<MdBlock.ListBlock>().first { it.ordered }
        assertEquals(listOf("بلیت", "سوغاتی"), ordered.items.map { it.text })
        val quote = blocks.filterIsInstance<MdBlock.Quote>().single()
        assertEquals("یادم باشد یخساز", quote.text)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("روز", "جا"), table.header)
        assertEquals(listOf("یک", "رامسر"), table.rows.single())
        assertTrue(blocks.any { it == MdBlock.Rule })
    }

    @Test
    fun `inline emphasis, code and links are split into runs`() {
        val parts = Markdown.inline("این **پررنگ** و *کج* و `کد` و [پیوند](https://example.com) است")
        assertTrue(parts.any { it is Inline.Bold && it.text == "پررنگ" })
        assertTrue(parts.any { it is Inline.Italic && it.text == "کج" })
        assertTrue(parts.any { it is Inline.Code && it.text == "کد" })
        assertTrue(parts.any { it is Inline.Link && it.url == "https://example.com" })
    }

    @Test
    fun `images are extracted with their url, angle brackets included`() {
        val parts = Markdown.inline("![نقشه](<../../../../assets/abc123.png>)")
        val image = parts.filterIsInstance<Inline.Image>().single()
        assertEquals("نقشه", image.alt)
        assertEquals("../../../../assets/abc123.png", image.url)
    }

    @Test
    fun `a lone dollar or underscore does not swallow the rest of the line`() {
        val parts = Markdown.inline("قیمت 5$ و snake_case_name و ایمیل a@b.com")
        assertEquals("قیمت 5$ و snake_case_name و ایمیل a@b.com", parts.filterIsInstance<Inline.Text>().joinToString("") { it.text })
    }

    @Test
    fun `plain text extraction drops the markup for list previews`() {
        val plain = Markdown.toPlainText("## عنوان\n\n**پررنگ** و `کد`\n\n- یک\n- دو")
        assertTrue(plain.contains("عنوان"))
        assertTrue(plain.contains("پررنگ"))
        assertTrue(!plain.contains("**"))
        assertTrue(!plain.contains("`"))
    }

    @Test
    fun `the fixture note parses into the expected shape`() {
        val text = Fixture.files().first { it.getString("path").contains("سفر") }.getString("text")
        val blocks = Markdown.parse(text)
        assertTrue(blocks.filterIsInstance<MdBlock.Heading>().any { it.level == 1 })
        assertTrue(blocks.filterIsInstance<MdBlock.Code>().any { it.language == "bash" })
        assertTrue(blocks.filterIsInstance<MdBlock.Quote>().isNotEmpty())
        assertTrue(blocks.filterIsInstance<MdBlock.Table>().isNotEmpty())
        assertTrue(blocks.filterIsInstance<MdBlock.ListBlock>().any { it.ordered })
        assertTrue(blocks.filterIsInstance<MdBlock.ListBlock>().any { !it.ordered })
    }
}
