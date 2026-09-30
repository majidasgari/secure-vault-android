package ir.maxv.securevault.data

import ir.maxv.securevault.core.S3Client
import ir.maxv.securevault.core.S3Config
import ir.maxv.securevault.core.S3Exception
import ir.maxv.securevault.core.S3Object
import ir.maxv.securevault.core.SyncPlanner
import ir.maxv.securevault.core.SyncState
import ir.maxv.securevault.core.VaultMeta
import ir.maxv.securevault.core.VaultPaths

/** Live progress of a sync run. */
data class SyncProgress(
    val phase: String,
    val done: Int,
    val total: Int,
    val currentPath: String = "",
    val bytes: Long = 0L,
) {
    val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
}

data class SyncResult(
    val downloaded: Int,
    val deleted: Int,
    val unchanged: Int,
    val bytes: Long,
    val failed: List<String>,
)

class VaultNotInBucket(message: String) : Exception(message)

/**
 * Pull-only sync engine.
 *
 * It never uploads, never deletes anything in the bucket and never touches the write lock: the
 * phone is a reader, and the desktop client stays the single writer (docs/SYNC.md).
 */
class SyncEngine(
    private val mirror: LocalMirror,
    private var config: S3Config,
    private val log: ((String) -> Unit)? = null,
) {

    private fun client(): S3Client = S3Client(config, log = log)

    val prefix: String get() = config.normalizedPrefix()

    private fun relativeOf(key: String): String? {
        val prefix = this.prefix
        if (!key.startsWith(prefix)) return null
        val relative = key.substring(prefix.length)
        if (relative.isEmpty() || !SyncPlanner.eligible(relative)) return null
        return relative
    }

    /**
     * The bucket key of a vault-relative path.
     *
     * The mirror keeps a vault's *relative* paths while the bucket holds them under `prefix`, so
     * every object read has to go through here. Passing the relative path straight to the client
     * asks for an object one level too high — a 404 that a download reports as "nothing to fetch".
     */
    private fun keyOf(relative: String): String = VaultPaths.objectKey(prefix, relative)

    /** List the bucket once and return `relative path → object`. */
    fun listRemote(): Map<String, S3Object> {
        val out = LinkedHashMap<String, S3Object>()
        for (obj in client().list(prefix)) {
            val relative = relativeOf(obj.key) ?: continue
            out[relative] = obj
        }
        return out
    }

    /** Test the coordinates + credentials without transferring anything. */
    fun testConnection(): Int = client().list(prefix).size

    /**
     * Refresh the three metadata files (`.vault-meta.json`, `meta.sqlite`, `secure.store`).
     *
     * @return the parsed vault metadata when the bucket actually holds a vault.
     */
    fun syncMetadata(
        state: SyncState,
        onProgress: (SyncProgress) -> Unit,
    ): Pair<VaultMeta, SyncResult> {
        val remote = listRemote()
        val metadata = remote.filterKeys { it in VaultPaths.METADATA_FILES }
        val metaObject = metadata[VaultPaths.META_FILE]
            ?: throw VaultNotInBucket("در مسیر «${prefix.trimEnd('/')}» والت پیدا نشد.")
        if (!metadata.containsKey(VaultPaths.META_DB) || !metadata.containsKey(VaultPaths.STORE_FILE)) {
            throw VaultNotInBucket("آینهٔ باکت ناقص است (meta.sqlite یا secure.store نیست).")
        }

        val local = state.all()
        val present = mirror.walk()
        val plan = SyncPlanner.plan(metadata, local, present)

        var bytes = 0L
        var downloaded = 0
        val failed = ArrayList<String>()
        val total = plan.downloads.size
        for ((position, relative) in plan.downloads.withIndex()) {
            onProgress(SyncProgress("metadata", position, total, relative, bytes))
            val written = try {
                client().download(keyOf(relative), mirror.resolve(relative))
            } catch (e: S3Exception) {
                failed.add(relative)
                null
            } catch (e: Exception) {
                failed.add(relative)
                null
            }
            val obj = metadata[relative]
            when {
                written == null -> failed.add(relative)
                else -> {
                    bytes += written
                    downloaded++
                    state.record(relative, written, obj?.etag)
                }
            }
        }

        var deleted = 0
        for (relative in plan.deletes) {
            mirror.delete(relative)
            state.forget(relative)
            deleted++
        }

        val metaText = mirror.readText(VaultPaths.META_FILE)
            ?: throw VaultNotInBucket(
                "فایل فرادادهٔ والت دریافت نشد (کلید «${keyOf(VaultPaths.META_FILE)}»" +
                    if (failed.isEmpty()) ")." else "، ناموفق‌ها: ${failed.joinToString("، ")})."
            )
        val meta = VaultMeta.parse(metaText)
        onProgress(SyncProgress("metadata", total, total, "", bytes))
        return meta to SyncResult(downloaded, deleted, plan.unchanged, bytes, failed)
    }

    /** Download every blob a predicate accepts (used for on-demand reads and prefetch runs). */
    fun downloadBlobs(
        rows: List<VaultRow>,
        state: SyncState,
        onProgress: (SyncProgress) -> Unit,
        phase: String = "blobs",
    ): SyncResult {
        val pending = rows.filter { row ->
            val blobId = row.blobId ?: return@filter false
            val relative = VaultPaths.blobPath(blobId)
            val localSize = mirror.length(relative)
            localSize < 0 || (row.size > 0 && localSize != row.size)
        }
        var bytes = 0L
        var downloaded = 0
        val failed = ArrayList<String>()
        for ((position, row) in pending.withIndex()) {
            val blobId = row.blobId ?: continue
            val relative = VaultPaths.blobPath(blobId)
            onProgress(SyncProgress(phase, position, pending.size, row.path, bytes))
            try {
                val written = client().download(keyOf(relative), mirror.resolve(relative))
                if (written != null) {
                    bytes += written
                    downloaded++
                    state.record(relative, written, null)
                }
            } catch (e: Exception) {
                failed.add(row.path)
            }
        }
        onProgress(SyncProgress(phase, pending.size, pending.size, "", bytes))
        return SyncResult(downloaded, 0, rows.size - pending.size, bytes, failed)
    }

    fun downloadOne(row: VaultRow): Boolean {
        val blobId = row.blobId ?: return false
        val relative = VaultPaths.blobPath(blobId)
        return try {
            client().download(keyOf(relative), mirror.resolve(relative)) != null
        } catch (e: Exception) {
            false
        }
    }

    /** Upload nothing; exposed only so the read-only promise is visible in code. */
    val writeCapable: Boolean get() = false
}
