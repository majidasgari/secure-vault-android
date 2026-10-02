package ir.maxv.securevault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import ir.maxv.securevault.core.Blob
import ir.maxv.securevault.core.LiteralHit
import ir.maxv.securevault.core.LiteralSearch
import ir.maxv.securevault.core.SearchScope
import ir.maxv.securevault.core.TamperDetected
import ir.maxv.securevault.core.VaultMeta
import ir.maxv.securevault.core.VaultPaths
import java.io.File

/** One note as the UI needs it. */
data class NoteContent(
    val row: VaultRow,
    val text: String,
    val note: String?,
    val tags: List<String>,
)

/** One search hit group (a note with its matches). */
data class SearchResult(
    val row: VaultRow,
    val title: String,
    val hits: List<LiteralHit>,
    val snippet: String,
    val snippetMatchStart: Int,
    /** True when only the file's title matched: its body was never searched (secret, or not text). */
    val titleOnly: Boolean = false,
)

/** Outcome of a literal search run. */
data class SearchOutcome(
    val query: String,
    val results: List<SearchResult>,
    val scanned: Int,
    val notDownloaded: Int,
    val elapsedMs: Long,
    /** How many of [results] came from a title match alone. */
    val titleOnly: Int = 0,
    /** Whether the run itself was the "titles only" mode. */
    val titlesOnly: Boolean = false,
    /** Notes too big to scan on a phone heap (they are named so the count is never a silent gap). */
    val skipped: Int = 0,
)

/**
 * An unlocked vault: the master key, the plaintext metadata index, the decrypted content store
 * and the decryption cache. Everything here dies on [close].
 */
