package ir.maxv.securevault.data

import android.content.Context
import ir.maxv.securevault.core.SyncState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The device's local mirror of the vault home.
 *
 * The layout is deliberately identical to the vault home (`meta.sqlite`, `secure.store`,
 * `files/<aa>/<blob>.enc`), so every path from the bucket maps 1:1 onto a file here and the
 * sync code needs no translation table.
 */
class LocalMirror(private val context: Context) {

    val root: File = File(context.filesDir, "vault")

    private val stateFile: File = File(context.filesDir, "sync-state.json")

    fun resolve(relative: String): File = File(root, relative)

    fun exists(relative: String): Boolean = resolve(relative).isFile

    fun length(relative: String): Long = resolve(relative).takeIf { it.isFile }?.length() ?: -1L

    fun readBytes(relative: String): ByteArray? =
        resolve(relative).takeIf { it.isFile }?.readBytes()

    fun readText(relative: String): String? =
        resolve(relative).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun writeBytes(relative: String, data: ByteArray) {
        val target = resolve(relative)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(relative: String) {
        val file = resolve(relative)
        if (file.isFile) file.delete()
        // prune the now-empty shard directory
        val parent = file.parentFile
        if (parent != null && parent != root && parent.list()?.isEmpty() == true) parent.delete()
    }

    /** Every file in the mirror, as vault-relative paths. */
    fun walk(): Set<String> {
        if (!root.isDirectory) return emptySet()
        val out = LinkedHashSet<String>()
        val base = root.absolutePath.length + 1
        root.walkTopDown().filter { it.isFile }.forEach { out.add(it.absolutePath.substring(base)) }
        return out
    }

    fun sizeBytes(): Long =
        if (!root.isDirectory) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun countFiles(): Int =
        if (!root.isDirectory) 0 else root.walkTopDown().count { it.isFile }

    fun hasMetadata(): Boolean = exists(ir.maxv.securevault.core.VaultPaths.META_FILE)

    fun clear() {
        root.deleteRecursively()
        stateFile.delete()
    }

    // ── sync state ────────────────────────────────────────────────────────────────
    fun loadState(): SyncState = SyncState.fromJson(stateFile.takeIf { it.isFile }?.readText())

    fun saveState(state: SyncState) {
        stateFile.writeText(state.toJson(), Charsets.UTF_8)
    }

    // ── non-secret local settings ─────────────────────────────────────────────────
    fun loadSettings(): LocalSettings {
        val file = File(context.filesDir, "settings.json")
        val json = file.takeIf { it.isFile }?.readText() ?: return LocalSettings()
        return try {
            LocalSettings.fromJson(JSONObject(json))
        } catch (e: Exception) {
            LocalSettings()
        }
    }

    fun saveSettings(settings: LocalSettings) {
        File(context.filesDir, "settings.json").writeText(settings.toJson().toString(), Charsets.UTF_8)
    }

    // ── credentials (keystore-sealed) ─────────────────────────────────────────────
    fun saveCredentials(accessKey: String, secretKey: String) {
        val json = JSONObject().put("access_key", accessKey).put("secret_key", secretKey)
        File(context.filesDir, "creds.bin").writeBytes(KeystoreBox.seal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    fun loadCredentials(): Pair<String, String>? {
        val file = File(context.filesDir, "creds.bin")
        if (!file.isFile) return null
        return try {
            val json = JSONObject(String(KeystoreBox.open(file.readBytes()), Charsets.UTF_8))
            val access = json.optString("access_key", "")
            val secret = json.optString("secret_key", "")
            if (access.isBlank() || secret.isBlank()) null else access to secret
        } catch (e: Exception) {
            null
        }
    }

    fun clearCredentials() {
        File(context.filesDir, "creds.bin").delete()
    }
}

/** Non-secret device settings. */
data class LocalSettings(
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    /** Text notes bigger than this are not prefetched (they stay available on demand). */
    val prefetchMaxBytes: Long = 2L * 1024 * 1024,
    val prefetchAttachments: Boolean = true,
    val prefetchAfterSync: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("endpoint", endpoint)
        .put("region", region)
        .put("bucket", bucket)
        .put("prefix", prefix)
        .put("prefetch_max_bytes", prefetchMaxBytes)
        .put("prefetch_attachments", prefetchAttachments)
        .put("prefetch_after_sync", prefetchAfterSync)

    companion object {
        fun fromJson(json: JSONObject): LocalSettings = LocalSettings(
            endpoint = json.optString("endpoint", ""),
            region = json.optString("region", ""),
            bucket = json.optString("bucket", ""),
            prefix = json.optString("prefix", ""),
            prefetchMaxBytes = json.optLong("prefetch_max_bytes", 2L * 1024 * 1024),
            prefetchAttachments = json.optBoolean("prefetch_attachments", true),
            prefetchAfterSync = json.optBoolean("prefetch_after_sync", false),
        )
    }
}
