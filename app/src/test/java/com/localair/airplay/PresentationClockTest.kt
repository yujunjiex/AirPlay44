package com.localair.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationClockTest {
    @Test fun preservesFrameSpacingOnMonotonicClock() {
        val clock = PresentationClock(startupDelayUs = 10_000, maxLeadUs = 100_000)
        val first = clock.targetTimeNs(1_000_000, 5_000_000_000)
        val second = clock.targetTimeNs(1_016_667, 5_005_000_000)
        assertEquals(5_010_000_000, first)
        assertEquals(16_667_000, second - first)
    }

    @Test fun reanchorsAfterDecoderFallsFarBehind() {
        val clock = PresentationClock(startupDelayUs = 0, maxLateUs = 80_000)
        clock.targetTimeNs(1_000_000, 1_000_000_000)
        val reanchored = clock.targetTimeNs(1_016_667, 1_200_000_000)
        assertEquals(1_200_000_000, reanchored)
    }

    @Test fun reanchorsAfterTimestampDiscontinuity() {
        val clock = PresentationClock(startupDelayUs = 0, maxTimestampJumpUs = 1_000_000)
        clock.targetTimeNs(1_000_000, 1_000_000_000)
        val reanchored = clock.targetTimeNs(3_000_001, 1_010_000_000)
        assertEquals(1_010_000_000, reanchored)
    }

    @Test fun capsFutureBufferingToLowLatencyWindow() {
        val clock = PresentationClock(startupDelayUs = 0, maxLeadUs = 20_000)
        clock.targetTimeNs(1_000_000, 1_000_000_000)
        val reanchored = clock.targetTimeNs(1_100_000, 1_005_000_000)
        assertEquals(1_005_000_000, reanchored)
    }
}
