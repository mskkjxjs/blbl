package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun commentsPanelSingleTapDoesNotClosePanel() {
        assertFalse(shouldCloseSidePanelOnSingleTap(isSidePanelVisible = true, isCommentsPanelVisible = true))
    }

    @Test
    fun settingsPanelSingleTapStillClosesPanel() {
        assertTrue(shouldCloseSidePanelOnSingleTap(isSidePanelVisible = true, isCommentsPanelVisible = false))
    }

    @Test
    fun commentsPanelDoesNotBlockEnabledGestures() {
        assertFalse(
            shouldBlockGestureForSidePanel(
                isSidePanelVisible = true,
                isCommentsPanelVisible = true,
                commentsGestureEnabled = true,
            ),
        )
    }

    @Test
    fun commentsPanelBlocksDisabledGestures() {
        assertTrue(
            shouldBlockGestureForSidePanel(
                isSidePanelVisible = true,
                isCommentsPanelVisible = true,
                commentsGestureEnabled = false,
            ),
        )
    }

    @Test
    fun settingsPanelBlocksGestures() {
        assertTrue(
            shouldBlockGestureForSidePanel(
                isSidePanelVisible = true,
                isCommentsPanelVisible = false,
                commentsGestureEnabled = true,
            ),
        )
    }

    @Test
    fun noSidePanelDoesNotBlockGestures() {
        assertFalse(
            shouldBlockGestureForSidePanel(
                isSidePanelVisible = false,
                isCommentsPanelVisible = false,
                commentsGestureEnabled = false,
            ),
        )
    }

    @Test
    fun leftEdgeLongPressBoostRequiresOptIn() {
        assertFalse(
            shouldStartEdgeLongPressBoost(x = 50f, width = 1_000f, edgeThreshold = 0.1f, leftEdgeEnabled = false),
        )
        assertTrue(
            shouldStartEdgeLongPressBoost(x = 50f, width = 1_000f, edgeThreshold = 0.1f, leftEdgeEnabled = true),
        )
    }

    @Test
    fun rightEdgeLongPressBoostRemainsAvailable() {
        assertTrue(
            shouldStartEdgeLongPressBoost(x = 950f, width = 1_000f, edgeThreshold = 0.1f, leftEdgeEnabled = false),
        )
    }

    @Test
    fun centerLongPressDoesNotStartBoost() {
        assertFalse(
            shouldStartEdgeLongPressBoost(x = 500f, width = 1_000f, edgeThreshold = 0.1f, leftEdgeEnabled = true),
        )
    }

    @Test
    fun verticalSwipeUsesIndependentSideActions() {
        assertEquals(
            "none",
            playerVerticalSwipeActionForPosition(
                x = 50f,
                width = 1_000f,
                sideThreshold = 0.2f,
                leftAction = "none",
                rightAction = "volume",
            ),
        )
        assertEquals(
            "volume",
            playerVerticalSwipeActionForPosition(
                x = 950f,
                width = 1_000f,
                sideThreshold = 0.2f,
                leftAction = "none",
                rightAction = "volume",
            ),
        )
    }

    @Test
    fun verticalSwipeCenterHasNoSideAction() {
        assertNull(
            playerVerticalSwipeActionForPosition(
                x = 500f,
                width = 1_000f,
                sideThreshold = 0.2f,
                leftAction = "brightness",
                rightAction = "volume",
            ),
        )
    }
}
