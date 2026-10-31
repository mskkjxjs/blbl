package blbl.cat3399.core.net

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.log.AppLog
import org.json.JSONObject

/**
 * Stores the secondary ("borrowed") account credentials used for playurl only.
 *
 * Deliberately isolated from [CookieStore]/[AccountSessionStore]: these credentials must never
 * leak into normal API requests, history reporting, or interactivity flows.
 */
object BorrowAccountStore {
    private const val TAG = "BorrowAccountStore"
    private const val PREFS = "blbl_borrow_account"
    private const val KEY_STATE = "state"
    private const val MAX_VALUE_LENGTH = 2048

    data class State(
        val sessData: String = "",
        val biliJct: String = "",
        val refreshToken: String = "",
        val mid: Long = 0L,
        val name: String = "",
        val avatarUrl: String? = null,
        val enabled: Boolean = false,
        val autoRefreshEnabled: Boolean = true,
        val refreshStatus: String = "",
        val lastCheckedEpochDay: Long = -1L,
        val updatedAtMs: Long = 0L,
    ) {
        val configured: Boolean
            get() = sessData.isNotBlank()

        val active: Boolean
            get() = enabled && configured

        /** Credentials are complete enough to perform the refresh flow. */
        val canAutoRefresh: Boolean
            get() = configured && biliJct.isNotBlank() && refreshToken.isNotBlank()

        /** Automatic (unattended) renewal: user preference plus complete credentials. */
        val autoRefreshActive: Boolean
            get() = autoRefreshEnabled && canAutoRefresh

        val cookieHeader: String
            get() = "SESSDATA=$sessData"

        val authCookieHeader: String
            get() =
                buildString {
                    append("SESSDATA=").append(sessData)
                    if (biliJct.isNotBlank()) append("; bili_jct=").append(biliJct)
                }
    }

    private val lock = Any()

