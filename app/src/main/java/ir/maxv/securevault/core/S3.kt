package ir.maxv.securevault.core

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Everything needed to reach one bucket: coordinates from the vault, keys entered by the user. */
data class S3Config(
    val enabled: Boolean = false,
    val bucket: String = "",
    val prefix: String = "",
    val endpoint: String = "",
    val region: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
) {
    val configured: Boolean
        get() = enabled && bucket.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()

    /** Key prefix with a trailing slash (`""` for the bucket root) — mirrors `S3Config.normalized_prefix`. */
    fun normalizedPrefix(): String {
        val p = prefix.trim().trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    companion object {
        fun fromVault(sync: SyncSettings, accessKey: String, secretKey: String): S3Config =
            S3Config(
                enabled = sync.enabled,
                bucket = sync.bucket,
                prefix = sync.prefix,
                endpoint = sync.endpoint,
                region = sync.region,
                accessKey = accessKey,
                secretKey = secretKey,
            )
    }
}

/** One object in the bucket. */
data class S3Object(
    val key: String,
    val size: Long,
    val etag: String?,
    val modified: Long,
)

class S3Exception(val code: String, message: String, val status: Int = 0) : Exception(message)

/**
 * AWS SigV4 signing (the same discipline as the desktop client's stdlib backend).
 *
 * Kept as pure functions so the test suite can compare it against independently
 * generated reference values.
 */
object SigV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val SERVICE = "s3"

    fun hmac(key: ByteArray, message: String): ByteArray =
        Crypto.hmacSha256(key, message.toByteArray(Charsets.UTF_8))

    /** AWS4 date → region → service → request key chain. */
    fun signingKey(secretKey: String, datestamp: String, region: String, service: String = SERVICE): ByteArray {
        val kDate = hmac(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), datestamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    fun canonicalRequest(
        method: String,
        path: String,
        canonicalQuery: String,
        canonicalHeaders: String,
        signedHeaders: String,
        payloadHash: String,
    ): String = listOf(method, path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash)
        .joinToString("\n")

    fun stringToSign(amzDate: String, scope: String, canonicalRequest: String): String =
        listOf(ALGORITHM, amzDate, scope, Hex.encode(Crypto.sha256(canonicalRequest.toByteArray(Charsets.UTF_8))))
            .joinToString("\n")

    data class Signed(
        val headers: Map<String, String>,
        val authorization: String,
        val scope: String,
        val canonicalRequest: String,
        val stringToSign: String,
        val signature: String,
    )

    /**
     * Sign one request.
     *
     * @param headers additional headers that must also be signed (name → value).
     */
    fun sign(
        accessKey: String,
        secretKey: String,
        region: String,
        host: String,
        method: String,
        path: String,
        canonicalQuery: String = "",
        payload: ByteArray = ByteArray(0),
        amzDate: String,
        datestamp: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Signed {
        val payloadHash = Crypto.sha256Hex(payload)
        val headers = LinkedHashMap<String, String>()
        headers["host"] = host
        headers["x-amz-content-sha256"] = payloadHash
        headers["x-amz-date"] = amzDate
        for ((k, v) in extraHeaders) headers[k.lowercase(Locale.ROOT)] = v

        val sortedNames = headers.keys.sorted()
        val canonicalHeaders = sortedNames.joinToString("") { "$it:${headers[it]!!.trim()}\n" }
        val signedHeaders = sortedNames.joinToString(";")
        val canonicalRequest = canonicalRequest(method, path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash)
        val scope = "$datestamp/$region/${SERVICE}/aws4_request"
        val sts = stringToSign(amzDate, scope, canonicalRequest)
        val signature = Hex.encode(hmac(signingKey(secretKey, datestamp, region), sts))
        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        val outHeaders = LinkedHashMap<String, String>()
        outHeaders["x-amz-content-sha256"] = payloadHash
        outHeaders["x-amz-date"] = amzDate
        for ((k, v) in extraHeaders) outHeaders[k] = v
        return Signed(outHeaders, authorization, scope, canonicalRequest, sts, signature)
    }

    fun amzDate(now: Date = Date()): String = format(now, "yyyyMMdd'T'HHmmss'Z'")

    fun datestamp(now: Date = Date()): String = format(now, "yyyyMMdd")

    private fun format(date: Date, pattern: String): String {
        val fmt = SimpleDateFormat(pattern, Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    /** URI path encoding that matches the desktop client (`urllib.parse.quote(key, safe="/-_.~")`). */
    fun encodePath(segment: String): String = segment
        .split("/")
        .joinToString("/") { encodePart(it, keepSlash = true) }

    fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")

    private fun encodePart(part: String, keepSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in part.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            val unreserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')
            if (unreserved) sb.append(c) else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

/**
 * Minimal S3 client: `get` + `list` only — this client is read-only by construction and
 * never writes, deletes or takes the vault's write lock.
 */
class S3Client(
    val config: S3Config,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private val region: String = config.region.trim().ifEmpty { "us-east-1" }
    private val scheme: String
    private val host: String
    private val basePath: String
    private val virtualHosted: Boolean

    init {
        val endpoint = config.endpoint.trim()
        if (endpoint.isNotEmpty()) {
            val uri = URI(if (endpoint.contains("://")) endpoint else "https://$endpoint")
            scheme = uri.scheme ?: "https"
            host = uri.rawAuthority ?: uri.host
            basePath = (uri.rawPath ?: "").trimEnd('/')
            virtualHosted = false
        } else {
            scheme = "https"
            host = "${config.bucket.trim()}.s3.$region.amazonaws.com"
            basePath = ""
            virtualHosted = true
        }
    }

    /** `(path, canonicalQuery)` for an object (or bucket) request. */
    private fun objectPath(key: String?, query: Map<String, String>): Pair<String, String> {
        val encodedKey = key?.let { SigV4.encodePath(it) } ?: ""
        val path = if (virtualHosted) {
            if (encodedKey.isEmpty()) "$basePath/" else "$basePath/$encodedKey"
        } else {
            val bucket = SigV4.encodePath(config.bucket.trim())
            if (encodedKey.isEmpty()) "$basePath/$bucket" else "$basePath/$bucket/$encodedKey"
        }
        val canonicalQuery = query.toSortedMap()
            .map { (k, v) -> "${SigV4.encodeQueryValue(k)}=${SigV4.encodeQueryValue(v)}" }
            .joinToString("&")
        return path to canonicalQuery
    }

    private fun open(
        method: String,
        key: String?,
        query: Map<String, String> = emptyMap(),
        payload: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val (path, canonicalQuery) = objectPath(key, query)
        val signed = SigV4.sign(
            accessKey = config.accessKey,
            secretKey = config.secretKey,
            region = region,
            host = host,
            method = method,
            path = path,
            canonicalQuery = canonicalQuery,
            payload = payload,
            amzDate = SigV4.amzDate(),
            datestamp = SigV4.datestamp(),
            extraHeaders = extraHeaders,
        )
        val target = "$scheme://$host$path" + if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
        val conn = URI(target).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        for ((name, value) in signed.headers) conn.setRequestProperty(name, value)
        conn.setRequestProperty("Authorization", signed.authorization)
        if (payload.isNotEmpty()) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
        }
        return conn
    }

    /** Return the object bytes, or `null` when the key does not exist. */
    fun get(key: String): ByteArray? {
        val conn = open("GET", key)
        return try {
            val status = conn.responseCode
            when {
                status == 200 -> conn.inputStream.readBytes()
                status == 403 || status == 404 -> null
                else -> throw S3Exception("s3_get_failed", "GET $key → HTTP $status", status)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream one object into [target]. Returns the byte count, or `null` when the key is missing.
     * The state of the network/disk is left untouched on failure (temp file + atomic move).
     */
    fun download(key: String, target: File, onProgress: ((Long) -> Unit)? = null): Long? {
        val conn = open("GET", key)
        try {
            val status = conn.responseCode
            if (status == 403 || status == 404) return null
            if (status != 200) throw S3Exception("s3_get_failed", "GET $key → HTTP $status", status)
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            var written = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(if (total > 0) written * 100 / total else written)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return written
        } finally {
            conn.disconnect()
        }
    }

    /** ETag of an object without downloading it, or `null` when missing. */
    fun etag(key: String): String? {
        val conn = open("HEAD", key)
        return try {
            val status = conn.responseCode
            if (status == 403 || status == 404) null
            else if (status != 200) throw S3Exception("s3_head_failed", "HEAD $key → HTTP $status", status)
            else conn.getHeaderField("ETag")?.trim('"')
        } finally {
            conn.disconnect()
        }
    }

    /** List every object under `prefix` (paginated). */
    fun list(prefix: String): List<S3Object> {
        val out = ArrayList<S3Object>()
        var token: String? = null
        while (true) {
            val query = LinkedHashMap<String, String>()
            query["list-type"] = "2"
            if (prefix.isNotEmpty()) query["prefix"] = prefix
            if (token != null) query["continuation-token"] = token!!
            val conn = open("GET", null, query)
            val body = try {
                val status = conn.responseCode
                if (status != 200) {
                    throw S3Exception("s3_list_failed", "LIST $prefix → HTTP $status", status)
                }
                conn.inputStream.readBytes()
            } finally {
                conn.disconnect()
            }
            val page = parseListXml(body)
            out.addAll(page.objects)
            if (!page.truncated || page.token.isNullOrEmpty()) return out
            token = page.token
        }
    }

    data class ListPage(val objects: List<S3Object>, val truncated: Boolean, val token: String?)

    companion object {
        /** Parse a ListObjectsV2 response (namespace-agnostic). */
        fun parseListXml(body: ByteArray): ListPage {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val doc = try {
                factory.newDocumentBuilder().parse(body.inputStream())
            } catch (e: Exception) {
                throw S3Exception("s3_list_failed", "bad_xml: ${e.message}")
            }
            val objects = ArrayList<S3Object>()
            val contents = doc.getElementsByTagName("Contents")
            for (i in 0 until contents.length) {
                val node = contents.item(i)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val el = node as org.w3c.dom.Element
                val key = text(el, "Key") ?: continue
                objects.add(
                    S3Object(
                        key = key,
                        size = text(el, "Size")?.toLongOrNull() ?: 0L,
                        etag = text(el, "ETag")?.trim('"')?.ifEmpty { null },
                        modified = parseIso(text(el, "LastModified")),
                    )
                )
            }
            val truncated = doc.getElementsByTagName("IsTruncated").item(0)?.textContent?.trim() == "true"
            val token = doc.getElementsByTagName("NextContinuationToken").item(0)?.textContent?.trim()
            return ListPage(objects, truncated, token)
        }

        private fun text(parent: org.w3c.dom.Element, tag: String): String? {
            val nodes = parent.getElementsByTagName(tag)
            if (nodes.length == 0) return null
            val value = nodes.item(0).textContent
            return if (value.isNullOrEmpty()) null else value.trim()
        }

        private fun parseIso(value: String?): Long {
            if (value.isNullOrEmpty()) return 0L
            return try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val normalised = value.replace("Z", "").substringBefore('.')
                fmt.parse(normalised)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }
}
