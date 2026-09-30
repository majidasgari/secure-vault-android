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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import ir.maxv.securevault.data.LocalSettings

/**
 * First run: the bucket coordinates and the S3 keys.
 *
 * The coordinates are the same ones the desktop client stores in the vault; the keys are never
 * written into the vault either there or here.
 */
@Composable
fun SetupScreen(
    initial: LocalSettings,
    hasCredentials: Boolean,
    busy: String?,
    error: String?,
    onTest: (LocalSettings, String, String) -> Unit,
    onSync: (LocalSettings, String, String) -> Unit,
    onDismissError: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var region by remember { mutableStateOf(initial.region) }
    var bucket by remember { mutableStateOf(initial.bucket) }
    var prefix by remember { mutableStateOf(initial.prefix) }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    fun settings() = initial.copy(
        endpoint = endpoint.trim(),
        region = region.trim(),
        bucket = bucket.trim(),
        prefix = prefix.trim(),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("گنجینهٔ همراه", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "نسخهٔ اندروید گنجینه: فقط خواندن. این برنامه هیچ چیزی در باکت نمی‌نویسد و قفل نوشتن " +
                "را هم برنمی‌دارد؛ هرچه در دسکتاپ ساخته می‌شود، اینجا فقط خوانده می‌شود.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("نشانی باکت", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://s3.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Region") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Bucket") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("Prefix") },
                    placeholder = { Text("sync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("کلیدهای دسترسی", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasCredentials) "کلیدها ذخیره شده‌اند؛ برای تغییر، مقدار تازه وارد کن."
                    else "کلیدها با کلید سخت‌افزاری همین دستگاه رمز می‌شوند و داخل والت نمی‌روند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = access,
                    onValueChange = { access = it },
                    label = { Text("Access key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Secret key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (error != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDismissError) { Text("باشه") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = busy == null,
                onClick = { onTest(settings(), access.trim(), secret.trim()) },
            ) { Text("تست اتصال") }
            Button(
                enabled = busy == null,
                onClick = { onSync(settings(), access.trim(), secret.trim()) },
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("ذخیره و همگام‌سازی")
            }
        }

        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(busy, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}
