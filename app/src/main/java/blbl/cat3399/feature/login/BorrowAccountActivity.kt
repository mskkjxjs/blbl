package blbl.cat3399.feature.login

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.BorrowAccountStore
import blbl.cat3399.core.net.BorrowWebClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ActivityBorrowAccountBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class BorrowAccountActivity : BaseActivity() {
    private lateinit var binding: ActivityBorrowAccountBinding
    private var pollJob: Job? = null
    private var saveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBorrowAccountBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnQr.setOnClickListener { startWebQrFlow() }
        binding.btnSave.setOnClickListener { saveFromFields() }
        binding.btnBack.setOnClickListener { finish() }

        prefillFields()
        binding.switchAutoRefresh.setOnCheckedChangeListener { _, checked ->
            BorrowAccountStore.setAutoRefreshEnabled(checked)
            renderState()
        }
        if (BorrowAccountStore.state().configured) {
            renderState(statusOverride = "小号已配置；如需更换，可扫码或直接修改下方三项 Cookie 后保存")
        } else {
            renderState()
            binding.ivQr.post { startWebQrFlow() }
        }
        binding.btnQr.post { binding.btnQr.requestFocus() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            binding.btnQr.post { binding.btnQr.requestFocus() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (currentFocus == null) binding.btnQr.post { binding.btnQr.requestFocus() }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        saveJob?.cancel()
        super.onDestroy()
    }

    private fun prefillFields() {
        val st = BorrowAccountStore.state()
        binding.etSessdata.setText(st.sessData)
        binding.etBiliJct.setText(st.biliJct)
        binding.etAcTime.setText(st.refreshToken)
        binding.switchAutoRefresh.isChecked = st.autoRefreshEnabled
    }

    private fun renderState(statusOverride: String? = null) {
        val st = BorrowAccountStore.state()
        val enabledText =
            when {
                !st.configured -> "取流：使用当前帐号"
                st.enabled -> "取流：使用小号（强制 Web 接口）"
                else -> "取流：已停用（使用当前帐号）"
            }
        binding.tvStatus.text = statusOverride ?: enabledText
        binding.tvDetail.text =
            buildString {
                append("小号：")
                append(
                    when {
                        !st.configured -> "未配置"
                        st.name.isNotBlank() -> st.name
                        st.mid > 0L -> "UID ${st.mid}"
                        else -> "已配置"
                    },
                )
                append('\n')
                append("自动续期：")
                append(
                    when {
                        !st.configured -> "未配置"
                        !st.autoRefreshEnabled ->
                            when {
                                !st.canAutoRefresh -> "已关闭（缺少 bili_jct / ac_time_value）"
                                st.refreshStatus.isNotBlank() -> "已关闭 · ${st.refreshStatus}"
                                else -> "已关闭"
                            }
                        !st.canAutoRefresh -> "缺少 bili_jct / ac_time_value"
                        st.refreshStatus.isNotBlank() -> st.refreshStatus
                        else -> "已就绪（每日检查）"
                    },
                )
                if (st.configured) append("\n观看历史、弹幕与互动仍使用当前帐号")
            }
    }

    private fun saveFromFields() {
        if (saveJob?.isActive == true) return
        val sessData = BorrowAccountStore.normalizeSessData(binding.etSessdata.text?.toString().orEmpty())
        if (sessData == null) {
            AppToast.show(this, "SESSDATA 不能为空，且不能包含空白字符")
            binding.etSessdata.requestFocus()
            return
        }
        val biliJct = BorrowAccountStore.normalizeOptionalToken(binding.etBiliJct.text?.toString().orEmpty(), "bili_jct")
        if (biliJct == null) {
            AppToast.show(this, "bili_jct 格式不正确")
            binding.etBiliJct.requestFocus()
            return
        }
        val acTime = BorrowAccountStore.normalizeOptionalToken(binding.etAcTime.text?.toString().orEmpty(), "ac_time_value")
        if (acTime == null) {
            AppToast.show(this, "ac_time_value 格式不正确")
            binding.etAcTime.requestFocus()
            return
        }

        saveJob =
            lifecycleScope.launch {
                binding.btnSave.isEnabled = false
                binding.tvStatus.text = "正在保存并校验..."
                try {
                    applyCredentials(
                        sessData = sessData,
                        biliJct = biliJct,
                        refreshToken = acTime,
                        source = "manual",
                    )
                } finally {
                    binding.btnSave.isEnabled = true
                }
            }
    }

    private fun startWebQrFlow() {
        pollJob?.cancel()
        binding.btnQr.isEnabled = false
        binding.tvStatus.text = "正在申请小号二维码..."
        binding.tvDebug.text = ""
        pollJob =
            lifecycleScope.launch {
                try {
                    val genRes = BorrowWebClient.get(GENERATE_URL)
                    if (genRes.code !in 200..299) error("HTTP ${genRes.code}")
                    val gen = JSONObject(genRes.body)
                    if (gen.optInt("code", -1) != 0) {
                        error(gen.optString("message", gen.optString("msg", "申请二维码失败")))
                    }
                    val data = gen.optJSONObject("data") ?: JSONObject()
                    val url = data.optString("url", "").trim()
                    val key = data.optString("qrcode_key", "").trim()
                    if (url.isBlank() || key.isBlank()) error("二维码数据不完整")

                    val size = targetQrSizePx()
                    binding.ivQr.setImageBitmap(withContext(Dispatchers.Default) { makeQr(url, size) })
                    binding.btnQr.isEnabled = true
                    binding.tvStatus.text = "请使用哔哩哔哩 App 扫码登录小号"
                    pollWebQr(key)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.w(TAG, "web qr generate failed", t)
                    binding.btnQr.isEnabled = true
                    binding.tvStatus.text = "申请二维码失败：${t.message ?: t.javaClass.simpleName}"
                }
            }
    }

    private suspend fun pollWebQr(key: String) {
        val pollUrl = BiliClient.withQuery(POLL_URL, mapOf("qrcode_key" to key))
        var lastState = Int.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            try {
                val res = BorrowWebClient.get(pollUrl)
                if (res.code in 200..299) {
                    val json = JSONObject(res.body)
                    val topCode = json.optInt("code", -1)
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val state = if (data.has("code")) data.optInt("code", topCode) else topCode
                    if (state != lastState) {
                        lastState = state
                        when (state) {
                            86101 -> binding.tvStatus.text = "等待扫码..."
                            86090 -> binding.tvStatus.text = "已扫码，请在手机端确认"
                            86038 -> {
                                binding.tvStatus.text = "二维码已失效，请点击“扫码登录小号”刷新"
                                binding.btnQr.isEnabled = true
                                return
                            }

                            0 -> {
                                binding.tvStatus.text = "登录成功，正在填入凭证..."
                                finishWebLogin(res.setCookies, data)
                                binding.btnQr.isEnabled = true
                                return
                            }

                            else ->
                                binding.tvStatus.text =
                                    if (topCode != 0) {
                                        "轮询失败：${json.optString("message", json.optString("msg", "code=$topCode"))}"
                                    } else {
                                        "等待扫码..."
                                    }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w(TAG, "web qr poll failed", t)
                binding.tvStatus.text = "轮询失败：${t.message ?: t.javaClass.simpleName}"
            }
            delay(2000)
        }
    }

    private suspend fun finishWebLogin(
        setCookies: List<String>,
        data: JSONObject,
    ) {
        var sessData = BorrowWebClient.extractCookie(setCookies, "SESSDATA")
        var biliJct = BorrowWebClient.extractCookie(setCookies, "bili_jct")
        val redirectUrl = data.optString("url", "")
        if (sessData.isNullOrBlank()) sessData = rawQueryParam(redirectUrl, "SESSDATA")
        if (biliJct.isNullOrBlank()) biliJct = rawQueryParam(redirectUrl, "bili_jct")
        val refreshToken = data.optString("refresh_token", "").trim()

        if (sessData.isNullOrBlank()) {
            binding.tvStatus.text = "登录成功，但未获取到 SESSDATA（可手动填写下方 Cookie）"
            return
        }
        binding.etSessdata.setText(sessData)
        binding.etBiliJct.setText(biliJct.orEmpty())
        binding.etAcTime.setText(refreshToken)
        applyCredentials(
            sessData = sessData,
            biliJct = biliJct.orEmpty(),
            refreshToken = refreshToken,
            source = "web_qr",
        )
    }

    private suspend fun applyCredentials(
        sessData: String,
        biliJct: String,
        refreshToken: String,
        source: String,
    ) {
        val saved = BorrowAccountStore.saveCredentials(sessData = sessData, biliJct = biliJct, refreshToken = refreshToken)
        if (saved.isFailure) {
            val message = saved.exceptionOrNull()?.message ?: "保存凭证失败"
            binding.tvStatus.text = message
            AppToast.show(this, message)
            return
        }

        val st = BorrowAccountStore.state()
        binding.etSessdata.setText(st.sessData)
        binding.etBiliJct.setText(st.biliJct)
        binding.etAcTime.setText(st.refreshToken)

        val (check, profile) = checkNav(st.authCookieHeader)
        when (check) {
            NavCheck.VALID -> {
                profile?.let { BorrowAccountStore.updateProfile(it.mid, it.name, it.avatarUrl) }
                binding.tvDebug.text = "source=$source mid=${profile?.mid ?: 0L}"
            }

            NavCheck.INVALID -> {
                binding.tvDebug.text = "source=$source nav=invalid"
                AppToast.show(this, "凭证已保存，但校验失败：SESSDATA 可能已失效")
                renderState(statusOverride = "小号凭证已保存（校验失败）")
                return
            }

            NavCheck.UNKNOWN -> {
                binding.tvDebug.text = "source=$source nav=unknown"
            }
        }

        val after = BorrowAccountStore.state()
        val message =
            when {
                !after.autoRefreshEnabled -> "小号已保存，自动续期已关闭"
                after.canAutoRefresh -> "小号登录成功，已启用取流与自动续期"
                else -> "小号已保存；缺少续期凭证，无法自动续期"
            }
        AppToast.show(this, message)
        renderState(
            statusOverride =
                when {
                    !after.autoRefreshEnabled -> "小号已就绪（自动续期已关闭）"
                    after.canAutoRefresh -> "小号已就绪"
                    else -> "小号已保存（无自动续期）"
                },
        )
    }

    private suspend fun checkNav(cookieHeader: String): Pair<NavCheck, NavProfile?> {
        val res =
            try {
                BorrowWebClient.get(NAV_URL, cookieHeader)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w(TAG, "nav check failed", t)
                return NavCheck.UNKNOWN to null
            }
        if (res.code !in 200..299) return NavCheck.UNKNOWN to null
        val json = runCatching { JSONObject(res.body) }.getOrElse { return NavCheck.UNKNOWN to null }
        if (json.optInt("code", -1) != 0) return NavCheck.INVALID to null
        val data = json.optJSONObject("data") ?: return NavCheck.UNKNOWN to null
        if (!data.optBoolean("isLogin", false)) return NavCheck.INVALID to null
        val profile =
            NavProfile(
                mid = data.optLong("mid", 0L),
                name = data.optString("uname", "").trim(),
                avatarUrl = data.optString("face", "").trim().takeIf { it.isNotBlank() },
            )
        return NavCheck.VALID to profile
    }

    private fun targetQrSizePx(): Int {
        val viewSize = min(binding.ivQr.width, binding.ivQr.height)
        val paddingX = binding.ivQr.paddingLeft + binding.ivQr.paddingRight
        val paddingY = binding.ivQr.paddingTop + binding.ivQr.paddingBottom
        val padding = max(paddingX, paddingY)
        val computed = (viewSize - padding).coerceAtLeast(1)
        return if (viewSize > 0) computed.coerceIn(720, 1200) else 900
    }

    private fun makeQr(
        text: String,
        size: Int,
    ): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun rawQueryParam(
        url: String,
        name: String,
    ): String? {
        val marker = "$name="
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        return url.substring(idx + marker.length).substringBefore('&').trim().takeIf { it.isNotBlank() }
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    private data class NavProfile(
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
    )

    private enum class NavCheck {
        VALID,
        INVALID,
        UNKNOWN,
    }

    companion object {
        private const val TAG = "BorrowAccount"
        private const val GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    }
}
