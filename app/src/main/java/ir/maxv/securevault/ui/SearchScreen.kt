package ir.maxv.securevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.core.Labels
import ir.maxv.securevault.core.PersianText
import ir.maxv.securevault.data.SearchOutcome
import ir.maxv.securevault.data.SearchResult
import ir.maxv.securevault.data.SyncProgress

/**
 * Literal search.
 *
 * The query is matched as a plain substring (after Persian letter/digit/diacritic
 * normalisation) against the decrypted bodies of every locally available note — not through a
 * token index, so a partial word, a path fragment or a code snippet all hit.
 */
@Composable
fun SearchScreen(
    results: SearchOutcome?,
    searching: Boolean,
    progress: SyncProgress?,
    missingNotes: Int,
    onSearch: (String, Boolean) -> Unit,
    onOpen: (SearchResult) -> Unit,
    onDownloadAll: () -> Unit,
) {
    var query by remember { mutableStateOf(results?.query ?: "") }
    var titlesOnly by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("جست‌وجوی لفظی") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query, titlesOnly) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = titlesOnly,
                    onClick = { titlesOnly = !titlesOnly },
                    label = { Text("فقط عنوان‌ها") },
                )
                Button(onClick = { onSearch(query, titlesOnly) }, enabled = query.isNotBlank()) {
                    Text("جست‌وجو")
                }
            }
            Text(
                "محتوای فایل‌های محرمانه جست‌وجو نمی‌شود؛ اگر عنوانشان بخورد، همان عنوان نشان داده می‌شود " +
                    "و متن را با تأیید صریح در خودِ یادداشت می‌بینی.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searching) {
                progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "اسکن ${p.done} از ${p.total} یادداشت…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("آماده‌سازی…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        results?.let { outcome ->
            if (outcome.notDownloaded > 0 || missingNotes > 0) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${outcome.notDownloaded} یادداشت روی این دستگاه نیست و در جست‌وجو نیامده است.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onDownloadAll) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("دریافت یادداشت‌های متنی")
                        }
                    }
                }
            }
            Text(
                "${outcome.results.size} نتیجه در ${outcome.scanned} یادداشت (${outcome.elapsedMs} میلی‌ثانیه)" +
                    if (!outcome.titlesOnly && outcome.titleOnly > 0) {
                        " — ${outcome.titleOnly} مورد فقط با عنوان پیدا شد"
                    } else {
                        ""
                    } +
                    if (outcome.skipped > 0) {
                        " — ${outcome.skipped} یادداشت بزرگ‌تر از ۴ مگابایت بود و اسکن نشد"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(outcome.results, key = { it.row.id }) { result ->
                    ResultRow(result, query) { onOpen(result) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, query: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.titleOnly) highlight(Labels.withEmoji(result.title, result.row.emoji), query)
                    else AnnotatedString(Labels.withEmoji(result.title, result.row.emoji)),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (result.titleOnly) {
                    Text(
                        "فقط عنوان",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (result.row.isSecret) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "${result.hits.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                result.row.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            when {
                // The body of a secret file is never decrypted for a search, so there is no snippet
                // to show — saying so beats an empty box the user would read as "no match".
                result.titleOnly && result.row.isSecret ->
                    Text(
                        "محتوای این فایل محرمانه است و جست‌وجو نمی‌شود؛ عنوانش خورده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                result.titleOnly -> Unit

                else -> HighlightedSnippet(result.snippet, query)
            }
        }
    }
}

/** The snippet with every hit painted, used for the body and for title-only hits. */
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    ir.maxv.securevault.core.LiteralSearch.findAll(text, query, maxHits = 40).forEach { hit ->
        val end = (hit.offset + hit.length).coerceAtMost(text.length)
        if (hit.offset < end) {
            addStyle(
                SpanStyle(background = HighlightColor, fontWeight = FontWeight.Bold),
                hit.offset,
                end,
            )
        }
    }
}

@Composable
private fun HighlightedSnippet(snippet: String, query: String) {
    val annotated: AnnotatedString = remember(snippet, query) { highlight(snippet, query) }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

private val HighlightColor = androidx.compose.ui.graphics.Color(0x66FFC95C)
