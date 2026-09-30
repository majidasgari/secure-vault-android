package ir.maxv.securevault

import ir.maxv.securevault.core.Blob
import ir.maxv.securevault.core.Hex
import ir.maxv.securevault.core.KeyDerivation
import ir.maxv.securevault.core.KdfParams
import ir.maxv.securevault.core.TamperDetected
import ir.maxv.securevault.core.VaultMeta
import ir.maxv.securevault.core.VaultPaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * End-to-end reader test against a vault produced by the desktop client.
 *
 * Covers the whole decryption chain — KDF → canary → HKDF per blob → AES-256-GCM with the
 * blob-id/sensitivity AAD → the encrypted content store — for a `pbkdf2-sha512` vault, which a
 * JVM test can derive on its own. The `argon2id` path cannot be exercised here (it needs the
 * device's native library); it is guarded at runtime by the canary check, which fails loudly if
 * the derivation is not byte-identical.
 */
class CryptoFixtureTest {

    private fun masterKey(password: String = Fixture.password): ByteArray {
        val meta = VaultMeta.parse(Fixture.metaJson())
        return KeyDerivation.deriveMasterKey(password, meta.kdf, argon2 = null)
    }

    @Test
    fun `metadata parses like the desktop format`() {
        val meta = VaultMeta.parse(Fixture.metaJson())
        assertEquals("pbkdf2-sha512", meta.kdf.algo)
        assertEquals(600_000, meta.kdf.iterations)
        assertEquals(16, meta.kdf.salt.size)
        assertTrue(meta.vaultId.isNotEmpty())
        assertEquals(10L * 1024 * 1024, meta.plainThresholdBytes)
        // the canary is a full blob: 36-byte header + ciphertext + GCM tag
        assertTrue(meta.canary.size > Blob.HEADER_SIZE)
        assertTrue(String(meta.canary.copyOfRange(0, 4), Charsets.US_ASCII) == "SVLT")
        assertEquals("sync", meta.sync.prefix)
        assertEquals("test-bucket", meta.sync.bucket)
    }

    @Test
    fun `the canary accepts the right password and rejects a wrong one`() {
        val meta = VaultMeta.parse(Fixture.metaJson())
        assertTrue(Blob.checkCanary(masterKey(), meta.canary))
        assertFalse(Blob.checkCanary(masterKey("wrong password"), meta.canary))
    }

    @Test
    fun `every note decrypts to exactly the text the desktop wrote`() {
        val key = masterKey()
        for (file in Fixture.files()) {
            val blobId = file.getString("blob_id")
            val sensitivity = file.getString("sensitivity")
            val blob = Fixture.vaultFile(VaultPaths.blobPath(blobId)).readBytes()
            val plain = Blob.decrypt(key, blobId, blob, sensitivity)
            assertEquals(
                "sha256 mismatch for " + file.getString("path"),
                file.getString("sha256"),
                MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) },
            )
            assertArrayEquals(plain, file.getString("text").toByteArray(Charsets.UTF_8))
            assertEquals("plain flag", false, Blob.isPlainBlob(blob))
        }
    }

    @Test
    fun `the AAD binds the blob id and the sensitivity`() {
        val key = masterKey()
        val file = Fixture.files().first { it.getString("sensitivity") == "normal" }
        val blobId = file.getString("blob_id")
        val blob = Fixture.vaultFile(VaultPaths.blobPath(blobId)).readBytes()
        // same bytes, wrong level → authentication must fail
        try {
            Blob.decrypt(key, blobId, blob, "secret")
            error("a mismatched sensitivity must not decrypt")
        } catch (e: TamperDetected) {
            // expected
        }
        try {
            Blob.decrypt(key, "00000000000000000000000000000000", blob, "normal")
            error("a mismatched blob id must not decrypt")
        } catch (e: TamperDetected) {
            // expected
        }
    }

    @Test
    fun `a single flipped byte is reported as tampering, never as garbage`() {
        val key = masterKey()
        val file = Fixture.files().first()
        val blobId = file.getString("blob_id")
        val blob = Fixture.vaultFile(VaultPaths.blobPath(blobId)).readBytes().copyOf()
        blob[Blob.HEADER_SIZE + 3] = (blob[Blob.HEADER_SIZE + 3].toInt() xor 0x01).toByte()
        try {
            Blob.decrypt(key, blobId, blob, file.getString("sensitivity"))
            error("a flipped ciphertext byte must not decrypt")
        } catch (e: TamperDetected) {
            // expected
        }
    }

    @Test
    fun `the content store decrypts to the SQLite database the desktop wrote`() {
        val key = masterKey()
        val store = Blob.decryptStore(key, Fixture.vaultFile(VaultPaths.STORE_FILE).readBytes())
        val header = String(store.copyOfRange(0, 16), Charsets.US_ASCII)
        assertEquals("SQLite format 3\u0000", header)
        val text = String(store, Charsets.UTF_8)
        val expectedNotes = Fixture.expected.getJSONObject("folder_notes")
        for (folder in expectedNotes.keys()) {
            val note = expectedNotes.getString(folder)
            assertTrue("folder note for '$folder' not found in the store", text.contains(note.take(12)))
        }
        val fileNotes = Fixture.expected.getJSONObject("file_notes")
        for (path in fileNotes.keys()) {
            val note = fileNotes.getString(path)
            assertTrue("file note for '$path' not found in the store", text.contains(note.take(12)))
        }
    }

    @Test
    fun `pbkdf2 derivation is reproducible and salt sensitive`() {
        val meta = VaultMeta.parse(Fixture.metaJson())
        val a = KeyDerivation.deriveMasterKey(Fixture.password, meta.kdf, null)
        val b = KeyDerivation.deriveMasterKey(Fixture.password, meta.kdf, null)
        assertArrayEquals(a, b)
        val other = meta.kdf.copy(salt = ByteArray(16) { 7 })
        assertNotEquals(Hex.encode(a), Hex.encode(KeyDerivation.deriveMasterKey(Fixture.password, other, null)))
    }

    @Test
    fun `kdf parameters other than the fixture's are rejected clearly`() {
        val params = KdfParams("argon2id", ByteArray(16), 3, 262144, 4, 600000)
        try {
            KeyDerivation.deriveMasterKey("x", params, argon2 = null)
            error("argon2id without a binding must be reported as unavailable")
        } catch (e: ir.maxv.securevault.core.KdfUnavailable) {
            assertTrue(e.message!!.contains("argon2id"))
        }
    }
}
