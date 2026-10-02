package ir.maxv.securevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.core.Labels
import ir.maxv.securevault.data.RecentItem

/**
 * «اخیراً باز شده» — the way back to the handful of files that are actually used.
 *
 * Ordered by the last open (the honest signal: what he reached for most recently), with the open
 * count shown only when a file has been opened more than once. Bounded by design: a few rows, then
 * an explicit «بیشتر» — a list that grows to hundreds stops being a shortcut.
 */
@Composable
fun RecentFilesCard(
    items: List<RecentItem>,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (items.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) items else items.take(COLLAPSED_ROWS)
    val hidden = items.size - shown.size

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("اخیراً باز شده", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            for (item in shown) {
                RecentRow(item = item, onClick = { onOpen(item.path) })
            }

            if (hidden > 0 || expanded) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "کمتر" else "$hidden فایل قدیمی‌تر")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "فقط روی همین گوشی نگه داشته می‌شود و در والت نوشته نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("پاک کردن")
                }
            }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Labels.withEmoji(item.title, item.emoji),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isSecret) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "محرمانه",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                buildString {
                    append(if (item.parent.isEmpty()) "خانه" else item.parent)
                    append(" · ")
                    append(relativeTime(item.openedAt))
                    if (item.opens > 1) {
                        append(" · ")
                        append("${item.opens} بار")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!item.downloaded) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Download,
                contentDescription = "هنوز دریافت نشده",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** How many rows the section shows before asking to expand — a shortcut, not a list. */
private const val COLLAPSED_ROWS = 5
