package ir.maxv.securevault.core

/**
 * Vault-relative path helpers, mirroring `vaultfs.blob_path` and `sync._is_excluded`.
 *
 * Every path handled here is relative to the vault home with `/` separators
 * (e.g. `files/0a/0a1b….enc`, `شخصی/نویسندگی/دیوار.md`).
 */
object VaultPaths {

    const val META_FILE = ".vault-meta.json"
    const val META_DB = "meta.sqlite"
    const val STORE_FILE = "secure.store"
    const val LOCK_FILE = ".secure-vault.lock"
    const val FILES_DIR = "files"

    val METADATA_FILES = listOf(META_FILE, META_DB, STORE_FILE)

    /** `files/<first two hex chars of the blob id>/<blob id>.enc` */
    fun blobPath(blobId: String): String =
        "$FILES_DIR/${blobId.take(2)}/$blobId.enc"

    /** True when the blob id looks like the desktop client's (32 hex chars). */
    fun isBlobId(blobId: String?): Boolean =
        blobId != null && blobId.length >= 2 && blobId.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /** Derived/runtime files that are never synced (`sync._is_excluded`). */
    fun isExcluded(relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        if (name == ".DS_Store" || name == LOCK_FILE) return true
        if (name.endsWith(".tmp") || name.endsWith(".dec")) return true
        if (name.endsWith("-wal") || name.endsWith("-shm")) return true
        if (name.endsWith(".db")) return true
        if (name.startsWith("store.")) return true
        val parts = relative.split('/')
        if (parts.contains("semantic") || parts.contains("cache")) return true
        return false
    }

    /** Last path segment. */
    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Parent folder (`""` for a top-level entry). */
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "" else trimmed.substring(0, idx)
    }

    /** Join a folder and a child name into a vault path. */
    fun join(folder: String, name: String): String =
        if (folder.isEmpty()) name else folder.trimEnd('/') + "/" + name

    /** Depth of a path (a top-level entry is depth 1). */
    fun depth(path: String): Int = path.split('/').count { it.isNotEmpty() }

    /** Display title of a note: the file name without its extension. */
    fun titleOf(path: String): String {
        val name = nameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extensionOf(path: String): String {
        val name = nameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg")
    val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "json", "yaml", "yml", "csv", "log", "ini", "toml", "xml", "html", "sql", "sh", "py", "kt", "js", "ts", "css")

    fun isImage(path: String): Boolean = extensionOf(path) in IMAGE_EXTENSIONS

    /** Heuristic: is this plausibly displayable text (used to pick prefetch candidates)? */
    fun isTextLike(path: String): Boolean {
        val ext = extensionOf(path)
        if (ext.isEmpty()) return true
        return ext in TEXT_EXTENSIONS || ext == "md"
    }

    /** Normalize a user/library path: strip a leading `/` and collapse duplicate slashes. */
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}
