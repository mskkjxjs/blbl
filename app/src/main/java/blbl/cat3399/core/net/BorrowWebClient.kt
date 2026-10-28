package blbl.cat3399.core.net

import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cookie-isolated HTTP client for the borrowed account.
 *
 * Uses [CookieJar.NO_COOKIES] and explicit `Cookie` headers so the borrowed account's cookies
 * never touch [BiliClient.cookies] (which belongs to the main account).
 */
internal object BorrowWebClient {
    data class Response(
        val code: Int,
        val body: String,
        val setCookies: List<String>,
    ) {
        fun json(): JSONObject = JSONObject(body)

        fun requireOk(): Response {
            if (code !in 200..299) throw IOException("HTTP $code")
            return this
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(
        url: String,
        cookieHeader: String? = null,
    ): Response = execute(Request.Builder().url(url).get(), cookieHeader)

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        cookieHeader: String? = null,
    ): Response {
        val body =
            FormBody.Builder().apply {
                for ((k, v) in form) add(k, v)
            }.build()
        return execute(Request.Builder().url(url).post(body), cookieHeader)
    }

    fun extractCookie(
        setCookies: List<String>,
        name: String,
    ): String? {
        for (raw in setCookies) {
            val pair = raw.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).trim() != name) continue
            val value = pair.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private suspend fun execute(
        builder: Request.Builder,
        cookieHeader: String?,
    ): Response {
        val ua =
            runCatching { BiliClient.prefs.userAgent }
                .getOrNull()
                .orEmpty()
                .ifBlank { AppPrefs.DEFAULT_UA }
        builder
            .header("User-Agent", ua)
            .header("Referer", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
        if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)

        val res = client.newCall(builder.build()).await()
        res.use {
            val body = withContext(Dispatchers.IO) { it.body?.string() ?: "" }
            return Response(code = it.code, body = body, setCookies = it.headers.values("Set-Cookie"))
        }
    }
}
