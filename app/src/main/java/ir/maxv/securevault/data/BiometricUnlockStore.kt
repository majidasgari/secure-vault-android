package ir.maxv.securevault.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import ir.maxv.securevault.core.BiometricRecord
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How the fingerprint route stands on this device. */
enum class BiometricState {
    /** No usable sensor, or no fingerprint enrolled in the system settings. */
    UNAVAILABLE,

    /** Ready, but nothing sealed yet — the user must unlock once with the password first. */
    OFF,

    /** A sealed record exists and its keystore key still works. */
    ON,

    /** A record exists, but the keystore invalidated its key (the enrolled fingerprints changed). */
    STALE,
}

/**
 * The device-local record that lets a fingerprint take the place of the master password.
 *
 * The master key is *never* written in the clear and the phone's normal keystore key is not enough:
 * the sealing key is generated with `setUserAuthenticationRequired(true)` and
 * `setInvalidatedByBiometricEnrollment(true)`, so
 *
 * * a cipher built from it cannot be *used* (`doFinal`) outside a successful `BiometricPrompt`,
 * * adding or removing a fingerprint in the system settings destroys the key, which is exactly the
 *   behaviour we want — otherwise anyone who can enrol a finger on the unlocked phone could open
 *   the vault.
 *
 * The file therefore holds nothing usable by itself: `iv` + AES-256-GCM ciphertext of a
 * [BiometricRecord], and the key that would open it only exists inside the TEE/StrongBox, gated by
 * the sensor. Losing it costs the user one password unlock.
 */
class BiometricUnlockStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    // ── status ────────────────────────────────────────────────────────────────────
    fun state(): BiometricState {
        if (!hardwareReady()) return BiometricState.UNAVAILABLE
        if (!file.isFile) return BiometricState.OFF
        // a cipher that can be built means the sealing key is still alive; an invalidated one
        // (fingerprints changed) leaves nothing to build here
        return if (decryptCipher() != null) BiometricState.ON else BiometricState.STALE
    }

    /** Does this device have a fingerprint sensor with at least one fingerprint enrolled? */
    fun hardwareReady(): Boolean = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun isSealed(): Boolean = file.isFile

    // ── ciphers (the prompt authorizes them, not this class) ───────────────────────
    /** A cipher to seal the record with. Its `doFinal` only works inside a biometric prompt. */
    fun encryptCipher(): Cipher? = try {
        val key = key(create = true) ?: return null
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    } catch (e: Exception) {
        null
    }

    /**
     * A cipher to read the sealed record with, or `null` when there is nothing to read or the key
     * is gone (invalidated). `doFinal` needs a successful prompt first.
     */
    fun decryptCipher(): Cipher? {
        if (!file.isFile) return null
        val sealed = readSealed() ?: return null
        return try {
            val key = key(create = false) ?: return null
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed.iv))
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── the record itself ─────────────────────────────────────────────────────────
    /** Seal [record] with an already-authorized [cipher]. */
    fun write(cipher: Cipher, record: BiometricRecord): Boolean = try {
        val body = cipher.doFinal(record.encode().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteArray(1 + iv.size + body.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(body, 0, out, 1 + iv.size, body.size)
        writeAtomic(out)
        true
    } catch (e: Exception) {
        false
    }

    /** Open the sealed record with an already-authorized [cipher]; `null` on any mismatch. */
    fun read(cipher: Cipher): BiometricRecord? {
        val sealed = readSealed() ?: return null
        return try {
            val json = String(cipher.doFinal(sealed.body), Charsets.UTF_8)
            BiometricRecord.parse(json)
        } catch (e: Exception) {
            null
        }
    }

    /** Forget the fingerprint route: the record file and its keystore key both go. */
    fun clear() {
        file.delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
        try {
            val store = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // the file is gone either way; a leftover key is inert without a sealed record
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────
    private class Sealed(val iv: ByteArray, val body: ByteArray)

    private fun readSealed(): Sealed? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 3) {
                null
            } else {
                val ivSize = bytes[0].toInt()
                if (ivSize <= 0 || bytes.size <= 1 + ivSize) null
                else Sealed(bytes.copyOfRange(1, 1 + ivSize), bytes.copyOfRange(1 + ivSize, bytes.size))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** `create = false` never conjures a fresh key: a missing/invalidated one must stay missing. */
    private fun key(create: Boolean): SecretKey? {
        val store = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        try {
            (store.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
        } catch (e: Exception) {
            // invalidated key: treat as absent
            if (!create) return null
        }
        if (!create) return null

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "secure-vault-android-biometric"
        private const val FILE_NAME = "biometric.rec"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
