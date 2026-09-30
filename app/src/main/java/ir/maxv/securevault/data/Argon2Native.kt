package ir.maxv.securevault.data

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2KtResult
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import ir.maxv.securevault.core.Argon2

/**
 * Argon2id from the bundled native library (`com.lambdapioneer.argon2kt`).
 *
 * The vault's parameters (t=3, m=256 MiB, p=4, hash_len=32, version 0x13) are passed through
 * unchanged, so the derived master key is identical to the desktop client's `argon2-cffi`
 * output. A wrong derivation cannot pass unnoticed: the vault canary is verified right after.
 */
class AndroidArgon2 : Argon2 {

    private val impl = Argon2Kt()

    override fun hashId(
        password: ByteArray,
        salt: ByteArray,
        timeCost: Int,
        memoryKib: Int,
        parallelism: Int,
        hashLen: Int,
    ): ByteArray {
        val result: Argon2KtResult = impl.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = timeCost,
            mCostInKibibyte = memoryKib,
            parallelism = parallelism,
            hashLengthInBytes = hashLen,
            version = Argon2Version.V13,
        )
        return result.rawHashAsByteArray()
    }
}
