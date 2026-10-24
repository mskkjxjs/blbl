package blbl.cat3399.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOsdVisibilityPolicyTest {
    @Test
    fun requestedOsdShowsWithoutOverlayOrTouchLock() {
        assertTrue(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = false,
                touchLocked = false,
            ),
        )
    }

    @Test
    fun overlayPanelSuppressesRequestedOsd() {
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = true,
                touchLocked = false,
            ),
        )
    }

    @Test
    fun hiddenRequestAndTouchLockKeepOsdHidden() {
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = false,
                hasOverlayPanel = false,
                touchLocked = false,
            ),
        )
        assertFalse(
            shouldShowPlayerOsd(
                requestedVisible = true,
                hasOverlayPanel = false,
                touchLocked = true,
            ),
        )
    }

    @Test
    fun commentsTouchSeekShowsPersistentProgressEvenWhenPreferenceDisabled() {
        assertTrue(
            shouldShowPersistentBottomProgress(
                persistentEnabled = false,
                showControls = true,
                transientSeekOsdVisible = false,
                commentsTouchSeekVisible = true,
            ),
        )
    }

    @Test
    fun regularPersistentProgressKeepsExistingVisibilityRules() {
        assertTrue(
            shouldShowPersistentBottomProgress(
                persistentEnabled = true,
                showControls = false,
                transientSeekOsdVisible = false,
                commentsTouchSeekVisible = false,
            ),
        )
        assertFalse(
            shouldShowPersistentBottomProgress(
                persistentEnabled = true,
                showControls = true,
                transientSeekOsdVisible = false,
                commentsTouchSeekVisible = false,
            ),
        )
        assertFalse(
            shouldShowPersistentBottomProgress(
                persistentEnabled = true,
                showControls = false,
                transientSeekOsdVisible = true,
                commentsTouchSeekVisible = false,
            ),
        )
    }
}
