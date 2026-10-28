package blbl.cat3399.core.net

import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Auto-renews the borrowed account's web credentials (SESSDATA/bili_jct).
 *
 * Mirrors the web "cookie refresh" flow: cookie/info -> correspond page -> cookie/refresh ->
 * confirm/refresh. Tokens are never logged.
 */
object BorrowAccountRefresher {
    private const val TAG = "BorrowAccountRefresher"
    private const val REFRESH_SOURCE = "main_web"
    private const val COOKIE_INFO_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
    private const val COOKIE_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
    private const val COOKIE_CONFIRM_URL = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
    private const val DAY_MS = 86_400_000L
    private const val FAILURE_RETRY_MS = 6 * 60 * 60 * 1000L

    private val refreshCsrfRegex = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")
    private val mutex = Mutex()

    data class RefreshResult(
        val ok: Boolean,
        val refreshed: Boolean,
        val message: String,
    )

    /** Cheap once-per-day check, safe to call before every playurl request. */
    suspend fun ensureFreshForPlayback() {
        val st = BorrowAccountStore.state()
        if (!st.active || !st.canAutoRefresh) return
        try {
            refreshIfNeeded(force = false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.w(TAG, "ensureFreshForPlayback failed", t)
        }
    }

    suspend fun refreshIfNeeded(force: Boolean = false): RefreshResult =
        mutex.withLock {
            val st = BorrowAccountStore.state()
            if (!st.configured) return@withLock RefreshResult(ok = false, refreshed = false, message = "未配置小号凭证")
            if (!st.canAutoRefresh) {
                return@withLock RefreshResult(
                    ok = false,
                    refreshed = false,
                    message = "缺少 bili_jct 或 ac_time_value，无法自动续期",
                )
            }

            val nowMs = System.currentTimeMillis()
            val epochDay = nowMs / DAY_MS
            val retryAfterFailure =
                st.refreshStatus.startsWith("续期失败") && nowMs - st.updatedAtMs >= FAILURE_RETRY_MS
            if (!force && st.lastCheckedEpochDay == epochDay && !retryAfterFailure) {
                return@withLock RefreshResult(
                    ok = true,
                    refreshed = false,
                    message = st.refreshStatus.ifBlank { "今日已检查" },
                )
            }

            try {
                doRefresh(nowMs, epochDay)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w(TAG, "refresh failed", t)
                val detail = t.message?.take(80)?.takeIf { it.isNotBlank() } ?: "未知错误"
                val message = "续期失败：$detail"
                BorrowAccountStore.recordCheck(message, epochDay)
                RefreshResult(ok = false, refreshed = false, message = message)
            }
        }

    private suspend fun doRefresh(
        nowMs: Long,
        epochDay: Long,
    ): RefreshResult {
        val st = BorrowAccountStore.state()
        val cookieHeader = st.authCookieHeader

        val infoUrl =
            BiliClient.withQuery(
                COOKIE_INFO_URL,
                mapOf("csrf" to st.biliJct),
            )
        val infoRes = BorrowWebClient.get(infoUrl, cookieHeader).requireOk()
        val info = infoRes.json()
        val infoCode = info.optInt("code", -1)
        if (infoCode != 0) {
            val msg = info.optString("message", info.optString("msg", "")).trim()
            throw IOException(if (msg.isNotBlank()) "cookie/info $infoCode $msg" else "cookie/info $infoCode")
        }

        val infoData = info.optJSONObject("data") ?: JSONObject()
        if (!infoData.optBoolean("refresh", false)) {
            BorrowAccountStore.recordCheck("检查正常，无需续期", epochDay)
            return RefreshResult(ok = true, refreshed = false, message = "检查正常，无需续期")
        }

        val ts = infoData.optLong("timestamp", nowMs).takeIf { it > 0 } ?: nowMs
        val correspondPath = withContext(Dispatchers.Default) { WebCookieMaintainer.getCorrespondPath(ts) }
        val html =
            BorrowWebClient.get(
                "https://www.bilibili.com/correspond/1/$correspondPath",
                cookieHeader,
            ).requireOk().body
        val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (refreshCsrf.isBlank()) throw IOException("refresh_csrf 获取失败")

        val oldRefreshToken = st.refreshToken
        val refreshRes =
            BorrowWebClient.postForm(
                COOKIE_REFRESH_URL,
                form =
                    mapOf(
                        "csrf" to st.biliJct,
                        "refresh_csrf" to refreshCsrf,
                        "source" to REFRESH_SOURCE,
                        "refresh_token" to oldRefreshToken,
                    ),
                cookieHeader = cookieHeader,
            ).requireOk()
        val refreshJson = refreshRes.json()
        val refreshCode = refreshJson.optInt("code", -1)
        if (refreshCode != 0) {
            val msg = refreshJson.optString("message", refreshJson.optString("msg", "")).trim()
            throw IOException(if (msg.isNotBlank()) "cookie/refresh $refreshCode $msg" else "cookie/refresh $refreshCode")
        }

        val refreshData = refreshJson.optJSONObject("data") ?: JSONObject()
        when (refreshData.optInt("status", 0)) {
            1 -> throw IOException("ac_time_value 已过期，请重新登录小号")
            2 -> throw IOException("续期被拒绝，请重新登录小号")
        }

        val newSessData = BorrowWebClient.extractCookie(refreshRes.setCookies, "SESSDATA") ?: st.sessData
        val newBiliJct = BorrowWebClient.extractCookie(refreshRes.setCookies, "bili_jct") ?: st.biliJct
        val newRefreshToken = refreshData.optString("refresh_token", "").trim().ifBlank { oldRefreshToken }

        BorrowAccountStore.applyRefresh(
            newSessData = newSessData,
            newBiliJct = newBiliJct,
            newRefreshToken = newRefreshToken,
            status = "已续期",
            epochDay = epochDay,
        )

        runCatching {
            BorrowWebClient.postForm(
                COOKIE_CONFIRM_URL,
                form =
                    mapOf(
                        "csrf" to newBiliJct,
                        "refresh_token" to oldRefreshToken,
                    ),
                cookieHeader = "SESSDATA=$newSessData; bili_jct=$newBiliJct",
            ).requireOk()
        }.onFailure { AppLog.w(TAG, "confirm/refresh failed", it) }

        AppLog.i(TAG, "borrow account refreshed")
        return RefreshResult(ok = true, refreshed = true, message = "已续期")
    }
}
