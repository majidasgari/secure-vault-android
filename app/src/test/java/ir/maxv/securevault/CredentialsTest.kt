package ir.maxv.securevault

import ir.maxv.securevault.core.Credentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * The credential body parser, checked against the desktop implementation's own reading of the same
 * bodies (`tools/make_totp_fixture.py`) plus the label rules that decide what a copy button may
 * offer.
 *
 * These are `/رمزها` bodies: a `label: value` block written by the migration. A parser that reads
 * the wrong line would show a code — or offer to copy a password — for the wrong field, which is
 * worse than showing nothing, so the label sets and the `##` boundary are asserted here.
 */
class CredentialsTest {

    private val fixture: JSONObject by lazy {
        val url = CredentialsTest::class.java.getResource("/fixture/totp_vectors.json")
            ?: error("fixture missing — run tools/make_totp_fixture.py")
        JSONObject(File(URI(url.toString())).readText(Charsets.UTF_8))
    }

    @Test
    fun `every fixture body parses exactly as the desktop client parses it`() {
        val rows = fixture.getJSONArray("bodies")
        assertTrue("the fixture must carry body rows", rows.length() >= 4)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val fields = Credentials.parse(row.getString("body"))
            val where = "body #$index"
            assertEquals("$where username", row.getString("username"), fields.username)
            assertEquals("$where password", row.getString("password"), fields.password)
            assertEquals("$where otp", row.getString("otp"), fields.otp)
            assertEquals("$where site", row.getString("site"), fields.site)
            assertEquals("$where category", row.getString("category"), fields.category)
            assertEquals("$where url", row.getString("url"), fields.url)
            assertEquals("$where has_otp", row.getBoolean("has_otp"), fields.hasOtp)
            assertEquals("$where has_password", row.getBoolean("has_password"), fields.hasPassword)
        }
    }

    @Test
    fun `several fields on one line are separated, and the first one wins`() {
        val fields = Credentials.parse(
            "# ورودی\n\nسایت: example.com | دسته: آزمون\nگذرواژه: اول\nگذرواژه: دوم\n",
        )
        assertEquals("example.com", fields.site)
        assertEquals("آزمون", fields.category)
        assertEquals("اول", fields.password)
    }

    @Test
    fun `the notes section ends the field region`() {
        val fields = Credentials.parse(
            "# ورودی\n\nنام کاربری: max\n\n## یادداشت‌ها\n\nگذرواژه: این رمز نیست\nکد یکبارمصرف (otp): متن\n",
        )
        assertEquals("max", fields.username)
        assertEquals("", fields.password)
        assertEquals("", fields.otp)
    }

    @Test
    fun `the migration's parenthesised otp label is found by keyword`() {
        val fields = Credentials.parse("# x\n\nکد یکبارمصرف (otp): otpauth://totp/x?secret=JBSWY3DPEHPK3PXP\n")
        assertTrue(fields.hasOtp)
        assertEquals("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP", fields.otp)
    }

    @Test
    fun `empty markers are no value at all`() {
        val fields = Credentials.parse("# x\n\nنام کاربری: max\nگذرواژه: —\nکد یکبارمصرف (otp): -\n")
        assertEquals("max", fields.username)
        assertTrue("an em dash is not a password", !fields.hasPassword)
        assertTrue("a dash is not a code", !fields.hasOtp)
    }

    @Test
    fun `a body without fields offers nothing to copy`() {
        val fields = Credentials.parse("# یادداشت\n\nفقط متن آزاد دربارهٔ چیزهایی که رمز نیستند\n")
        assertTrue(!fields.hasPassword && !fields.hasOtp && !fields.hasUsername)
        assertEquals("", fields.site)
    }

    @Test
    fun `a card entry is not mistaken for a login`() {
        val fields = Credentials.parse(
            "# کارت\n\nسایت: bank.example | دسته: بانک و پرداخت\nشماره کارت: 6037991234567890\n",
        )
        assertEquals("bank.example", fields.site)
        assertTrue("a card number is not a password", !fields.hasPassword)
        assertTrue("a card number is not a user name", !fields.hasUsername)
    }
}
