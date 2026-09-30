package ir.maxv.securevault.core

import org.json.JSONObject
import java.util.Base64

/** KDF parameters persisted in `.vault-meta.json`. */
data class KdfParams(
    val algo: String,
    val salt: ByteArray,
    val timeCost: Int,
    val memoryKib: Int,
    val parallelism: Int,
    val iterations: Int,
) {
    override fun equals(other: Any?): Boolean = other is KdfParams && other.algo == algo &&
        other.salt.contentEquals(salt) && other.timeCost == timeCost &&
        other.memoryKib == memoryKib && other.parallelism == parallelism &&
        other.iterations == iterations

    override fun hashCode(): Int = algo.hashCode() * 31 + salt.contentHashCode()
}

/** Argon2id, supplied by the platform binding (native library on Android). */
interface Argon2 {
    /** hash_len is always 32 here; the vault uses `type = ID` and version 0x13 (19). */
    fun hashId(password: ByteArray, salt: ByteArray, timeCost: Int, memoryKib: Int, parallelism: Int, hashLen: Int): ByteArray
}

/** Thrown when the environment cannot run the KDF the vault was created with. */
class KdfUnavailable(message: String) : Exception(message)

object KeyDerivation {

    /** Derive the 32-byte master key exactly like `crypto.derive_master_key`. */
    fun deriveMasterKey(password: String, params: KdfParams, argon2: Argon2?): ByteArray {
        val pw = password.toByteArray(Charsets.UTF_8)
        return when (params.algo) {
            "argon2id" -> {
                val impl = argon2 ?: throw KdfUnavailable("argon2id_unavailable")
                impl.hashId(pw, params.salt, params.timeCost, params.memoryKib, params.parallelism, Blob.KEY_SIZE)
            }
            "pbkdf2-sha512" -> Crypto.pbkdf2Sha512(pw, params.salt, params.iterations, Blob.KEY_SIZE)
            else -> throw KdfUnavailable("unsupported_kdf:" + params.algo)
        }
    }
}

/** Sync coordinates stored in the vault settings (not secrets). */
data class SyncSettings(
    val enabled: Boolean,
    val bucket: String,
    val prefix: String,
    val endpoint: String,
    val region: String,
)

/** The readable part of `.vault-meta.json`. */
data class VaultMeta(
    val schemaVersion: Int,
    val vaultId: String,
    val createdAt: Long,
    val kdf: KdfParams,
    val canary: ByteArray,
    val plainThresholdBytes: Long,
    val sync: SyncSettings,
    val language: String,
) {
    companion object {
        fun parse(json: String): VaultMeta {
            val root = JSONObject(json)
            val kdfJson = root.getJSONObject("kdf")
            val settings = root.optJSONObject("settings") ?: JSONObject()
            val syncJson = settings.optJSONObject("sync") ?: JSONObject()
            val kdf = KdfParams(
                algo = kdfJson.optString("algo", "argon2id"),
                salt = Base64.getDecoder().decode(kdfJson.getString("salt_b64")),
                timeCost = kdfJson.optInt("time_cost", 0),
                memoryKib = kdfJson.optInt("memory_kib", 0),
                parallelism = kdfJson.optInt("parallelism", 0),
                iterations = kdfJson.optInt("iterations", 600000),
            )
            return VaultMeta(
                schemaVersion = root.optInt("schema_version", 1),
                vaultId = root.optString("vault_id", ""),
                createdAt = root.optLong("created_at", 0L),
                kdf = kdf,
                canary = Base64.getDecoder().decode(root.getString("canary_b64")),
                plainThresholdBytes = settings.optLong("plain_threshold_bytes", 10L * 1024 * 1024),
                sync = SyncSettings(
                    enabled = syncJson.optBoolean("enabled", false),
                    bucket = syncJson.optString("bucket", ""),
                    prefix = syncJson.optString("prefix", ""),
                    endpoint = syncJson.optString("endpoint", ""),
                    region = syncJson.optString("region", ""),
                ),
                language = settings.optString("language", "fa"),
            )
        }
    }
}
