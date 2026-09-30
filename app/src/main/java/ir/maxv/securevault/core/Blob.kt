package ir.maxv.securevault.core

/**
 * The vault's self-describing blob format (SPEC/01 §4), read-only.
 *
 * ```
 * offset size field
 * 0      4    magic b"SVLT"
 * 4      1    format version = 1
 * 5      1    kdf id (1 = argon2id, 2 = pbkdf2-sha512)
 * 6      1    flags (bit0 = payload stored plain)
 * 7      1    reserved = 0
 * 8      16   per-blob HKDF salt (a keyed MAC of the header when the plain flag is set)
 * 24     12   GCM nonce (unused when plain)
 * 36     ...  ciphertext||tag, or the raw payload when plain
 * ```
 */
object Blob {

    val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 36
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val KEY_SIZE = 32

    const val KDF_ARGON2ID = 1
    const val KDF_PBKDF2 = 2

    /** The desktop client's canary blob id and its expected plaintext. */
    const val CANARY_BLOB_ID = "canary"
    val CANARY_PLAINTEXT = "secure-vault".toByteArray(Charsets.UTF_8)

    /** The blob id of the encrypted content store. */
    const val STORE_BLOB_ID = "secure.store"

    data class Header(
        val version: Int,
        val kdfId: Int,
        val plain: Boolean,
        val salt: ByteArray,
        val nonce: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Header && other.version == version &&
            other.kdfId == kdfId && other.plain == plain &&
            other.salt.contentEquals(salt) && other.nonce.contentEquals(nonce)

        override fun hashCode(): Int = salt.contentHashCode() * 31 + nonce.contentHashCode()
    }

    fun header(blob: ByteArray): Header {
        if (blob.size < HEADER_SIZE) throw TamperDetected("blob_truncated")
        if (!blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw TamperDetected("bad_magic")
        val version = blob[4].toInt() and 0xFF
        val kdfId = blob[5].toInt() and 0xFF
        val flags = blob[6].toInt() and 0xFF
        val reserved = blob[7].toInt() and 0xFF
        if (version != FORMAT_VERSION) throw TamperDetected("unsupported_version")
        if (kdfId != KDF_ARGON2ID && kdfId != KDF_PBKDF2) throw TamperDetected("unsupported_kdf")
        if (reserved != 0 || (flags and 0x01.inv()) != 0) throw TamperDetected("unsupported_flags")
        return Header(
            version = version,
            kdfId = kdfId,
            plain = (flags and 0x01) != 0,
            salt = blob.copyOfRange(8, 8 + SALT_SIZE),
            nonce = blob.copyOfRange(8 + SALT_SIZE, HEADER_SIZE),
        )
    }

    /** True for a well-formed blob whose payload is stored unencrypted (> plain threshold). */
    fun isPlainBlob(blob: ByteArray): Boolean = try {
        header(blob).plain
    } catch (e: TamperDetected) {
        false
    }

    /** Per-blob AES key: HKDF-SHA256 over the master key, bound to the blob id. */
    fun fileKey(masterKey: ByteArray, blobId: String, salt: ByteArray): ByteArray =
        Crypto.hkdfSha256(
            ikm = masterKey,
            salt = salt,
            info = ("secure-vault/file/" + blobId).toByteArray(Charsets.UTF_8),
            length = KEY_SIZE,
        )

    fun aad(blobId: String, sensitivity: String): ByteArray =
        ("sv/" + blobId + "/" + sensitivity).toByteArray(Charsets.UTF_8)

    /**
     * Decrypt [blob] for [blobId] at [sensitivity].
     *
     * @throws TamperDetected whenever the header is malformed or the payload does not authenticate.
     */
    fun decrypt(masterKey: ByteArray, blobId: String, blob: ByteArray, sensitivity: String): ByteArray {
        val h = header(blob)
        val payload = blob.copyOfRange(HEADER_SIZE, blob.size)
        val aad = aad(blobId, sensitivity)
        if (h.plain) {
            // Plain payloads carry no AEAD tag: the header is authenticated with a truncated
            // HMAC-SHA256 kept in the salt field.
            val mac = Crypto.hmacSha256(masterKey, blob.copyOfRange(0, 8) + h.nonce + aad)
            if (!Crypto.constantTimeEquals(h.salt, mac.copyOfRange(0, SALT_SIZE))) {
                throw TamperDetected("plain_header_mac_mismatch")
            }
            return payload
        }
        return Crypto.aesGcmDecrypt(fileKey(masterKey, blobId, h.salt), h.nonce, payload, aad)
    }

    /** Verify the master key against the vault canary; never throws. */
    fun checkCanary(masterKey: ByteArray, canary: ByteArray): Boolean = try {
        decrypt(masterKey, CANARY_BLOB_ID, canary, "normal").contentEquals(CANARY_PLAINTEXT)
    } catch (e: Exception) {
        false
    }

    /** Decrypt the encrypted content store (`secure.store`) into a plaintext SQLite byte array. */
    fun decryptStore(masterKey: ByteArray, blob: ByteArray): ByteArray =
        decrypt(masterKey, STORE_BLOB_ID, blob, "normal")
}
