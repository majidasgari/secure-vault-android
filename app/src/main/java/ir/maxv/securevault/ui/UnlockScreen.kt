package ir.maxv.securevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.data.SyncProgress
import ir.maxv.securevault.data.VaultInfo

/** The unlock screen: fingerprint (when set up), one password field, the vault's parameters, sync. */
@Composable
fun UnlockScreen(
    info: VaultInfo?,
    busy: String?,
    progress: SyncProgress?,
    error: String?,
    biometricEnabled: Boolean,
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    // Ask for the finger once every time this screen appears (a fresh lock, or coming back after
    // the auto-lock timer), but never in a loop: the user can always fall back to the password.
    var prompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled && !prompted && busy == null) {
            prompted = true
            onBiometricUnlock()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("گشودن گنجینه", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "تنظیمات") }
        }

        info?.let { VaultInfoCard(it) }

        if (biometricEnabled) {
            Button(
                enabled = busy == null,
                onClick = onBiometricUnlock,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy != null) "در حال گشودن…" else "گشودن با اثر انگشت")
            }
            Text(
                "یا گذرواژهٔ اصلی را بزن:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("گذرواژهٔ اصلی") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (busy == null) onUnlock(password) }),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = busy == null && password.isNotEmpty(),
            onClick = { onUnlock(password) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy != null) "در حال گشودن…" else "گشودن")
        }

        OutlinedButton(onClick = onSync, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CloudSync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("همگام‌سازی با S3")
        }

        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(busy, style = MaterialTheme.typography.bodySmall)
            }
        }
        progress?.let { ProgressCard(it) }

        if (error != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onDismissError) { Text("باشه") }
                }
            }
        }

        Text(
            "گذرواژه فقط برای بازکردن کلید روی همین دستگاه است؛ جایی ذخیره نمی‌شود و " +
                "بازیابی هم ندارد. کلید با Argon2id ساخته می‌شود و همین گوشی باید چند لحظه و " +
                "حدود ۲۵۶ مگابایت حافظه آزاد داشته باشد.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (biometricEnabled) {
            Text(
                "اثر انگشت همین گوشی کلید گنجینه را نگه می‌دارد و پشت تأیید اثر انگشت قفل است؛ " +
                    "اگر اثر انگشت‌های دستگاه عوض شود آن کلید باطل می‌شود و دوباره گذرواژه لازم است.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun VaultInfoCard(info: VaultInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("محتوای این نسخهٔ محلی", style = MaterialTheme.typography.titleMedium)
            InfoLine("شناسهٔ والت", info.vaultId.take(8))
            InfoLine("یادداشت‌ها", "${info.notes} فایل در ${info.folders} پوشه")
            InfoLine("محرمانه", "${info.secrets} فایل با سطح secret/secretfile")
            InfoLine("دریافت‌شده", "${info.downloadedNotes} از ${info.notes} یادداشت متنی")
            InfoLine("حجم محلی", "${info.localBytes / 1024 / 1024} مگابایت")
            InfoLine(
                "کلید",
                "Argon2id · ${info.argonMemoryMib} مگابایت · t=${info.argonTimeCost} · p=${info.argonParallelism}"
            )
            if (info.lastSyncAt > 0) {
                InfoLine("آخرین همگام‌سازی", relativeTime(info.lastSyncAt))
            }
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProgressCard(progress: SyncProgress) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${progress.phase} — ${progress.done}/${progress.total}",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress.currentPath.isNotEmpty()) {
                Text(
                    progress.currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (progress.bytes > 0) {
                Text(
                    "${progress.bytes / 1024} کیلوبایت",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val diff = System.currentTimeMillis() - epochMillis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "همین حالا"
        minutes < 60 -> "$minutes دقیقه پیش"
        minutes < 24 * 60 -> "${minutes / 60} ساعت پیش"
        else -> "${minutes / (24 * 60)} روز پیش"
    }
}
