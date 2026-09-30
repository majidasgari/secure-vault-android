package ir.maxv.securevault

import ir.maxv.securevault.core.S3Client
import ir.maxv.securevault.core.S3Config
import ir.maxv.securevault.core.SyncPlanner
import ir.maxv.securevault.core.VaultPaths
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live read-only check against a real S3-compatible bucket.
 *
 * Skipped unless the coordinates are in the environment, so the suite stays offline by default:
 *
 * ```
 * SVA_S3_ENDPOINT=... SVA_S3_REGION=... SVA_S3_BUCKET=... SVA_S3_PREFIX=... \
 * SVA_S3_ACCESS=... SVA_S3_SECRET=... ./gradlew :app:testReleaseUnitTest --tests '*S3LiveTest*'
 * ```
 *
 * It proves the SigV4 implementation and the ListObjectsV2 parser against a provider that is not
 * AWS, using the very same code the app runs. It only lists and reads; it never writes.
 */
class S3LiveTest {

    private fun configOrSkip(): S3Config {
        val endpoint = System.getenv("SVA_S3_ENDPOINT") ?: ""
        val bucket = System.getenv("SVA_S3_BUCKET") ?: ""
        val access = System.getenv("SVA_S3_ACCESS") ?: ""
        val secret = System.getenv("SVA_S3_SECRET") ?: ""
        assumeTrue("live S3 credentials not provided", endpoint.isNotEmpty() && bucket.isNotEmpty() && access.isNotEmpty() && secret.isNotEmpty())
        return S3Config(
            enabled = true,
            bucket = bucket,
            prefix = System.getenv("SVA_S3_PREFIX") ?: "",
            endpoint = endpoint,
            region = System.getenv("SVA_S3_REGION") ?: "",
            accessKey = access,
            secretKey = secret,
        )
    }

    @Test
    fun `the bucket lists and a vault metadata file can be read`() {
        val config = configOrSkip()
        val client = S3Client(config)
        val objects = client.list(config.normalizedPrefix())
        assertTrue("listed no objects", objects.isNotEmpty())

        val vaultMembers = objects
            .mapNotNull { obj ->
                val prefix = config.normalizedPrefix()
                if (!obj.key.startsWith(prefix)) null else obj.key.substring(prefix.length)
            }
            .filter { SyncPlanner.eligible(it) }
        assertTrue("no eligible vault members", vaultMembers.isNotEmpty())

        // the key rule the device run broke: metadata lives *under* the prefix
        val metaBytes = client.get(VaultPaths.objectKey(config.normalizedPrefix(), VaultPaths.META_FILE))
        assertNotNull("vault metadata not readable", metaBytes)
        val metaText = String(metaBytes!!, Charsets.UTF_8)
        assertTrue(metaText.contains("vault_id"))
        assertTrue(metaText.contains("kdf"))

        // and the relative path alone must NOT resolve — that is the bug this pins down
        assertNull(
            "a relative key resolved against the bucket root",
            client.get(VaultPaths.META_FILE),
        )
    }
}
