package org.mozilla.fenix.upgrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgridVideoPreparationTest {
    @Test fun `three seconds of startup is not a playback failure`() {
        val deadline = VideoPreparationDeadline().apply { reset(100) }
        assertFalse(deadline.expired(3_100, 0))
        assertFalse(deadline.expired(9_100, 0))
        assertTrue(deadline.expired(10_100, 0))
    }
    @Test fun `buffering progress extends only the stall deadline`() {
        val deadline = VideoPreparationDeadline().apply { reset(100) }
        assertFalse(deadline.expired(8_100, 100))
        assertFalse(deadline.expired(16_100, 200))
        assertFalse(deadline.expired(19_100, 300))
        assertTrue(deadline.expired(20_100, 400))
    }
    @Test fun `stalled buffer cannot refresh its own deadline`() {
        val deadline = VideoPreparationDeadline().apply { reset(100) }
        assertFalse(deadline.expired(1_100, 100))
        assertFalse(deadline.expired(10_100, 100))
        assertTrue(deadline.expired(11_100, 100))
    }
    @Test fun `retry starts with an independent deadline and buffer`() {
        val deadline = VideoPreparationDeadline().apply { reset(100) }
        assertTrue(deadline.expired(21_100, 500))
        deadline.reset(21_100)
        assertFalse(deadline.expired(29_100, 100))
        assertFalse(deadline.expired(31_100, 100))
        assertTrue(deadline.expired(39_100, 100))
    }
}
