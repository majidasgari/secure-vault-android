package ir.maxv.securevault

import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Access to the fixture produced by `tools/make_test_fixture.py` (i.e. by the desktop
 * implementation itself), plus its expected values.
 */
object Fixture {

    val root: File by lazy {
        val url = Fixture::class.java.getResource("/fixture/expected.json")
            ?: error("fixture missing — run tools/make_test_fixture.py")
        File(URI(url.toString())).parentFile
    }

    val expected: JSONObject by lazy { JSONObject(File(root, "expected.json").readText(Charsets.UTF_8)) }

    val vaultDir: File get() = File(root, "vault")

    fun vaultFile(relative: String): File = File(vaultDir, relative)

    val password: String get() = expected.getString("password")

    fun metaJson(): String = File(vaultDir, ".vault-meta.json").readText(Charsets.UTF_8)

    fun files(): List<JSONObject> {
        val array = expected.getJSONArray("files")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}
