package ir.maxv.securevault.data

import android.content.Context
import android.util.Log
import ir.maxv.securevault.core.AppStage
import ir.maxv.securevault.core.KdfUnavailable
import ir.maxv.securevault.core.KeyDerivation
import ir.maxv.securevault.core.S3Config
import ir.maxv.securevault.core.StageLogic
import ir.maxv.securevault.core.SyncState
import ir.maxv.securevault.core.VaultMeta
import ir.maxv.securevault.core.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Which surface the app should show. */
/** Everything the UI wants to know about the local mirror and its vault. */
data class VaultInfo(
    val vaultId: String,
    val kdfAlgo: String,
    val argonMemoryMib: Int,
    val argonTimeCost: Int,
    val argonParallelism: Int,
    val notes: Int,
    val folders: Int,
    val secrets: Int,
    val downloadedNotes: Int,
    val localBytes: Long,
    val lastSyncAt: Long,
    val syncPrefix: String,
)

data class AppState(
    val stage: AppStage = AppStage.SETUP,
    val settings: LocalSettings = LocalSettings(),
    val hasCredentials: Boolean = false,
    val info: VaultInfo? = null,
    val progress: SyncProgress? = null,
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * The whole app in one place: setup, sync, unlock, browse, read, search.
 *
 * Every call that touches the network, the KDF or the mirror, runs off the main thread.
 */
class VaultRepository(private val context: Context) {

    /**
     * Deliberately sparse: one line per meaningful event (a failed request with the provider's own
     * error code, a finished sync, a refused unlock). No per-file chatter — `adb logcat -s SecureVault`
     * should stay readable.
     */
    private fun note(message: String) = Log.i(TAG, message)

    private fun warn(message: String) = Log.w(TAG, message)

    private val mirror = LocalMirror(context)
    private val workDir = File(context.cacheDir, "work")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var session: VaultSession? = null
    private var backgroundedAt: Long = 0L

    /** Minutes the vault may stay unlocked in the background before it locks itself. */
    var autoLockMinutes: Int = 10

    // ── setup ─────────────────────────────────────────────────────────────────────
    fun bootstrap() {
        val settings = mirror.loadSettings()
        val hasCreds = mirror.loadCredentials() != null
        val stage = StageLogic.stage(hasVaultMetadata = mirror.hasMetadata(), hasOpenSession = false)
        _state.value = _state.value.copy(
            stage = stage,
            settings = settings,
            hasCredentials = hasCreds,
            info = if (mirror.hasMetadata()) readInfoWithoutNotes() else null,
        )
    }

    /**
     * Let the mirror decide which screen the app belongs on.
     *
     * The stage was only ever computed at startup, so a *successful* first sync left the app sitting
     * on the setup form until it was restarted, even though the vault was already on the device.
     * Every path that changes what the mirror holds (sync, unlock, lock, wipe) comes through here.
     */
    private fun syncStage() {
        val stage = StageLogic.stage(
            hasVaultMetadata = mirror.hasMetadata(),
            hasOpenSession = session != null,
        )
        if (_state.value.stage != stage) {
            _state.value = _state.value.copy(stage = stage, info = readInfoWithoutNotes())
        }
    }

    fun saveSetup(settings: LocalSettings, accessKey: String?, secretKey: String?) {
        mirror.saveSettings(settings)
        if (!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
            mirror.saveCredentials(accessKey, secretKey)
        }
        _state.value = _state.value.copy(
            settings = settings,
            hasCredentials = mirror.loadCredentials() != null,
            error = null,
        )
    }

    fun updateSettings(settings: LocalSettings) {
        mirror.saveSettings(settings)
        _state.value = _state.value.copy(settings = settings)
    }

    private fun config(meta: VaultMeta? = null): S3Config? {
        val creds = mirror.loadCredentials() ?: return null
        val settings = mirror.loadSettings()
        val sync = meta?.sync
        val config = S3Config(
            enabled = true,
            bucket = sync?.bucket?.takeIf { it.isNotBlank() } ?: settings.bucket,
            prefix = if (sync != null) sync.prefix else settings.prefix,
            endpoint = sync?.endpoint?.takeIf { it.isNotBlank() } ?: settings.endpoint,
            region = sync?.region?.takeIf { it.isNotBlank() } ?: settings.region,
            accessKey = creds.first,
            secretKey = creds.second,
        )
        return if (config.configured) config else null
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val config = config()
            ?: return@withContext "اطلاعات اتصال کامل نیست یا کلیدها ذخیره نشده‌اند.".also { setError(it) }
        try {
            val count = SyncEngine(mirror, config, log = ::warn).testConnection()
            val message = "اتصال برقرار شد — $count شیء در مسیر مخزن."
            note(message)
            setError(null)
            message
        } catch (e: Exception) {
            val message = "اتصال ناموفق: ${e.message ?: e.javaClass.simpleName}"
            warn("testConnection: $message")
            setError(message)
            message
        }
    }

    // ── sync ──────────────────────────────────────────────────────────────────────
    suspend fun sync(prefetch: Boolean = true): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existingMeta = readLocalMeta()
            val config = config(existingMeta) ?: return@withContext "کلیدهای S3 ذخیره نشده است."
            val engine = SyncEngine(mirror, config, log = ::warn)
            val state = mirror.loadState()
            setBusy("همگام‌سازی فهرست…")
            try {
                val (meta, metaResult) = engine.syncMetadata(state) { p -> setProgress(p) }
                mirror.saveState(state)
                adoptCoordinates(meta)
                var result = metaResult
                if (prefetch) {
                    val settings = mirror.loadSettings()
                    val rows = localRows(meta)
                    val textRows = rows.filter {
                        !it.isDir && it.sensitivity == "normal" && it.blobId != null &&
                            VaultPaths.isTextLike(it.path) &&
                            (it.size <= settings.prefetchMaxBytes || settings.prefetchAttachments)
                    }
                    val blobResult = engine.downloadBlobs(
                        rows = textRows,
                        state = state,
                        onProgress = { p -> setProgress(p) },
                    )
                    mirror.saveState(state)
                    result = SyncResult(
                        downloaded = result.downloaded + blobResult.downloaded,
                        deleted = result.deleted + blobResult.deleted,
                        unchanged = result.unchanged + blobResult.unchanged,
                        bytes = result.bytes + blobResult.bytes,
                        failed = result.failed + blobResult.failed,
                    )
                }
                session?.let { active ->
                    // the metadata may have moved under an open session: reload it in place
                    if (active.meta.vaultId == meta.vaultId) active.reload()
                }
                refreshInfo()
                setBusy(null)
                setProgress(null)
                syncStage()
                note("sync done: downloaded=${result.downloaded} deleted=${result.deleted} bytes=${result.bytes} failed=${result.failed}")
                "همگام شد — ${result.downloaded} فایل دریافت، ${result.bytes / 1024} کیلوبایت"
            } catch (e: Exception) {
                setBusy(null)
                setProgress(null)
                warn("sync failed: ${e.javaClass.simpleName}: ${e.message}")
                setError("همگام‌سازی ناموفق: ${e.message ?: e.javaClass.simpleName}")
                "همگام‌سازی ناموفق: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Download every normal blob (notes and attachments). */
    suspend fun downloadEverything(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rows = localRows(readLocalMeta()) ?: return@withContext "ابتدا یک‌بار همگام‌سازی کن."
            val config = config(readLocalMeta()) ?: return@withContext "کلیدهای S3 ذخیره نشده است."
            val engine = SyncEngine(mirror, config, log = ::warn)
            val state = mirror.loadState()
            setBusy("دریافت همهٔ فایل‌ها…")
            return@withContext try {
                val result = engine.downloadBlobs(
                    rows = rows.filter { !it.isDir && it.blobId != null },
                    state = state,
                    onProgress = { p -> setProgress(p) },
                    phase = "all",
                )
                mirror.saveState(state)
                setBusy(null)
                setProgress(null)
                refreshInfo()
                "دریافت کامل شد — ${result.downloaded} فایل (${result.bytes / 1024 / 1024} مگابایت)"
            } catch (e: Exception) {
                setBusy(null)
                setProgress(null)
                "دریافت ناموفق: ${e.message}"
            }
        }
    }

    private fun adoptCoordinates(meta: VaultMeta) {
        val settings = mirror.loadSettings()
        val merged = settings.copy(
            bucket = settings.bucket.ifBlank { meta.sync.bucket },
            prefix = if (settings.bucket.isBlank()) meta.sync.prefix else settings.prefix,
            endpoint = settings.endpoint.ifBlank { meta.sync.endpoint },
            region = settings.region.ifBlank { meta.sync.region },
        )
        if (merged != settings) mirror.saveSettings(merged)
    }

    // ── unlock ────────────────────────────────────────────────────────────────────
    suspend fun unlock(password: String): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val metaFile = mirror.resolve(VaultPaths.META_FILE)
            if (!metaFile.isFile) {
                val message = "فایل متادیتا پیدا نشد؛ اول همگام‌سازی کن."
                warn("unlock: $message (${metaFile.absolutePath})")
                setError(message)
                return@withContext false
            }
            setBusy("باز کردن قفل… (Argon2id)")
            try {
                val meta = VaultMeta.parse(metaFile.readText(Charsets.UTF_8))
                val argon = if (meta.kdf.algo == "argon2id") AndroidArgon2() else null
                val key = KeyDerivation.deriveMasterKey(password, meta.kdf, argon)
                if (!ir.maxv.securevault.core.Blob.checkCanary(key, meta.canary)) {
                    key.fill(0)
                    setBusy(null)
                    // Only the fact is logged, never the password or any derived material.
                    warn("unlock: canary mismatch (kdf=${meta.kdf.algo})")
                    setError("گذرواژه نادرست است.")
                    return@withContext false
                }
                val active = openSession(meta, key)
                session?.close()
                session = active
                setBusy(null)
                setError(null)
                syncStage()
                refreshInfo()
                true
            } catch (e: KdfUnavailable) {
                setBusy(null)
                warn("unlock: ${e.message}")
                setError("این والت با الگوریتم پشتیبانی‌نشده ساخته شده است (${e.message}).")
                false
            } catch (e: Exception) {
                setBusy(null)
                warn("unlock failed: ${e.javaClass.simpleName}: ${e.message}")
                setError("باز کردن قفل ناموفق: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
    }

    fun lock() {
        session?.close()
        session = null
        File(workDir, "store.dec").delete()
        _state.value = _state.value.copy(error = null, notice = null)
        syncStage()
    }

    fun onBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
    }

    /** Lock after [autoLockMinutes] in the background (called when the app resumes). */
    fun onForegrounded() {
        if (backgroundedAt == 0L || session == null) return
        val minutes = (System.currentTimeMillis() - backgroundedAt) / 60_000.0
        if (minutes >= autoLockMinutes) lock()
        backgroundedAt = 0L
    }

    private fun openSession(meta: VaultMeta, key: ByteArray): VaultSession {
        val active = VaultSession(context, mirror, meta, key, workDir)
        active.open()
        return active
    }

    // ── reading ───────────────────────────────────────────────────────────────────
    private fun activeSession(): VaultSession? = session

    fun children(folder: String): List<VaultRow> = activeSession()?.index?.children(folder).orEmpty()

    fun folderNote(folder: String): String? = activeSession()?.index?.folderNote(folder)

    fun fileNote(row: VaultRow): String? = activeSession()?.index?.fileNote(row.id)

    fun tagsOf(row: VaultRow): List<String> = activeSession()?.index?.tagsOf(row.id).orEmpty()

    fun row(path: String): VaultRow? = activeSession()?.index?.row(path)

    fun readNote(row: VaultRow): NoteContent = activeSession()?.note(row)
        ?: throw IllegalStateException("vault_locked")

    fun imageFor(url: String): ByteArray? {
        val active = activeSession() ?: return null
        if (url.startsWith("data:")) return active.dataUriBytes(url)
        val row = active.attachmentRow(url) ?: return null
        return active.imageBytes(row)
    }

    fun attachmentDownloaded(url: String): Boolean {
        val active = activeSession() ?: return false
        val row = active.attachmentRow(url) ?: return false
        return active.isDownloaded(row)
    }

    fun isDownloaded(row: VaultRow): Boolean = activeSession()?.isDownloaded(row) ?: false

    fun attachmentRow(url: String): VaultRow? = activeSession()?.attachmentRow(url)

    suspend fun downloadRow(row: VaultRow): Boolean = withContext(Dispatchers.IO) {
        val config = config(session?.meta) ?: return@withContext false
        val engine = SyncEngine(mirror, config, log = ::warn)
        val ok = engine.downloadOne(row)
        if (ok) {
            val state = mirror.loadState()
            val blobId = row.blobId
            if (blobId != null) state.record(VaultPaths.blobPath(blobId), row.size, null)
            mirror.saveState(state)
            refreshInfo()
        }
        ok
    }

    suspend fun search(query: String, titlesOnly: Boolean = false): SearchOutcome? = withContext(Dispatchers.Default) {
        val active = activeSession() ?: return@withContext null
        active.search(query, titlesOnly) { done, total ->
            setProgress(SyncProgress("جست‌وجو", done, total))
        }.also {
            setProgress(null)
        }
    }

    fun notesMissingLocally(): Int = activeSession()?.missingTextCount() ?: 0

    // ── maintenance ───────────────────────────────────────────────────────────────
    fun wipeLocalCopy() {
        session?.close()
        session = null
        mirror.clear()
        mirror.clearCredentials()
        mirror.saveState(SyncState())
        File(workDir, "store.dec").delete()
        _state.value = AppState(stage = AppStage.SETUP)
    }

    /** True once the vault's own metadata is on the device (i.e. a sync has landed). */
    fun hasLocalVault(): Boolean = mirror.hasMetadata()

    fun refreshInfo() {
        _state.value = _state.value.copy(info = readInfo())
    }

    private fun readInfo(): VaultInfo? {
        val meta = readLocalMeta() ?: return null
        val indexSize = localRowCount()
        val notesCount = noteRowCount()
        val downloaded = downloadedTextCount()
        return VaultInfo(
            vaultId = meta.vaultId,
            kdfAlgo = meta.kdf.algo,
            argonMemoryMib = meta.kdf.memoryKib / 1024,
            argonTimeCost = meta.kdf.timeCost,
            argonParallelism = meta.kdf.parallelism,
            notes = notesCount.first,
            folders = notesCount.second,
            secrets = secretsCount(),
            downloadedNotes = downloaded,
            localBytes = mirror.sizeBytes(),
            lastSyncAt = mirror.loadState().lastSyncAt(),
            syncPrefix = meta.sync.prefix.ifBlank { mirror.loadSettings().prefix },
        )
    }

    /** The info we can show while locked: metadata is plaintext by design (docs/SECURITY.md §2). */
    private fun readInfoWithoutNotes(): VaultInfo? = readInfo()

    private fun readLocalMeta(): VaultMeta? = try {
        mirror.readText(VaultPaths.META_FILE)?.let { VaultMeta.parse(it) }
    } catch (e: Exception) {
        null
    }

    private fun localRowCount(): Int = mirror.countFiles()

    /** Counts read straight from the plaintext meta.sqlite, no key needed. */
    private fun noteRowCount(): Pair<Int, Int> {
        val source = mirror.resolve(VaultPaths.META_DB)
        if (!source.isFile) return 0 to 0
        return try {
            val copy = VaultIndex.workingCopy(source, workDir, "counts.sqlite")
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(copy.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val files = db.rawQuery("SELECT COUNT(*) FROM files WHERE is_dir=0", null).use { it.moveToFirst(); it.getInt(0) }
            val dirs = db.rawQuery("SELECT COUNT(*) FROM files WHERE is_dir=1", null).use { it.moveToFirst(); it.getInt(0) }
            db.close()
            copy.delete()
            files to dirs
        } catch (e: Exception) {
            0 to 0
        }
    }

    private fun secretsCount(): Int {
        val source = mirror.resolve(VaultPaths.META_DB)
        if (!source.isFile) return 0
        return try {
            val copy = VaultIndex.workingCopy(source, workDir, "counts2.sqlite")
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(copy.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val count = db.rawQuery("SELECT COUNT(*) FROM files WHERE is_dir=0 AND sensitivity<>'normal'", null).use { it.moveToFirst(); it.getInt(0) }
            db.close()
            copy.delete()
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun downloadedTextCount(): Int {
        val active = session ?: return 0
        return active.index.textFiles().count { active.isDownloaded(it) }
    }

    /** Rows out of the local metadata without opening a session (used by sync prefetch). */
    private fun localRows(meta: VaultMeta?): List<VaultRow> {
        val source = mirror.resolve(VaultPaths.META_DB)
        if (!source.isFile) return emptyList()
        return try {
            val copy = VaultIndex.workingCopy(source, workDir, "sync-rows.sqlite")
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(copy.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            val index = VaultIndex.load(db)
            db.close()
            copy.delete()
            index.rows
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setBusy(text: String?) {
        _state.value = _state.value.copy(busy = text)
    }

    private fun setProgress(progress: SyncProgress?) {
        _state.value = _state.value.copy(progress = progress)
    }

    private fun setError(text: String?) {
        _state.value = _state.value.copy(error = text)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    /** Export a small JSON breadcrumb of the local mirror (used by the self-check screen). */
    fun localInventory(): String {
        val rows = mirror.walk()
        val json = JSONArray()
        rows.sorted().take(500).forEach { json.put(it) }
        return JSONObject()
            .put("files", rows.size)
            .put("bytes", mirror.sizeBytes())
            .put("sample", json)
            .toString(2)
    }

    companion object {
        /** `adb logcat -s SecureVault` */
        const val TAG = "SecureVault"
    }
}
