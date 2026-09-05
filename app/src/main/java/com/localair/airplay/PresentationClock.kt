package com.localair.airplay

/** Maps AirPlay presentation timestamps onto Android's monotonic clock. */
internal class PresentationClock(
    private val startupDelayUs: Long = 60_000,
    private val maxLateUs: Long = 120_000,
    private val maxTimestampJumpUs: Long = 2_000_000,
) {
    private var basePtsUs = UNSET
    private var baseTimeNs = 0L

    fun reset() {
        basePtsUs = UNSET
        baseTimeNs = 0L
    }

    fun targetTimeNs(ptsUs: Long, nowNs: Long): Long {
        if (ptsUs <= 0) return nowNs
        if (basePtsUs == UNSET) return anchor(ptsUs, nowNs)

        val deltaUs = ptsUs - basePtsUs
        if (deltaUs < -maxLateUs || deltaUs > maxTimestampJumpUs) {
            return anchor(ptsUs, nowNs)
        }

        val targetNs = baseTimeNs + deltaUs * 1_000L
        if (targetNs < nowNs - maxLateUs * 1_000L) {
            return anchor(ptsUs, nowNs)
        }
        return targetNs
    }

    private fun anchor(ptsUs: Long, nowNs: Long): Long {
        basePtsUs = ptsUs
        baseTimeNs = nowNs + startupDelayUs * 1_000L
        return baseTimeNs
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
