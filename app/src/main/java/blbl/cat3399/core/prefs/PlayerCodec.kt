package blbl.cat3399.core.prefs

import java.util.Locale

object PlayerCodec {
    const val AUTO = "AUTO"
    const val AVC = "AVC"
    const val HEVC = "HEVC"
    const val AV1 = "AV1"

    val selectable: List<String> = listOf(AUTO, AVC, HEVC, AV1)

    private const val CODEC_ID_AVC = 7
    private const val CODEC_ID_HEVC = 12
    private const val CODEC_ID_AV1 = 13

    fun normalize(value: String?): String =
        when (value?.trim()?.uppercase(Locale.US)) {
            AVC -> AVC
            HEVC -> HEVC
            AV1 -> AV1
            else -> AUTO
        }

    fun label(value: String): String =
        when (normalize(value)) {
            AVC -> AVC
            HEVC -> HEVC
            AV1 -> AV1
            else -> "自动"
        }

    fun fromLabel(label: String): String = selectable.firstOrNull { this.label(it) == label.trim() } ?: AUTO

    fun codecid(value: String?): Int? =
        when (normalize(value)) {
            AVC -> CODEC_ID_AVC
            HEVC -> CODEC_ID_HEVC
            AV1 -> CODEC_ID_AV1
            else -> null
        }

    /** Higher is better; AUTO follows AV1 > HEVC > AVC. */
    fun rank(codecid: Int, preferred: String): Int {
        val preferredId = codecid(preferred)
        if (preferredId != null && codecid == preferredId) return 3
        return when (codecid) {
            CODEC_ID_AV1 -> 2
            CODEC_ID_HEVC -> 1
            CODEC_ID_AVC -> 0
            else -> -1
        }
    }
}
