package ir.maxv.securevault.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** The three ways a fingerprint prompt can end. */
sealed class BiometricOutcome {
    /** The sensor accepted a finger and the cipher is now usable. */
    data class Unlocked(val cipher: Cipher) : BiometricOutcome()

    /** Something we should tell the user about. */
    data class Failed(val message: String) : BiometricOutcome()

    /** The user backed out (or a single unreadable touch): nothing to say. */
    data object Cancelled : BiometricOutcome()
}

/**
 * Run one biometric prompt for [cipher] and hand the authorized cipher back.
 *
 * Only `BIOMETRIC_STRONG` is allowed (no device-credential fallback): the sealing key lives in the
 * keystore under that class, so a PIN wouldn't be able to open it anyway. A single misread does not
 * end the prompt — Android keeps it open and applies its own lockout after repeated failures, which
 * we surface instead of inventing our own counter.
 */
fun promptForBiometric(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String,
    negative: String,
    onResult: (BiometricOutcome) -> Unit,
) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val unlocked = result.cryptoObject?.cipher
            onResult(
                if (unlocked == null) BiometricOutcome.Failed("تأیید انجام شد ولی کلید برنگشت؛ با گذرواژه باز کن.")
                else BiometricOutcome.Unlocked(unlocked)
            )
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val message = errorMessage(errorCode)
            onResult(if (message == null) BiometricOutcome.Cancelled else BiometricOutcome.Failed(message))
        }

        override fun onAuthenticationFailed() {
            // one unreadable touch: the prompt stays up, Android counts the failures itself
        }
    }

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(negative)
        .setConfirmationRequired(false)
        .build()

    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        .authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

/** `null` means "the user cancelled — say nothing". */
private fun errorMessage(code: Int): String? = when (code) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_CANCELED,
    -> null

    BiometricPrompt.ERROR_LOCKOUT ->
        "تلاش‌های ناموفق زیاد شد؛ حدود ۳۰ ثانیه صبر کن یا گذرواژه بزن."

    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
        "اثر انگشت موقتاً قفل شده است؛ برای بازکردن قفل از گذرواژه استفاده کن."

    BiometricPrompt.ERROR_HW_UNAVAILABLE ->
        "حسگر اثر انگشت الان در دسترس نیست؛ با گذرواژه باز کن."

    BiometricPrompt.ERROR_NO_SPACE ->
        "حافظهٔ امن دستگاه پر است؛ با گذرواژه باز کن."

    BiometricPrompt.ERROR_TIMEOUT ->
        "مهلت تأیید تمام شد؛ دوباره امتحان کن."

    BiometricPrompt.ERROR_UNABLE_TO_PROCESS ->
        "خواندن اثر انگشت ناموفق بود؛ دوباره بزن."

    else -> "تأیید اثر انگشت انجام نشد (کد $code)؛ با گذرواژه باز کن."
}
