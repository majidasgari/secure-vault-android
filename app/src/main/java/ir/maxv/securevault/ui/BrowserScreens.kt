package ir.maxv.securevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.core.Credentials
import ir.maxv.securevault.core.PersianText
import ir.maxv.securevault.core.VaultPaths
import ir.maxv.securevault.data.VaultRow

/** The note list of one folder: breadcrumbs, the folder's own note (the map), then its children. */
@Composable
fun FolderScreen(
    folder: String,
    children: List<VaultRow>,
    folderNote: String?,
    notePreview: (VaultRow) -> String?,
    downloaded: (VaultRow) -> Boolean,
    onOpenFolder: (String) -> Unit,
    onOpenNote: (VaultRow) -> Unit,
    onCrumb: (String) -> Unit,
    header: @Composable () -> Unit,
    busy: String?,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { header() }
        item { Breadcrumbs(folder, onCrumb) }
        folderNote?.takeIf { it.isNotBlank() }?.let { note ->
            item { FolderNoteCard(folder, note) }
        }
        if (busy != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(busy, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (children.isEmpty()) {
            item {
                Text(
                    "این پوشه خالی است.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(children, key = { it.id }) { row ->
            EntryRow(
                row = row,
                preview = notePreview(row),
                available = if (row.isDir) true else downloaded(row),
                onClick = { if (row.isDir) onOpenFolder(row.path) else onOpenNote(row) },
            )
        }
    }
}

@Composable
private fun Breadcrumbs(folder: String, onCrumb: (String) -> Unit) {
    val parts = VaultPaths.normalize(folder).split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onCrumb("") }) { Text("خانه") }
        var prefix = ""
        parts.forEachIndexed { index, part ->
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            val target = prefix
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (index == parts.lastIndex) {
                Text(
                    part,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                TextButton(onClick = { onCrumb(target) }) {
                    Text(part, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FolderNoteCard(folder: String, note: String) {
    var expanded by remember(folder) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("دفترچهٔ این پوشه", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "بستن" else "بیشتر")
                }
            }
            MarkdownView(
                text = if (expanded) note else note.take(600),
                imageLoader = { null },
                onNeedImage = {},
            )
        }
    }
}

@Composable
private fun EntryRow(
    row: VaultRow,
    preview: String?,
    available: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (row.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (row.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.isSecret) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "محرمانه",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                val subtitle = when {
                    row.isDir -> "پوشه"
                    preview?.isNotBlank() == true -> preview
                    else -> "${row.size / 1024} کیلوبایت"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (!row.isDir && !available) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "هنوز دریافت نشده",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** One note, rendered. Secrets must be revealed explicitly before anything is read. */
@Composable
fun NoteScreen(
    view: VaultViewModel.NoteView,
    highlight: String?,
    imageLoader: (String) -> ByteArray?,
    onNeedImage: (String) -> Unit,
    onReveal: () -> Unit,
    onDownload: () -> Unit,
) {
    var raw by remember(view.row.path) { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(view.row.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        view.row.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text("${view.row.size / 1024} KB") })
                        AssistChip(onClick = {}, label = { Text(view.row.sensitivity) })
                        view.tags.take(6).forEach { tag ->
                            AssistChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                    view.note?.takeIf { it.isNotBlank() }?.let { note ->
                        HorizontalDivider()
                        Text("یادداشت فایل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { raw = !raw }) {
                            Text(if (raw) "نمایش قالب‌بندی‌شده" else "متن خام")
                        }
                    }
                }
            }
        }

        // A credential entry's live fields — the rotating code, and the values worth copying — are
        // read from the note body that is already decrypted here. Nothing extra is fetched, and the
        // card appears for a secret entry exactly like the desktop viewer's, not for prose notes.
        val body = view.text
        if (view.row.isSecret && body != null && body.isNotBlank()) {
            val fields = Credentials.parse(body)
            if (fields.hasOtp || fields.hasPassword || fields.hasUsername) {
                item { CredentialCard(fields) }
            }
        }

        when {
            view.locked -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("این فایل محرمانه است.", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "محتوای فایل‌های secret و secretfile فقط با تأیید صریح خودت نشان داده می‌شود، " +
                                "و در جست‌وجوی متنی هرگز نمی‌آید.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(onClick = onReveal, modifier = Modifier.weight(1f)) { Text("نمایش") }
                            OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("دریافت")
                            }
                        }
                    }
                }
            }

            view.error != null -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(view.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onDownload) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("تلاش دوباره")
                        }
                    }
                }
            }

            view.text == null -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("در حال دریافت و رمزگشایی…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> item {
                val text = view.text ?: ""
                if (raw || view.row.isSecret) {
                    // a secret body is never previewed as markdown, exactly like the desktop UI
                    SelectionContainer {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                } else {
                    MarkdownView(
                        text = text,
                        imageLoader = imageLoader,
                        onNeedImage = onNeedImage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        highlight?.takeIf { it.isNotBlank() }?.let { needle ->
            item { HighlightSummary(view.text.orEmpty(), needle) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HighlightSummary(text: String, needle: String) {
    val hits = remember(text, needle) { ir.maxv.securevault.core.LiteralSearch.findAll(text, needle, maxHits = 10) }
    if (hits.isEmpty()) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("جای «$needle» در این یادداشت", style = MaterialTheme.typography.titleMedium)
            hits.forEach { hit ->
                Row {
                    Text(
                        "خط ${hit.line}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        hit.lineText.trim().take(160),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