class VaultSession(
    private val context: Context,
    private val mirror: LocalMirror,
    val meta: VaultMeta,
    private var masterKey: ByteArray,
    private val workDir: File,
) {

    private var metaDb: SQLiteDatabase? = null
    private var storeDb: SQLiteDatabase? = null
    private var decryptedStore: File? = null

    lateinit var index: VaultIndex
        private set

    /** Decrypted bodies, keyed by file id (bounded; see [MAX_CACHE_BYTES]). */
    private val bodyCache = LinkedHashMap<Long, String>()
    private var cacheBytes = 0L

    /** Decrypted images, keyed by file id. */
    private val imageCache = LinkedHashMap<Long, ByteArray>()
    private var imageCacheBytes = 0L

    val vaultId: String get() = meta.vaultId

    /**
     * A copy of the master key, for sealing into the device's biometric record. Never logged, never
     * written anywhere by the caller, and zeroed by the caller as soon as it is sealed.
     */
    fun keyCopy(): ByteArray? = if (masterKey.isEmpty()) null else masterKey.copyOf()

    /** Decrypt the content store and load the metadata index. */
    fun open(): VaultIndex {
        val metaSource = mirror.resolve(VaultPaths.META_DB)
        val storeSource = mirror.resolve(VaultPaths.STORE_FILE)
        require(metaSource.isFile) { "meta.sqlite is missing" }
        require(storeSource.isFile) { "secure.store is missing" }

        val metaCopy = VaultIndex.workingCopy(metaSource, workDir, VaultPaths.META_DB)
        metaDb = SQLiteDatabase.openDatabase(metaCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        val storePlain = Blob.decryptStore(masterKey, storeSource.readBytes())
        val storeCopy = File(workDir, "store.dec")
        storeCopy.writeBytes(storePlain)
        decryptedStore = storeCopy
        storeDb = SQLiteDatabase.openDatabase(storeCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        val base = VaultIndex.load(metaDb!!)
        val (folderNotes, fileNotes) = VaultIndex.loadNotes(storeDb!!)
        index = VaultIndex(base.rows, base.tags, folderNotes, fileNotes)
        return index
    }

    /** Decrypt one file's content straight from the mirror. */
    fun readBytes(row: VaultRow): ByteArray {
        val blobId = row.blobId ?: throw IllegalStateException("not_a_blob")
        val relative = VaultPaths.blobPath(blobId)
        val blob = mirror.readBytes(relative) ?: throw NoSuchElementException("blob_not_downloaded")
        return Blob.decrypt(masterKey, blobId, blob, row.sensitivity)
    }

    fun isDownloaded(row: VaultRow): Boolean {
        val blobId = row.blobId ?: return false
        return mirror.exists(VaultPaths.blobPath(blobId))
    }

    /** Decrypted text of a note, cached while the session lives. */
    fun readText(row: VaultRow, cache: Boolean = true): String {
        bodyCache[row.id]?.let {
            // refresh LRU order
            bodyCache.remove(row.id)
            bodyCache[row.id] = it
            return it
        }
        val bytes = readBytes(row)
        val text = String(bytes, Charsets.UTF_8)
        if (cache && bytes.size <= CACHE_PER_FILE_LIMIT) {
            bodyCache[row.id] = text
            cacheBytes += heapCost(text)
            trimCache()
        }
        return text
    }

    /**
     * What a cached string really costs the heap.
     *
     * The old accounting added the UTF-8 byte count, but the retained object is a `String`: Latin
     * text is 1 byte per char, everything else (`Persian`, Cyrillic, emoji) is 2. Counting 2 bytes
     * per char is the honest upper bound and keeps the LRU from quietly holding twice what it
     * thinks it does.
     */
    private fun heapCost(text: String): Long = 2L * text.length

    fun note(row: VaultRow): NoteContent = NoteContent(
        row = row,
        text = readText(row),
        note = index.fileNote(row.id),
        tags = index.tagsOf(row.id),
    )

    private fun trimCache() {
        while (cacheBytes > MAX_CACHE_BYTES && bodyCache.isNotEmpty()) {
            val oldest = bodyCache.keys.first()
            val value = bodyCache.remove(oldest) ?: continue
            cacheBytes -= heapCost(value)
        }
    }

    // ── images ────────────────────────────────────────────────────────────────────
    /** Resolve an image reference from a note body to a vault file row. */
    fun attachmentRow(url: String): VaultRow? {
        val clean = url.trim().removePrefix("<").removeSuffix(">").trim()
        if (clean.isEmpty() || clean.startsWith("data:") || clean.startsWith("http")) return null
        val path = clean
            .removePrefix("vault:/")
            .removePrefix("vault:")
            .substringBefore('?')
            .substringBefore('#')
        val normalized = VaultPaths.normalize(path)
        index.row(normalized)?.let { return it }
        val name = VaultPaths.nameOf(normalized)
        if (name.isEmpty()) return null
        // Joplin imports reference attachments relatively (`../../../../assets/<name>`), while
        // the importer stores every resource under `attachments/`; fall back to the basename.
        return index.rows.firstOrNull { !it.isDir && it.name == name && VaultPaths.isImage(it.path) }
            ?: index.rows.firstOrNull { !it.isDir && it.name == name }
    }

    /** Decrypted image bytes for inline `data:` URIs. */
    fun dataUriBytes(url: String): ByteArray? = try {
        val payload = url.substringAfter(",", "")
        Base64.decode(payload, Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }

    /** Decrypted bytes of an image blob (in-memory cache, never written to disk). */
    fun imageBytes(row: VaultRow): ByteArray? {
        imageCache[row.id]?.let {
            imageCache.remove(row.id)
            imageCache[row.id] = it
            return it
        }
        val bytes = try {
            readBytes(row)
        } catch (e: Exception) {
            return null
        }
        if (bytes.size <= IMAGE_CACHE_PER_FILE) {
            imageCache[row.id] = bytes
            imageCacheBytes += bytes.size
            while (imageCacheBytes > IMAGE_CACHE_BYTES && imageCache.isNotEmpty()) {
                val oldest = imageCache.keys.first()
                val value = imageCache.remove(oldest) ?: continue
                imageCacheBytes -= value.size
            }
        }
        return bytes
    }

    // ── search ────────────────────────────────────────────────────────────────────
    /**
     * Literal search over the vault.
     *
     * *Every* file's title is a candidate (titles are plaintext metadata), while bodies are only
     * scanned for `normal`, text-like files — `secret`/`secretfile` content is never decrypted here,
     * it needs the explicit reveal in the reader. A secret file that matches its title is reported
     * with [SearchResult.titleOnly] set, without any snippet.
     *
     * Bodies are read *without* filling the session cache: a search walks the whole vault, and
     * caching every note is how the phone ran out of heap. Only the snippet of each hit survives.
     */
    fun search(
        query: String,
        titlesOnly: Boolean = false,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): SearchOutcome {
        val started = System.currentTimeMillis()
        val candidates = index.rows.filter { !it.isDir }
        val results = ArrayList<SearchResult>()
        var scanned = 0
        var missing = 0
        var skipped = 0
        var titleMatches = 0

        for ((position, row) in candidates.withIndex()) {
            onProgress?.invoke(position + 1, candidates.size)

            val textLike = VaultPaths.isTextLike(row.path)
            val scope = SearchScope.of(row.sensitivity, textLike)
            val titleHit = LiteralSearch.firstHit(row.title, query)

            // Titles first: a hit here is worth reporting even when the body cannot be read.
            fun titleResult(): SearchResult {
                titleMatches++
                return SearchResult(
                    row = row,
                    title = row.title,
                    hits = listOf(titleHit!!),
                    snippet = row.path,
                    snippetMatchStart = 0,
                    titleOnly = !titlesOnly,
                )
            }

            val size = if (row.size > 0) row.size else 0L
            val bodyAllowed = !titlesOnly && row.blobId != null &&
                SearchScope.scansBody(row.sensitivity, textLike, size)
            if (!bodyAllowed) {
                titleHit?.let { results.add(titleResult()) }
                if (!titlesOnly && row.blobId != null && !SearchScope.titlesOnly(scope) && size > SearchScope.MAX_BODY_BYTES) {
                    // normal text, just too big to scan on a phone heap: reported, never hidden
                    skipped++
                }
                continue
            }
            if (!isDownloaded(row)) {
                // only count bodies we would have scanned: secret/attachment rows are never here
                missing++
                titleHit?.let { results.add(titleResult()) }
                continue
            }
            scanned++
            try {
                val text = readText(row, cache = false)
                val hits = LiteralSearch.findAll(text, query, maxHits = MAX_HITS_PER_NOTE)
                if (hits.isEmpty()) {
                    titleHit?.let { results.add(titleResult()) }
                    continue
                }
                val first = hits.first()
                results.add(
                    SearchResult(
                        row = row,
                        title = row.title,
                        hits = hits,
                        snippet = LiteralSearch.snippet(text, first),
                        snippetMatchStart = LiteralSearch.matchStartInSnippet(text, first),
                        titleOnly = false,
                    )
                )
            } catch (e: TamperDetected) {
                // a blob that fails authentication is skipped, never silently trusted
            } catch (e: Exception) {
                // unreadable file: skip it, keep searching
            }
        }

        results.sortWith(
            compareByDescending<SearchResult> { it.hits.size }
                .thenBy { it.row.title.length }
        )
        return SearchOutcome(
            query = query,
            results = results,
            scanned = scanned,
            notDownloaded = missing,
            elapsedMs = System.currentTimeMillis() - started,
            titleOnly = titleMatches,
            titlesOnly = titlesOnly,
            skipped = skipped,
        )
    }

    /** Note text lines split for the reader (kept here so the UI stays dumb). */
    fun lineCount(row: VaultRow): Int = try {
        readText(row).count { it == '\n' } + 1
    } catch (e: Exception) {
        0
    }

    /** How many readable notes are still missing locally. */
    fun missingTextCount(): Int = index.textFiles().count { !isDownloaded(it) }

    /** Re-read the metadata/content store after a sync while staying unlocked. */
    fun reload() {
        if (masterKey.isEmpty()) return
        storeDb?.close()
        metaDb?.close()
        storeDb = null
        metaDb = null
        decryptedStore?.delete()
        bodyCache.clear()
        cacheBytes = 0
        open()
    }

    fun close() {
        try {
            storeDb?.close()
        } catch (e: Exception) {
        }
        try {
            metaDb?.close()
        } catch (e: Exception) {
        }
        storeDb = null
        metaDb = null
        decryptedStore?.delete()
        File(workDir, VaultPaths.META_DB).delete()
        File(workDir, "${VaultPaths.META_DB}-wal").delete()
        File(workDir, "${VaultPaths.META_DB}-shm").delete()
        decryptedStore = null
        bodyCache.clear()
        imageCache.clear()
        cacheBytes = 0
        imageCacheBytes = 0
        for (i in masterKey.indices) masterKey[i] = 0
        masterKey = ByteArray(0)
    }

    companion object {
        /**
         * Cache bounds, sized for a phone heap (256 MB on the test device), not for a desktop.
         *
         * The cache used to allow 96 MB *of UTF-8*, i.e. up to ~192 MB of Java chars, which is how a
         * content search ended in an `OutOfMemoryError` while a 6 MB substring was being built.
         */
        private const val CACHE_PER_FILE_LIMIT = 256 * 1024
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
        private const val IMAGE_CACHE_PER_FILE = 4 * 1024 * 1024
        private const val IMAGE_CACHE_BYTES = 24L * 1024 * 1024
        private const val MAX_HITS_PER_NOTE = 50
    }
}
