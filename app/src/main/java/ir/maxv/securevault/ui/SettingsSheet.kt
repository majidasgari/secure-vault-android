package ir.maxv.securevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.data.BiometricState
import ir.maxv.securevault.data.LocalSettings
import ir.maxv.securevault.data.VaultInfo

/** The fingerprint switch, with the one line that explains why it is or is not available. */
@Composable
fun FingerprintCard(
    state: BiometricState,
    vaultUnlocked: Boolean,
    busy: String?,
    onToggle: (Boolean) -> Unit,
) {
    val enabled = state == BiometricState.ON

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("گشودن با اثر انگشت", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    enabled = busy == null && state != BiometricState.UNAVAILABLE &&
                        (vaultUnlocked || enabled),
                    onCheckedChange = onToggle,
                )
            }
            Text(
                when {
                    state == BiometricState.UNAVAILABLE ->
                        "این گوشی حسگر اثر انگشت فعال ندارد یا در تنظیمات سیستم اثری ثبت نشده است."

                    state == BiometricState.STALE ->
                        "کلید اثر انگشت باطل شده است؛ با گذرواژه باز کن و دوباره فعال کن."

                    enabled ->
                        "فعال است: کلید گنجینه با کلید سخت‌افزاری همین گوشی و پشت تأیید اثر انگشت " +
                            "نگه داشته می‌شود. گذرواژه سر جایش می‌ماند."

                    !vaultUnlocked ->
                        "برای فعال‌کردن، اول گنجینه را با گذرواژه باز کن."

                    else ->
                        "با روشن‌کردن این گزینه کلید گنجینه پشت اثر انگشت همین گوشی مهر می‌شود و " +
                            "دیگر هر بار گذرواژه لازم نیست."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Settings live in the app, not in the vault: coordinates and the size policy are device-local,
 * and the keys are sealed with the keystore. The vault's own settings stay untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: LocalSettings,
    info: VaultInfo?,
    autoLockMinutes: Int,
    busy: String?,
    vaultUnlocked: Boolean,
    biometricState: BiometricState,
    onDismiss: () -> Unit,
    onSave: (LocalSettings, String?, String?, Int) -> Unit,
    onBiometricToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onDownloadAll: () -> Unit,
    onLock: () -> Unit,
    onWipe: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var endpoint by remember { mutableStateOf(settings.endpoint) }
    var region by remember { mutableStateOf(settings.region) }
    var bucket by remember { mutableStateOf(settings.bucket) }
    var prefix by remember { mutableStateOf(settings.prefix) }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var prefetchMb by remember { mutableStateOf((settings.prefetchMaxBytes / 1024 / 1024).toString()) }
    var attachments by remember { mutableStateOf(settings.prefetchAttachments) }
    var autoLock by remember { mutableStateOf(autoLockMinutes.toString()) }
    var confirmWipe by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("تنظیمات این دستگاه", style = MaterialTheme.typography.titleLarge)
            }

            info?.let { VaultInfoCard(it) }

            FingerprintCard(
                state = biometricState,
                vaultUnlocked = vaultUnlocked,
                busy = busy,
                onToggle = onBiometricToggle,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("باکت", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(region, { region = it }, label = { Text("Region") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(prefix, { prefix = it }, label = { Text("Prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(access, { access = it }, label = { Text("Access key (برای تغییر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        secret,
                        { secret = it },
                        label = { Text("Secret key (برای تغییر)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سیاست دریافت", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        prefetchMb,
                        { prefetchMb = it.filter { ch -> ch.isDigit() } },
                        label = { Text("سقف حجم یادداشت برای دریافت خودکار (مگابایت)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("دریافت پیوست‌ها هم در همگام‌سازی", modifier = Modifier.weight(1f))
                        Switch(checked = attachments, onCheckedChange = { attachments = it })
                    }
                    OutlinedTextField(
                        autoLock,
                        { autoLock = it.filter { ch -> ch.isDigit() } },
                        label = { Text("قفل خودکار پس از چند دقیقه در پس‌زمینه") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // The action area is a grid of equal halves, not one row of buttons that has to fit:
            // three wide buttons in a single Row used to crush the last one («قفل») until Compose
            // broke the word letter by letter — «ق ف ل» stacked on three lines.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = busy == null,
                    onClick = {
                        val mb = prefetchMb.toLongOrNull() ?: 2L
                        onSave(
                            settings.copy(
                                endpoint = endpoint.trim(),
                                region = region.trim(),
                                bucket = bucket.trim(),
                                prefix = prefix.trim(),
                                prefetchMaxBytes = mb * 1024 * 1024,
                                prefetchAttachments = attachments,
                            ),
                            access.trim().ifBlank { null },
                            secret.trim().ifBlank { null },
                            autoLock.toIntOrNull() ?: 10,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("ذخیره") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("بستن") }
            }

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("همگام‌سازی")
                }
                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("دریافت همه")
                }
            }

            OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("قفل گنجینه")
            }

            if (confirmWipe) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("نسخهٔ محلی و کلیدها پاک شوند؟ باکت دست‌نخورده می‌ماند.")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(onClick = onWipe, modifier = Modifier.weight(1f)) { Text("پاک کن") }
                            TextButton(
                                onClick = { confirmWipe = false },
                                modifier = Modifier.weight(1f),
                            ) { Text("بی‌خیال") }
                        }
                    }
                }
            } else {
                TextButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("پاک‌کردن نسخهٔ محلی", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
