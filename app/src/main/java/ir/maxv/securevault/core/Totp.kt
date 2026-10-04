package ir.maxv.securevault.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One TOTP generator parsed out of a stored value. */
data class TotpSpec(
    val secret: String,
    val algorithm: String = Totp.DEFAULT_ALGORITHM,
    val digits: Int = Totp.DEFAULT_DIGITS,
    val period: Int = Totp.DEFAULT_PERIOD,
    val issuer: String = "",
    val label: String = "",
)

/** One generated (or stored) one-time code, ready to display. */
data class OtpCode(
    val code: String,
    val live: Boolean,
    val remaining: Int?,
    val period: Int,
    val digits: Int,
    val algorithm: String = Totp.DEFAULT_ALGORITHM,
    val issuer: String = "",
    val label: String = "",
) {
    /** `totp` for a generated code, `static` for a pasted backup code. */
    val kind: String get() = if (live) "totp" else "static"

    /**
     * The code as the eye should read it (`595 561`), never the value to copy.
     *
     * Grouping is presentation only: [code] stays the exact digits a site expects. Six digits split
     * 3+3, eight split 4+4, nine split 3+3+3; anything else is left untouched. A pasted backup code
     * is never regrouped — it does not rotate and must be typed exactly as stored.
     */
    val display: String
        get() {
            if (!live) return code
            if (code.isNotEmpty() && code.all { it.isDigit() } && code.length % 3 == 0) {
                return code.chunked(3).joinToString(" ")
            }
            if (code.isNotEmpty() && code.all { it.isDigit() } && code.length % 2 == 0) {
                val half = code.length / 2
                return code.substring(0, half) + " " + code.substring(half)
            }
            return code
        }
}

/**
 * One-time codes (RFC 6238) for the credential entries that carry an `otpauth://` value.
 *
 * The credential tree keeps its source's OTP field verbatim, so a stored value is one of three
 * things: an `otpauth://totp/…?secret=…` URI, a bare base32 seed, or a code someone pasted by hand
 * (a backup code). [valueToCode] turns the first two into a live code and returns the third
 * unchanged — it never invents a code for a value it does not understand.
 *
 * Mirrors the desktop client's `core/totp.py` field for field, so a code shown here is the code the
 * desktop and the browser add-on show for the same entry. Two properties matter:
 *
 * * **Nothing leaves the process.** The value is used for one HMAC and dropped: no network, no
 *   storage, no logging. This is the read-only client, so a code is only ever *shown*.
 * * **No Android imports.** The module is plain Kotlin/JVM, so the RFC vectors run as unit tests
 *   without a device.
 */
object Totp {

    const val DEFAULT_ALGORITHM = "SHA1"
    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD = 30

    /** Parameters outside these bounds are clamped (a wrong `digits` must not break a live code). */
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 10
    const val MIN_PERIOD = 5

    /** Value the migration writes when a field is empty — never an OTP. */
    const val PLACEHOLDER = "—"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val DIGITS_RE = Regex("^\\d{4,10}$")

    /**
     * A bare base32 seed: the alphabet is `A-Z` + `2-7`, so a *sentence* in capitals is technically
     * decodable and would silently produce a wrong code. Requiring 16+ characters with no spaces,
     * dashes or punctuation keeps prose out.
     */
    private val SECRET_RE = Regex("^[A-Z2-7]{16,}$")

    private val MACS = mapOf("SHA1" to "HmacSHA1", "SHA256" to "HmacSHA256", "SHA512" to "HmacSHA512")

