package ir.maxv.securevault

import ir.maxv.securevault.core.Hex
import ir.maxv.securevault.core.S3Config
import ir.maxv.securevault.core.S3Object
import ir.maxv.securevault.core.SigV4
import ir.maxv.securevault.core.SyncPlanner
import ir.maxv.securevault.core.SyncState
import ir.maxv.securevault.core.VaultPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing and the sync planner.
 *
 * The reference values in the fixture were computed by an independent implementation
 * (`tools/make_test_fixture.py`, pure Python `hmac`), so this is not a self-fulfilling test.
 */
class S3AndSyncTest {

    private val reference get() = Fixture.expected.getJSONObject("sigv4")

    @Test
    fun `the sigv4 signing key matches the independent reference`() {
        val key = SigV4.signingKey(
            secretKey = reference.getString("secret"),
            datestamp = reference.getString("datestamp"),
            region = reference.getString("region"),
        )
        assertEquals(reference.getString("signing_key_hex"), Hex.encode(key))
    }

    @Test
    fun `canonical request, string to sign and signature match the reference`() {
        val canonical = SigV4.canonicalRequest(
            method = reference.getString("method"),
            path = reference.getString("path"),
            canonicalQuery = reference.getString("query"),
            canonicalHeaders = "host:s3.ir-thr-at1.arvanstorage.ir\n" +
                "x-amz-content-sha256:${reference.getString("payload_hash")}\n" +
                "x-amz-date:${reference.getString("amz_date")}\n",
            signedHeaders = "host;x-amz-content-sha256;x-amz-date",
            payloadHash = reference.getString("payload_hash"),
        )
        assertEquals(reference.getString("canonical_request"), canonical)
    }

    @Test
    fun `a full signature over a fixed request matches the reference`() {
        val signed = SigV4.sign(
            accessKey = reference.getString("access"),
            secretKey = reference.getString("secret"),
            region = reference.getString("region"),
            host = "s3.ir-thr-at1.arvanstorage.ir",
            method = reference.getString("method"),
            path = reference.getString("path"),
            canonicalQuery = reference.getString("query"),
            payload = ByteArray(0),
            amzDate = reference.getString("amz_date"),
            datestamp = reference.getString("datestamp"),
        )
        assertEquals(reference.getString("string_to_sign"), signed.stringToSign)
        assertEquals(reference.getString("signature"), signed.signature)
        assertEquals(reference.getString("authorization"), signed.authorization)
    }

    @Test
    fun `the path is encoded like the desktop client even for persian keys`() {
        assertEquals("/bucket/a/b.md", "/bucket/" + SigV4.encodePath("a/b.md"))
        val encoded = SigV4.encodePath("یادداشت/سفر.md")   // ی = U+06CC = DB 8C, س = U+0633 = D8 B3
        assertTrue(encoded.startsWith("%DB%8C"))
        assertTrue(encoded.contains("/"))
        assertEquals("a%20b", SigV4.encodePath("a b"))
    }

    @Test
    fun `the object path mirrors the vault home layout`() {
        assertEquals("files/ab/abcdef.enc", VaultPaths.blobPath("abcdef"))
        assertEquals("x", VaultPaths.parentOf("x/y.md"))
        assertEquals("", VaultPaths.parentOf("y.md"))
        assertEquals("diary.md", VaultPaths.join("", "diary.md"))
        assertEquals("a/b/diary.md", VaultPaths.join("a/b", "diary.md"))
    }

    @Test
    fun `bucket keys always carry the sync prefix`() {
        // the regression that broke the first device run: relative paths were sent to S3 as-is,
        // asking for an object one level above the vault (a silent 404)
        assertEquals("sync/.vault-meta.json", VaultPaths.objectKey("sync/", ".vault-meta.json"))
        assertEquals("sync/meta.sqlite", VaultPaths.objectKey("sync", "meta.sqlite"))
        assertEquals("sync/files/ab/abcdef.enc", VaultPaths.objectKey("sync/", VaultPaths.blobPath("abcdef")))
        assertEquals("a/b/x.enc", VaultPaths.objectKey("", "a/b/x.enc"))
        assertEquals("deep/pre/fix/x.md", VaultPaths.objectKey("/deep/pre/fix/", "/x.md"))
        // never a doubled slash, whatever the caller passes
        assertFalse(VaultPaths.objectKey("sync/", "/x.md").contains("//"))
        assertFalse(VaultPaths.objectKey("/sync", "x.md").contains("//"))
    }

