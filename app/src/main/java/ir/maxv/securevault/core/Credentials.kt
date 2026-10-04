package ir.maxv.securevault.core

import java.util.Locale

/** The fields of one credential entry — the same ones the desktop client and the add-on read. */
data class CredentialFields(
    val site: String = "",
    val category: String = "",
    val username: String = "",
    val password: String = "",
    val otp: String = "",
    val url: String = "",
) {
    val hasPassword: Boolean get() = password.isNotEmpty()
    val hasOtp: Boolean get() = otp.isNotEmpty()
    val hasUsername: Boolean get() = username.isNotEmpty()
}

/**
 * The credential body parser, mirroring the desktop client's `core/credentials.py`.
 *
 * `/رمزها` entries are `secretfile`s whose body is a `label: value` block (the migration wrote
 * them), so finding the live fields — a user name to copy, a password, an `otpauth://` value — is a
 * line scan with the *same* labels the desktop uses. Getting this wrong would show a code for the
 * wrong entry, which is worse than showing nothing, so the label sets are kept verbatim:
 *
 * * one line may hold several fields separated by `|` (`سایت: x | دسته: y`);
 * * the first occurrence of a label wins;
 * * a line starting with `##` ends the field region (everything below is free-text notes);
 * * a label is matched exactly first, then by keyword — which is what catches the migration's
 *   `کد یکبارمصرف (otp): …` line (its key *contains* `otp`).
 */
object Credentials {

    val USERNAME_LABELS = listOf(
        "نام کاربری", "نامکاربری", "نام کاربری/ایمیل", "یوزرنیم", "username", "user name", "user", "login",
    )
    val PASSWORD_LABELS = listOf("گذرواژه", "گذر واژه", "رمز عبور", "پسورد", "password", "pass")
    val OTP_LABELS = listOf(
        "کد یکبارمصرف", "کد یک بار مصرف", "کد یکبار مصرف", "otp", "totp", "2fa", "2fa code",
    )
    val SITE_LABELS = listOf("سایت", "وبسایت", "site")
    val CATEGORY_LABELS = listOf("دسته", "دسته بندی", "category", "group")
    val URL_LABELS = listOf("آدرس", "نشانی", "آدرس سایت", "url", "website", "web site", "site url", "link")

    private val USERNAME_KEYWORDS = listOf("username", "user name", "نام کاربری", "email", "ایمیل")
    private val PASSWORD_KEYWORDS = listOf("password", "pass", "گذرواژه", "رمز عبور")
    private val OTP_KEYWORDS = listOf("otp", "totp", "2fa", "یکبارمصرف", "یک بار مصرف")
    private val URL_KEYWORDS = listOf("url", "آدرس", "نشانی", "website")

    /** Markers the migration writes when a field has no value. */
    private val EMPTY_MARKERS = setOf("", "—", "-")

    private const val SECTION_MARKER = "##"

    /** Parse one credential body into its fields. Never logs, never throws. */
    fun parse(body: String): CredentialFields {
        val fields = LinkedHashMap<String, String>()
        var inNotes = false
        for (raw in body.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(SECTION_MARKER)) {
                inNotes = true
                continue
            }
            if (inNotes) continue
            if (line.startsWith("#")) continue
            for (segment in line.split("|")) {
                val split = segment.indexOf(':')
                if (split < 0) continue
                val key = clean(segment.substring(0, split)).lowercase(Locale.ROOT)
                val value = clean(segment.substring(split + 1))
                if (key.isNotEmpty() && !fields.containsKey(key)) fields[key] = value
            }
        }

        fun firstOf(labels: List<String>): String {
            for (label in labels) {
                val value = fields[label.lowercase(Locale.ROOT)]
                if (!value.isNullOrEmpty()) return value
            }
            return ""
        }

        fun byKeyword(keywords: List<String>): String {
            for ((key, value) in fields) {
                if (value.isNotEmpty() && keywords.any { key.contains(it) }) return value
            }
            return ""
        }

        return CredentialFields(
            site = firstOf(SITE_LABELS),
            category = firstOf(CATEGORY_LABELS),
            username = firstOf(USERNAME_LABELS).ifEmpty { byKeyword(USERNAME_KEYWORDS) },
            password = firstOf(PASSWORD_LABELS).ifEmpty { byKeyword(PASSWORD_KEYWORDS) },
            otp = firstOf(OTP_LABELS).ifEmpty { byKeyword(OTP_KEYWORDS) },
            url = firstOf(URL_LABELS).ifEmpty { byKeyword(URL_KEYWORDS) },
        )
    }

    private fun clean(value: String): String {
        val text = value.trim()
        return if (EMPTY_MARKERS.contains(text.lowercase(Locale.ROOT))) "" else text
    }
}
