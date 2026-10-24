package blbl.cat3399.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDoubleTapPolicyTest {
    @Test
    fun centerDoubleTapAlwaysTogglesPlayPause() {
        assertTrue(shouldTogglePlayPauseOnDoubleTap(direction = 0, pauseOnEdgeDoubleTap = false))
        assertTrue(shouldTogglePlayPauseOnDoubleTap(direction = 0, pauseOnEdgeDoubleTap = true))
    }

    @Test
    fun edgeDoubleTapSeeksByDefault() {
        assertFalse(shouldTogglePlayPauseOnDoubleTap(direction = -1, pauseOnEdgeDoubleTap = false))
        assertFalse(shouldTogglePlayPauseOnDoubleTap(direction = 1, pauseOnEdgeDoubleTap = false))
    }

    @Test
    fun edgeDoubleTapTogglesPlayPauseWhenEnabled() {
        assertTrue(shouldTogglePlayPauseOnDoubleTap(direction = -1, pauseOnEdgeDoubleTap = true))
        assertTrue(shouldTogglePlayPauseOnDoubleTap(direction = 1, pauseOnEdgeDoubleTap = true))
    }
}
