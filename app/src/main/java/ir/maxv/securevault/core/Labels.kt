package ir.maxv.securevault.core

/**
 * Display labels built from vault metadata.
 *
 * The vault keeps a short emoji label per folder/file (the `emoji` column of `meta.sqlite`) —
 * plaintext metadata, like the name itself, so it is readable while the vault is locked. On this
 * device it is shown the same way the desktop app shows it: in front of the name, separated by a
 * space. The label never replaces the name and is never invented here: an empty or missing label
 * leaves the name exactly as it was.
 */
object Labels {

    /** Return the label to show for `name`, prefixed with `emoji` when the vault has one. */
    fun withEmoji(name: String, emoji: String?): String {
        val glyph = clean(emoji)
        return if (glyph.isEmpty()) name else "$glyph $name"
    }

    /** Return `emoji` without surrounding whitespace, or `""` when there is nothing to show. */
    fun clean(emoji: String?): String = emoji?.trim().orEmpty()
}