    @Test
    fun `derived and runtime files are never mirrored`() {
        assertTrue(VaultPaths.isExcluded("semantic/semantic.db"))
        assertTrue(VaultPaths.isExcluded("x/cache/y"))
        assertTrue(VaultPaths.isExcluded(".secure-vault.lock"))
        assertTrue(VaultPaths.isExcluded("store.1234.abcd.dec"))
        assertTrue(VaultPaths.isExcluded("meta.sqlite-wal"))
        assertTrue(VaultPaths.isExcluded("notes/thing.tmp"))
        assertFalse(VaultPaths.isExcluded("meta.sqlite"))
        assertFalse(VaultPaths.isExcluded("secure.store"))
        assertFalse(VaultPaths.isExcluded(".vault-meta.json"))
        assertFalse(VaultPaths.isExcluded("files/aa/aabb.enc"))
    }

    @Test
    fun `the planner downloads what changed and never uploads anything`() {
        val remote = mapOf(
            "meta.sqlite" to S3Object("p/meta.sqlite", 1000, "etag-a", 0),
            "secure.store" to S3Object("p/secure.store", 2000, "etag-b", 0),
            "files/aa/aa1.enc" to S3Object("p/files/aa/aa1.enc", 10, "etag-c", 0),
        )
        val base = mapOf(
            "meta.sqlite" to ir.maxv.securevault.core.SyncEntry(900, "etag-old", 1),
            "files/aa/aa1.enc" to ir.maxv.securevault.core.SyncEntry(10, "etag-c", 1),
            "files/bb/bb2.enc" to ir.maxv.securevault.core.SyncEntry(20, "etag-d", 1),
        )
        val plan = SyncPlanner.plan(remote, base, setOf("meta.sqlite", "files/aa/aa1.enc", "files/bb/bb2.enc"))
        assertEquals(listOf("meta.sqlite", "secure.store"), plan.downloads)
        assertEquals(listOf("files/bb/bb2.enc"), plan.deletes)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `a missing local file is downloaded again even when the etag matches`() {
        val remote = mapOf("secure.store" to S3Object("p/secure.store", 10, "etag", 0))
        val base = mapOf("secure.store" to ir.maxv.securevault.core.SyncEntry(10, "etag", 1))
        val plan = SyncPlanner.plan(remote, base, emptySet())
        assertEquals(listOf("secure.store"), plan.downloads)
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `sync state survives a json round trip`() {
        val state = SyncState()
        state.record("secure.store", 42, "etag-x", 111)
        state.record("files/aa/aa1.enc", 7, null, 222)
        val restored = SyncState.fromJson(state.toJson())
        assertEquals(2, restored.size)
        assertEquals(42L, restored.entry("secure.store")!!.size)
        assertEquals("etag-x", restored.entry("secure.store")!!.etag)
        assertEquals(222L, restored.lastSyncAt())
    }

    @Test
    fun `a broken state file degrades to an empty state instead of crashing`() {
        assertEquals(0, SyncState.fromJson("{not json").size)
        assertEquals(0, SyncState.fromJson(null).size)
    }

    @Test
    fun `coordinates come from the vault settings, credentials from the device`() {
        val meta = ir.maxv.securevault.core.VaultMeta.parse(Fixture.metaJson())
        assertEquals("test-bucket", meta.sync.bucket)
        val config = S3Config.fromVault(meta.sync, "AKIA", "SECRET")
        assertEquals("sync/", config.normalizedPrefix())
        assertTrue(config.configured)
        // the credentials are device-local, the coordinates are the vault's
        assertEquals("SECRET", config.secretKey)
        val pathStyle = S3Config(
            enabled = true,
            bucket = "bucket-name",
            prefix = "/sync/",
            endpoint = "https://s3.example.com/base/",
            region = "r",
            accessKey = "a",
            secretKey = "s",
        )
        assertEquals("sync/", pathStyle.normalizedPrefix())
        assertFalse(S3Config(bucket = "b", accessKey = "a", secretKey = "s").configured)
    }
}
