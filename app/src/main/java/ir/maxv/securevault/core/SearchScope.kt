package ir.maxv.securevault.core

/**
 * What a literal search is allowed to look at, per file.
 *
 * Two separate things hide behind "search the notes":
 *
 * * the **title** (the file name out of `meta.sqlite`), which is plaintext metadata by design — the
 *   browser already shows the names of `secret`/`secretfile` files while the vault is locked, so
 *   searching them leaks nothing new;
 * * the **body**, which lives in an encrypted blob and, for `secret`/`secretfile`, is only readable
 *   after an explicit reveal (desktop `docs/SECURITY.md`).
 *
 * So every file's title is searchable, while a body may only be scanned when the file is `normal`
 * *and* text-like. A secret file that matches therefore shows up as a hit on its title — never with
 * a snippet of its content, which was never decrypted.
 *
 * Kept out of the Android layer so the rule itself is tested, not just described.
 */
object SearchScope {

    /** The file's name may be matched. */
    const val TITLES = 1

    /** The decrypted body may be scanned. */
    const val BODY = 2

    /**
     * Bodies above this size are not scanned.
     *
     * Not a policy about secrecy but about the phone: a search holds the whole decrypted note plus
     * its normalisation map in memory, and a multi-megabyte note on a 256 MB heap is how the device
     * ends up with an `OutOfMemoryError` mid-search. Such notes are counted and reported instead of
     * silently skipped.
     */
    const val MAX_BODY_BYTES = 4L * 1024 * 1024

    fun of(sensitivity: String, isTextLike: Boolean): Int {
        var scope = TITLES
        if (sensitivity == "normal" && isTextLike) scope = scope or BODY
        return scope
    }

    /** True when this file's *body* may be decrypted and scanned. */
    fun scansBody(sensitivity: String, isTextLike: Boolean, size: Long): Boolean =
        !titlesOnly(of(sensitivity, isTextLike)) && size in 0..MAX_BODY_BYTES

    /** True when a hit on this file can only be about its title. */
    fun titlesOnly(scope: Int): Boolean = (scope and BODY) == 0
}
