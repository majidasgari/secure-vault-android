package ir.maxv.securevault.core

import org.json.JSONObject
import java.util.Base64

/**
 * What the device-keystore biometric record holds: the vault's master key, tied to the vault it was
 * derived from.
 *
 * Deliberately free of Android imports, because the keystore itself cannot be exercised off-device
 * while *what we accept back from it* can — and that is where the dangerous mistakes live: a record
 * from another vault, a truncated key, a file someone mangled.
 *
 * The record is only ever written sealed, under an AES-256-GCM keystore key whose use requires a
 * successful biometric authentication, and it is only ever accepted after [matches] confirms that
 * its key really opens *this* vault's canary.
 */
data class BiometricRecord(
    val vaultId: String,
    val masterKey: ByteArray,
) {

    fun encode(): String = JSONObject()
        .put(FIELD_VERSION, FORMAT_VERSION)
        .put(FIELD_VAULT_ID, vaultId)
        .put(FIELD_KEY, Base64.getEncoder().encodeToString(masterKey))
        .toString()

    /**
     * The gate that lets a fingerprint open the vault: the record must belong to *this* vault and
     * its key must decrypt the canary. A record that fails this is discarded, never used.
     */
    fun matches(meta: VaultMeta): Boolean =
        vaultId == meta.vaultId && Blob.checkCanary(masterKey, meta.canary)

    override fun equals(other: Any?): Boolean =
        other is BiometricRecord && other.vaultId == vaultId && other.masterKey.contentEquals(masterKey)

    override fun hashCode(): Int = vaultId.hashCode() * 31 + masterKey.contentHashCode()

    /** Never print the key itself. */
    override fun toString(): String = "BiometricRecord(vault=$vaultId, key=…)"

    companion object {
        const val FORMAT_VERSION = 1

        private const val FIELD_VERSION = "v"
        private const val FIELD_VAULT_ID = "vault_id"
        private const val FIELD_KEY = "key_b64"

        /**
         * Parse a decrypted record.
         *
         * Throws [IllegalArgumentException] (or a JSON parse error) on anything we do not
         * understand — every caller treats that as "use the password instead".
         */
        fun parse(json: String): BiometricRecord {
            val root = JSONObject(json)
            val version = root.optInt(FIELD_VERSION, 0)
            require(version == FORMAT_VERSION) { "unsupported_record_version:$version" }
            val vaultId = root.optString(FIELD_VAULT_ID, "")
            require(vaultId.isNotEmpty()) { "record_without_vault_id" }
            val key = try {
                Base64.getDecoder().decode(root.getString(FIELD_KEY))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("record_key_not_base64")
            }
            require(key.size == Blob.KEY_SIZE) { "record_key_size:${key.size}" }
            return BiometricRecord(vaultId, key)
        }
    }
}