    @Volatile
    private var current: State = State()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            prefs = p
            current = parse(p.getString(KEY_STATE, null))
        }
        AppLog.i(TAG, "init configured=${current.configured} active=${current.active} canRefresh=${current.canAutoRefresh}")
    }

    fun state(): State = current

    fun isPlaybackActive(): Boolean = current.active

    fun playbackCookieHeader(): String? = current.takeIf { it.active }?.cookieHeader

    fun saveCredentials(
        sessData: String,
        biliJct: String,
        refreshToken: String,
    ): Result<Unit> {
        val sess = normalizeSessData(sessData)
            ?: return Result.failure(IllegalArgumentException("SESSDATA 格式不正确"))
        val jct = normalizeOptionalToken(biliJct, "bili_jct")
            ?: return Result.failure(IllegalArgumentException("bili_jct 格式不正确"))
        val refresh = normalizeOptionalToken(refreshToken, "ac_time_value")
            ?: return Result.failure(IllegalArgumentException("ac_time_value 格式不正确"))

        synchronized(lock) {
            val cur = current
            val sameCookie = sess == cur.sessData
            val switched = !cur.configured || !sameCookie
            val next =
                cur.copy(
                    sessData = sess,
                    biliJct = jct,
                    refreshToken = refresh,
                    mid = if (sameCookie) cur.mid else 0L,
                    name = if (sameCookie) cur.name else "",
                    avatarUrl = if (sameCookie) cur.avatarUrl else null,
                    enabled = if (switched) true else cur.enabled,
                    refreshStatus = if (sameCookie) cur.refreshStatus else "",
                    lastCheckedEpochDay = if (sameCookie) cur.lastCheckedEpochDay else -1L,
                    updatedAtMs = System.currentTimeMillis(),
                )
            persistLocked(next)
        }
        return Result.success(Unit)
    }

    fun updateProfile(
        mid: Long,
        name: String,
        avatarUrl: String?,
    ) {
        synchronized(lock) {
            val cur = current
            if (!cur.configured) return
            persistLocked(
                cur.copy(
                    mid = mid.coerceAtLeast(0L),
                    name = name.trim(),
                    avatarUrl = avatarUrl?.trim()?.takeIf { it.isNotBlank() },
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setEnabled(enabled: Boolean): Boolean {
        synchronized(lock) {
            val cur = current
            if (enabled && !cur.configured) return false
            if (cur.enabled == enabled) return true
            persistLocked(cur.copy(enabled = enabled, updatedAtMs = System.currentTimeMillis()))
        }
        return true
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        synchronized(lock) {
            val cur = current
            if (cur.autoRefreshEnabled == enabled) return
            persistLocked(cur.copy(autoRefreshEnabled = enabled, updatedAtMs = System.currentTimeMillis()))
        }
    }

    fun applyRefresh(
        newSessData: String,
        newBiliJct: String,
        newRefreshToken: String,
        status: String,
        epochDay: Long,
    ) {
        synchronized(lock) {
            val cur = current
            if (!cur.configured) return
            val sess = normalizeSessData(newSessData) ?: cur.sessData
            persistLocked(
                cur.copy(
                    sessData = sess,
                    biliJct = normalizeOptionalToken(newBiliJct, "bili_jct")?.takeIf { it.isNotBlank() } ?: cur.biliJct,
                    refreshToken =
                        normalizeOptionalToken(newRefreshToken, "ac_time_value")?.takeIf { it.isNotBlank() } ?: cur.refreshToken,
                    refreshStatus = status,
                    lastCheckedEpochDay = epochDay,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun recordCheck(
        status: String,
        epochDay: Long,
    ) {
        synchronized(lock) {
            val cur = current
            if (!cur.configured) return
            persistLocked(
                cur.copy(
                    refreshStatus = status,
                    lastCheckedEpochDay = epochDay,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            current = State()
            if (::prefs.isInitialized) prefs.edit().remove(KEY_STATE).apply()
        }
        AppLog.i(TAG, "credentials cleared")
    }

    private fun persistLocked(state: State) {
        current = state
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_STATE, serialize(state).toString()).apply()
    }

    private fun serialize(state: State): JSONObject =
        JSONObject()
            .put("sessData", state.sessData)
            .put("biliJct", state.biliJct)
            .put("refreshToken", state.refreshToken)
            .put("mid", state.mid)
            .put("name", state.name)
            .put("avatarUrl", state.avatarUrl ?: "")
            .put("enabled", state.enabled)
            .put("autoRefreshEnabled", state.autoRefreshEnabled)
            .put("refreshStatus", state.refreshStatus)
            .put("lastCheckedEpochDay", state.lastCheckedEpochDay)
            .put("updatedAtMs", state.updatedAtMs)

    private fun parse(raw: String?): State {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return State()
        return runCatching {
            val obj = JSONObject(text)
            State(
                sessData = normalizeSessData(obj.optString("sessData", "")).orEmpty(),
                biliJct = normalizeOptionalToken(obj.optString("biliJct", ""), "bili_jct").orEmpty(),
                refreshToken = normalizeOptionalToken(obj.optString("refreshToken", ""), "ac_time_value").orEmpty(),
                mid = obj.optLong("mid", 0L).coerceAtLeast(0L),
                name = obj.optString("name", ""),
                avatarUrl = obj.optString("avatarUrl", "").takeIf { it.isNotBlank() },
                enabled = obj.optBoolean("enabled", false),
                autoRefreshEnabled = obj.optBoolean("autoRefreshEnabled", true),
                refreshStatus = obj.optString("refreshStatus", ""),
                lastCheckedEpochDay = obj.optLong("lastCheckedEpochDay", -1L),
                updatedAtMs = obj.optLong("updatedAtMs", 0L),
            )
        }.getOrElse {
            AppLog.w(TAG, "parse state failed", it)
            State()
        }
    }

    /** Accepts a bare value, a `SESSDATA=...` fragment, or a pasted cookie string. */
    fun normalizeSessData(raw: String): String? {
        val v = normalizeCookieValue(raw, "SESSDATA") ?: return null
        return v.takeIf { it.isNotBlank() }
    }

    /** Accepts a bare value or a `name=...` fragment; blank input means "not provided". */
    fun normalizeOptionalToken(
        raw: String,
        name: String,
    ): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return normalizeCookieValue(trimmed, name)
    }

    private fun normalizeCookieValue(
        raw: String,
        name: String,
    ): String? {
        var v = raw.trim()
        if (v.isEmpty()) return null
        val marker = "$name="
        val idx = v.indexOf(marker)
        if (idx >= 0) v = v.substring(idx + marker.length)
        v = v.substringBefore(';').substringBefore('\n').trim()
        if (v.isEmpty() || v.length > MAX_VALUE_LENGTH) return null
        if (v.any { it.isWhitespace() || it.code < 0x21 || it == '"' || it == '\'' || it == '\\' }) return null
        return v
    }
}