    /** Decode an RFC 4648 base32 string, tolerating padding, spaces and dashes; null when invalid. */
    fun base32Decode(text: String): ByteArray? {
        val clean = text.replace(Regex("[\\s-]"), "").uppercase(Locale.ROOT).trimEnd('=')
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)
        var bits = 0
        var value = 0
        for (character in clean) {
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            value = (value shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.add(((value shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return if (out.isEmpty()) null else out.toByteArray()
    }

    /** Return true when `text` is plausibly a bare base32 seed (see `SECRET_RE`). */
    fun looksLikeSecret(text: String): Boolean =
        SECRET_RE.matches(text.trim().uppercase(Locale.ROOT).trimEnd('='))

    /**
     * Parse an `otpauth://totp/…` URI into a [TotpSpec]; null when it is not one.
     *
     * Only the `totp` type is understood (`hotp` needs a counter we do not store). Missing
     * parameters fall back to the RFC defaults; `digits`/`period` are clamped.
     */
    fun parseOtpauth(uri: String): TotpSpec? {
        val text = uri.trim()
        if (!text.lowercase(Locale.ROOT).startsWith("otpauth://")) return null
        val parsed = try {
            URI(text)
        } catch (error: Exception) {
            return null
        }
        if (!parsed.scheme.equals("otpauth", ignoreCase = true)) return null
        if (!(parsed.authority ?: "").equals("totp", ignoreCase = true)) return null
        val query = queryParams(parsed.rawQuery)
        val secret = query["secret"].orEmpty().trim()
        if (secret.isEmpty()) return null
        val algorithm = query["algorithm"].orEmpty().uppercase(Locale.ROOT)
            .takeIf { MACS.containsKey(it) } ?: DEFAULT_ALGORITHM
        val digits = positiveInt(query["digits"], DEFAULT_DIGITS).coerceIn(MIN_DIGITS, MAX_DIGITS)
        val period = positiveInt(query["period"], DEFAULT_PERIOD).coerceAtLeast(MIN_PERIOD)
        return TotpSpec(
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            period = period,
            issuer = query["issuer"].orEmpty(),
            label = decodePercent(parsed.rawPath.orEmpty().trimStart('/')),
        )
    }

    /** Generate the code for `spec`: `(code, secondsRemaining)`. Throws on an unusable seed. */
    fun codeFor(spec: TotpSpec, atSeconds: Long): Pair<String, Int> {
        val key = base32Decode(spec.secret) ?: throw IllegalArgumentException("invalid_base32")
        val mac = Mac.getInstance(MACS[spec.algorithm] ?: "HmacSHA1")
        mac.init(SecretKeySpec(key, mac.algorithm))
        val digest = mac.doFinal(counterBytes(atSeconds / spec.period))
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        var modulo = 1L
        repeat(spec.digits) { modulo *= 10 }
        val code = (binary % modulo).toString().padStart(spec.digits, '0')
        val remaining = spec.period - (atSeconds % spec.period).toInt()
        return code to remaining
    }

    /**
     * Turn one stored OTP value into something to display, or null when it is not an OTP.
     *
     * Order: `otpauth://` URI → live; a 4–10 digit number → a *static* code (returned as-is, no
     * countdown); a bare base32 seed → live with the RFC defaults. Anything else is not an OTP.
     */
    fun valueToCode(value: String, atSeconds: Long = System.currentTimeMillis() / 1000): OtpCode? {
        val text = value.trim()
        if (text.isEmpty() || text == PLACEHOLDER) return null
        val spec = parseOtpauth(text)
        if (spec != null) {
            val generated = try {
                codeFor(spec, atSeconds)
            } catch (error: Exception) {
                return null
            }
            return OtpCode(
                code = generated.first,
                live = true,
                remaining = generated.second,
                period = spec.period,
                digits = spec.digits,
                algorithm = spec.algorithm,
                issuer = spec.issuer,
                label = spec.label,
            )
        }
        if (DIGITS_RE.matches(text)) {
            return OtpCode(
                code = text,
                live = false,
                remaining = null,
                period = DEFAULT_PERIOD,
                digits = text.length,
            )
        }
        if (looksLikeSecret(text)) {
            val seed = TotpSpec(secret = text)
            val generated = try {
                codeFor(seed, atSeconds)
            } catch (error: Exception) {
                return null
            }
            return OtpCode(
                code = generated.first,
                live = true,
                remaining = generated.second,
                period = seed.period,
                digits = seed.digits,
                algorithm = seed.algorithm,
            )
        }
        return null
    }

    /** The raw OTP value a credential body stores ("" when the body has no OTP field). */
    fun otpValueFromBody(body: String): String = Credentials.parse(body).otp

    /** The live code of one credential body, or null when the body carries no readable OTP. */
    fun otpForBody(body: String, atSeconds: Long = System.currentTimeMillis() / 1000): OtpCode? =
        valueToCode(otpValueFromBody(body), atSeconds)

    /** Big-endian 8-byte counter for the given time step. */
    private fun counterBytes(counter: Long): ByteArray {
        val out = ByteArray(8)
        var value = counter
        for (index in 7 downTo 0) {
            out[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return out
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
        val query = rawQuery.orEmpty()
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val split = pair.indexOf('=')
            val rawKey = if (split < 0) pair else pair.substring(0, split)
            val rawValue = if (split < 0) "" else pair.substring(split + 1)
            val key = decodePercent(rawKey)
            if (!out.containsKey(key)) out[key] = decodePercent(rawValue)
        }
        return out
    }

    /** Percent-decode like Python's `unquote`: a `+` stays a `+`. */
    private fun decodePercent(text: String): String = try {
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (error: Exception) {
        text
    }

    private fun positiveInt(value: String?, fallback: Int): Int {
        val number = value?.trim()?.toIntOrNull() ?: return fallback
        return if (number > 0) number else fallback
    }
}
