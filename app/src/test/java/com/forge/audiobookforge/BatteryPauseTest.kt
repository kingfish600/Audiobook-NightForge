package com.forge.audiobookforge

import com.forge.audiobookforge.conversion.LOW_BATTERY_PAUSE_PERCENT
import com.forge.audiobookforge.conversion.shouldPauseForLowBattery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long render on battery must pause before it flattens the phone — but only when the
 * level is actually known to be low. An unreadable level (-1) pausing a render would stop
 * work for no reason and look like a crash, so it is pinned here.
 */
class BatteryPauseTest {

    @Test
    fun pausesAtOrBelowTheThreshold() {
        assertTrue(shouldPauseForLowBattery(0))
        assertTrue(shouldPauseForLowBattery(1))
        assertTrue(shouldPauseForLowBattery(19))
        assertTrue(shouldPauseForLowBattery(LOW_BATTERY_PAUSE_PERCENT))
    }

    @Test
    fun doesNotPauseWhenThereIsHeadroom() {
        assertFalse(shouldPauseForLowBattery(LOW_BATTERY_PAUSE_PERCENT + 1))
        assertFalse(shouldPauseForLowBattery(50))
        assertFalse(shouldPauseForLowBattery(100))
    }

    @Test
    fun anUnknownLevelNeverPauses() {
        assertFalse("unreadable battery must not stop a render", shouldPauseForLowBattery(-1))
        assertFalse(shouldPauseForLowBattery(-42))
    }

    @Test
    fun theThresholdIsSensible() {
        assertTrue("too high would pause healthy renders", LOW_BATTERY_PAUSE_PERCENT in 10..30)
    }
}
