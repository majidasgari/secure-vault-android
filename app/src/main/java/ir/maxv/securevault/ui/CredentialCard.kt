package ir.maxv.securevault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.maxv.securevault.core.CredentialFields
import ir.maxv.securevault.core.Totp
import kotlinx.coroutines.delay

/**
 * The live fields of one credential entry: the rotating code and the values worth copying.
 *
 * Deliberately the same three things the desktop viewer and the browser add-on show, in the same
 * order and with the same wording: the code grouped for reading (`595 561`) with a countdown and a
 * progress bar, and — only when the entry actually has them — «رونوشت نام کاربری» and «رونوشت رمز
 * عبور». Nothing is fetched: the value comes from the note body that is already decrypted on the
 * device, the code is generated locally, and the copy button is the only place a value leaves the
 * screen (the clipboard, marked sensitive so the system's own preview does not show it).
 *
 * The code ticks once a second while the card is on screen; leaving the screen cancels the loop.
 */
@Composable
fun CredentialCard(fields: CredentialFields, modifier: Modifier = Modifier) {
    if (!fields.hasOtp && !fields.hasPassword && !fields.hasUsername) return

    var seconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    var status by remember { mutableStateOf("") }
    val context = LocalContext.current

    // One ticker for the card: the code and its countdown both read from `seconds`.
    LaunchedEffect(fields.otp) {
        while (true) {
            seconds = System.currentTimeMillis() / 1000
            delay(500)
        }
    }
    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(4000)
            status = ""
        }
    }

    val code = remember(fields.otp, seconds) { Totp.valueToCode(fields.otp, seconds) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (code != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("کد یکبارمصرف", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (code.live && code.remaining != null) {
                            "${code.remaining} ثانیه مانده"
                        } else {
                            "کد پشتیبان (بدون شمارش)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    code.display,
                    // the digits are an LTR run in an RTL screen: pin the direction so bidi can never
                    // reorder the groups around the space (the family rule: codes are always LTR)
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    textAlign = TextAlign.Left,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 30.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (code.live && code.remaining != null) {
                    CountdownBar(remaining = code.remaining, period = code.period)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        copySensitive(context, "کد یکبارمصرف", code.code)
                        status = "کد رونوشت شد."
                    }) { Text("رونوشت کد") }
                    if (fields.hasUsername) {
                        TextButton(onClick = {
                            copySensitive(context, "نام کاربری", fields.username)
                            status = "نام کاربری رونوشت شد."
                        }) { Text("رونوشت نام کاربری") }
                    }
                    if (fields.hasPassword) {
                        TextButton(onClick = {
                            copySensitive(context, "گذرواژه", fields.password)
                            status = "گذرواژه رونوشت شد."
                        }) { Text("رونوشت رمز عبور") }
                    }
                }
            } else {
                // No readable OTP: the copy buttons stand on their own.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fields.hasUsername) {
                        TextButton(onClick = {
                            copySensitive(context, "نام کاربری", fields.username)
                            status = "نام کاربری رونوشت شد."
                        }) { Text("رونوشت نام کاربری") }
                    }
                    if (fields.hasPassword) {
                        TextButton(onClick = {
                            copySensitive(context, "گذرواژه", fields.password)
                            status = "گذرواژه رونوشت شد."
                        }) { Text("رونوشت رمز عبور") }
                    }
                }
            }
            if (status.isNotEmpty()) {
                HorizontalDivider()
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** The remaining-seconds bar under a live code (the desktop viewer's 6px bar). */
@Composable
private fun CountdownBar(remaining: Int, period: Int) {
    val fraction = if (period <= 0) 0f else (remaining.toFloat() / period).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * Put one secret on the clipboard, marked sensitive.
 *
 * `EXTRA_IS_SENSITIVE` (the same key the platform reads since Android 13) keeps the system's
 * clipboard preview from printing a password or a code on screen; older releases ignore the extra.
 * The label is a plain field name, never the value.
 */
private fun copySensitive(context: Context, label: String, value: String) {
    if (value.isEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    clipboard.setPrimaryClip(clip)
}
