package ir.maxv.securevault

import ir.maxv.securevault.core.BiometricRecord
import ir.maxv.securevault.core.Blob
import ir.maxv.securevault.core.KeyDerivation
import ir.maxv.securevault.core.VaultMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a fingerprint may open the vault.
 *
 * The Keystore half (an AES key that only works after a successful `BiometricPrompt`) cannot be
 * exercised off-device, but everything the app does with what comes back *can*: parsing the record,
 * refusing a mangled one, and — the one that matters — refusing a record that does not belong to
 * this vault. A wrong "yes" here would open the wrong vault's content behind a fingerprint.
 */
class BiometricRecordTest {

    private val meta: VaultMeta get() = VaultMeta.parse(Fixture.metaJson())

    private fun masterKey(password: String = Fixture.password): ByteArray =
        KeyDerivation.deriveMasterKey(password, meta.kdf, argon2 = null)

    private fun record(password: String = Fixture.password, vaultId: String = meta.vaultId) =
        BiometricRecord(vaultId, masterKey(password))

    @Test
    fun `a record survives the round trip through the sealed file`() {
        val original = record()
        val parsed = BiometricRecord.parse(original.encode())
        assertEquals(original.vaultId, parsed.vaultId)
        assertArrayEquals(original.masterKey, parsed.masterKey)
        assertEquals(original, parsed)
        assertEquals(Blob.KEY_SIZE, parsed.masterKey.size)
    }

    @Test
    fun `toString never spills the key`() {
        val text = record().toString()
        assertFalse(text, text.contains(record().masterKey.joinToString("") { "%02x".format(it) }))
    }

    @Test
    fun `a record from this vault passes and one from anywhere else does not`() {
        assertTrue(record().matches(meta))

        // same vault id, key that does not decrypt the canary (wrong password / stale record)
        assertFalse(record(password = "not the password").matches(meta))

        // right key, another vault: a recreated vault keeps no id
        assertFalse(record(vaultId = "0000000000000000").matches(meta))
    }

    @Test
    fun `a mangled record is refused instead of half-trusted`() {
        val good = record().encode()

        val cases = mapOf(
            "not json at all" to "}{",
            "empty" to "",
            "no version" to """{"vault_id":"x","key_b64":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}""",
            "future version" to good.replace("\"v\":1", "\"v\":99"),
            "no vault id" to good.replace(meta.vaultId, ""),
            "key is not base64" to good.replace(Regex("\"key_b64\":\"[^\"]*\""), "\"key_b64\":\"!!!\""),
            "key is too short" to good.replace(Regex("\"key_b64\":\"[^\"]*\""), "\"key_b64\":\"AAAA\""),
            "no key at all" to good.replace(Regex(",\"key_b64\":\"[^\"]*\""), ""),
        )

        for ((what, json) in cases) {
            val failed = try {
                BiometricRecord.parse(json)
                false
            } catch (e: Exception) {
                true
            }
            assertTrue("must refuse: $what", failed)
        }
    }
}
