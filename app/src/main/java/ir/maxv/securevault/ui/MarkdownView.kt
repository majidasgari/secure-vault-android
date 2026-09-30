package ir.maxv.securevault.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.maxv.securevault.core.Inline
import ir.maxv.securevault.core.Markdown
import ir.maxv.securevault.core.MdBlock
import ir.maxv.securevault.core.PersianText

/**
 * Markdown reader.
 *
 * Direction rule (Max's standing rule for every text surface): a block that contains even one
 * Persian/Arabic letter is laid out RTL and right-aligned; a purely Latin block stays LTR.
 * Code is *never* flipped — it is always LTR, monospaced and horizontally scrollable.
 */
@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    imageLoader: (String) -> ByteArray?,
    onNeedImage: (String) -> Unit,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block)
                is MdBlock.Paragraph -> ParagraphBlock(block, imageLoader, onNeedImage)
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.ListBlock -> ListBlock(block)
                is MdBlock.Quote -> QuoteBlock(block)
                is MdBlock.Table -> TableBlock(block)
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    val size = when (block.level) {
        1 -> 23.sp
        2 -> 20.sp
        3 -> 18.sp
        else -> 16.5.sp
    }
    DirectionalBlock(block.text) {
        Spacer(Modifier.height(4.dp))
        InlineText(
            block.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = size,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun ParagraphBlock(
    block: MdBlock.Paragraph,
    imageLoader: (String) -> ByteArray?,
    onNeedImage: (String) -> Unit,
) {
    val parts = remember(block.text) { Markdown.inline(block.text) }
    if (parts.none { it is Inline.Image }) {
        DirectionalBlock(block.text) {
            InlineParts(parts, MaterialTheme.typography.bodyLarge)
        }
        return
    }
    // A paragraph that carries an image is rendered as runs: text lines and pictures.
    val segments = remember(block.text) { splitSegments(parts) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Run -> DirectionalBlock(segment.plain) {
                    Text(
                        segment.annotated,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is Segment.Picture -> VaultImage(segment.url, segment.alt, imageLoader, onNeedImage)
            }
        }
    }
}

private sealed class Segment {
    data class Run(val plain: String, val annotated: AnnotatedString) : Segment()
    data class Picture(val url: String, val alt: String) : Segment()
}

/** Split inline parts into text runs and standalone pictures (pure, no composition). */
private fun splitSegments(parts: List<Inline>): List<Segment> {
    val out = ArrayList<Segment>()
    var builder = AnnotatedString.Builder()
    fun flush() {
        if (builder.length > 0) {
            val built = builder.toAnnotatedString()
            out.add(Segment.Run(built.text, built))
            builder = AnnotatedString.Builder()
        }
    }
    parts.forEach { part ->
        if (part is Inline.Image) {
            flush()
            out.add(Segment.Picture(part.url, part.alt))
        } else {
            appendInline(builder, part)
        }
    }
    flush()
    return out
}

@Composable
private fun ListBlock(block: MdBlock.ListBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { index, item ->
            val marker = if (block.ordered) "${index + 1}." else "•"
            DirectionalBlock(item.text) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        marker,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    Box(Modifier.weight(1f).padding(start = (item.level * 14).dp)) {
                        InlineText(item.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote) {
    DirectionalBlock(block.text) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(IntrinsicHeightPlaceholder)
                    .background(MaterialTheme.colorScheme.secondary)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                InlineText(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    ),
                )
            }
        }
    }
}

/** A quote's stripe cannot measure the row; this keeps the layout simple and stable. */
private val IntrinsicHeightPlaceholder = 24.dp

@Composable
private fun TableBlock(block: MdBlock.Table) {
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(1.dp)
    ) {
        Row {
            block.header.forEach { cell ->
                TableCell(cell, header = true)
            }
        }
        block.rows.forEach { row ->
            Row {
                for (index in 0 until columnCount) {
                    TableCell(row.getOrElse(index) { "" }, header = false)
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean) {
    val rtl = PersianText.isRtlText(text)
    Box(
        Modifier
            .widthIn(min = 96.dp, max = 260.dp)
            .background(if (header) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            InlineText(
                text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // the language badge is always LTR (a code label is not prose)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("رونوشت", style = MaterialTheme.typography.bodySmall)
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                block.code,
                style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 20.sp),
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun VaultImage(
    url: String,
    alt: String,
    imageLoader: (String) -> ByteArray?,
    onNeedImage: (String) -> Unit,
) {
    val bitmap = remember(url) {
        imageLoader(url)?.let { bytes ->
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = alt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                alt.ifBlank { "تصویر" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onNeedImage(url) }) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("دریافت", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Wrap a block in the direction its own content asks for. */
@Composable
private fun DirectionalBlock(text: String, content: @Composable () -> Unit) {
    val rtl = PersianText.isRtlText(text)
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle) {
    val parts = remember(text) { Markdown.inline(text) }
    val builder = AnnotatedString.Builder()
    parts.forEach { appendInline(builder, it) }
    Text(
        builder.toAnnotatedString(),
        style = style,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InlineParts(parts: List<Inline>, style: TextStyle) {
    val builder = AnnotatedString.Builder()
    parts.forEach { appendInline(builder, it) }
    Text(
        builder.toAnnotatedString(),
        style = style,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun appendInline(builder: AnnotatedString.Builder, part: Inline) {
    when (part) {
        is Inline.Text -> builder.append(part.text)
        is Inline.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) }
        is Inline.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.text) }
        is Inline.Code -> builder.withStyle(
            SpanStyle(fontFamily = MonoFamily, background = CodeBackground)
        ) { append(part.text) }
        is Inline.Link -> builder.withLink(LinkAnnotation.Url(part.url)) {
            withStyle(SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline)) {
                append(part.text.ifBlank { part.url })
            }
        }
        is Inline.Image -> builder.append(part.alt)
    }
}

private val CodeBackground = androidx.compose.ui.graphics.Color(0x3322AAFF)
private val LinkColor = androidx.compose.ui.graphics.Color(0xFF7FB2FF)
