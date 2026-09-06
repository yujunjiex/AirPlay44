package com.localair.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AnnexBTest {
    private fun nal(type: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1, type.toByte()) + payload.map { it.toByte() }.toByteArray()

    @Test fun findsFourByteStartCodes() {
        val stream = nal(7, 1, 2) + nal(8, 3) + nal(5, 4, 5)
        assertEquals(listOf(0, 7, 13), H264AnnexB.startCodes(stream))
    }

    @Test fun extractsSpsAndPpsFromCombinedAccessUnit() {
        val sps = nal(7, 0x64, 0, 0x1f)
        val pps = nal(8, 0xee, 0x3c)
        val parameters = H264AnnexB.parameterSets(sps + pps + nal(5, 9))
        assertArrayEquals(sps, parameters.sps)
        assertArrayEquals(pps, parameters.pps)
    }

    @Test fun inspectsCombinedAccessUnitInOnePass() {
        val inspection = H264AnnexB.inspect(nal(7, 1) + nal(8, 2) + nal(5, 3))
        assertTrue(inspection.hasVideoSlice)
        assertTrue(inspection.hasIdr)
        assertArrayEquals(nal(7, 1), inspection.parameterSets.sps)
        assertArrayEquals(nal(8, 2), inspection.parameterSets.pps)
    }

    @Test fun reportsIdrAndNonIdrSlicesOnly() {
        assertTrue(H264AnnexB.containsVideoSlice(nal(5, 1)))
        assertTrue(H264AnnexB.containsVideoSlice(nal(1, 1)))
        assertFalse(H264AnnexB.containsVideoSlice(nal(7, 1) + nal(8, 2)))
        assertTrue(H264AnnexB.containsIdr(nal(5, 1)))
        assertFalse(H264AnnexB.containsIdr(nal(1, 1)))
    }

    @Test fun toleratesTruncatedAndNonAnnexBData() {
        assertTrue(H264AnnexB.startCodes(byteArrayOf(0, 0, 0)).isEmpty())
        val parameters = H264AnnexB.parameterSets(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(parameters.sps)
        assertNull(parameters.pps)
    }
}
