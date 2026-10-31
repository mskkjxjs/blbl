package blbl.cat3399.feature.player

import androidx.core.content.ContextCompat
import blbl.cat3399.R
import java.util.Locale
import kotlin.math.abs

internal fun PlayerActivity.quickSpeedSubtitle(): String {
    val holdText = holdSeekSpeedText(holdSeekSpeed())
    return if (quickSpeedActive) "已开启 · ${holdText}x" else "已关闭（长按 ${holdText}x）"
}

internal fun PlayerActivity.toggleQuickSpeed(showHint: Boolean = true) {
    setQuickSpeedEnabled(!quickSpeedActive, showHint)
}

internal fun PlayerActivity.setQuickSpeedEnabled(
    enabled: Boolean,
    showHint: Boolean = true,
) {
    if (enabled == quickSpeedActive) return
    val target: Float
    if (enabled) {
        val current = session.playbackSpeed.takeIf { it.isFinite() }?.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX) ?: 1.0f
        if (abs(current - holdSeekSpeed()) > 0.001f) quickSpeedRestoreSpeed = current
        target = holdSeekSpeed()
    } else {
        target =
            quickSpeedRestoreSpeed
                .takeIf { it.isFinite() }
                ?.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
                ?: 1.0f
    }
    applySessionSettingValue(
        value = target,
        updateSession = { copy(playbackSpeed = it) },
        afterApplied = { speed ->
            quickSpeedActive = enabled
            player?.setPlaybackSpeed(speed)
            if (showHint) {
                showSeekHint(
                    if (enabled) {
                        "快速倍速 ${holdSeekSpeedText(speed)}x"
                    } else {
                        "恢复 ${String.format(Locale.US, "%.2fx", speed)}"
                    },
                    hold = false,
                )
            }
            updateQuickSpeedUi()
        },
    )
}

/** Picking a speed manually always overrides the quick-speed toggle. */
internal fun PlayerActivity.applyManualPlaybackSpeed(speed: Float) {
    if (quickSpeedActive) {
        quickSpeedActive = false
        updateQuickSpeedUi()
    }
    player?.setPlaybackSpeed(speed)
}

internal fun PlayerActivity.updateQuickSpeedButtonUi() {
    binding.btnQuickSpeed.imageTintList =
        ContextCompat.getColorStateList(
            this,
            if (quickSpeedActive) R.color.blbl_blue else R.color.player_button_tint,
        )
    binding.btnQuickSpeed.contentDescription =
        getString(if (quickSpeedActive) R.string.player_quick_speed_turn_off else R.string.player_quick_speed_turn_on)
}

internal fun PlayerActivity.updateQuickSpeedUi() {
    updateQuickSpeedButtonUi()
    touchController?.refreshQuickSpeedButton()
}

private const val PLAYBACK_SPEED_MIN = 0.25f
private const val PLAYBACK_SPEED_MAX = 4.0f
