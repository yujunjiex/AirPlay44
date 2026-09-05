package com.localair.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test fun usesSourceAspectAndEncodedDecoderSize() {
        val geometry = VideoGeometry.fromAirPlay(2532, 1170, 1920, 886)
        assertEquals(PixelSize(2532, 1170), geometry.display)
        assertEquals(PixelSize(1920, 886), geometry.encoded)
    }

    @Test fun fitsWidePhoneInsideSixteenByNineTvWithoutStretching() {
        assertEquals(
            PixelSize(1920, 887),
            VideoGeometry.fitInside(PixelSize(1920, 1080), PixelSize(2532, 1170)),
        )
    }

    @Test fun fillsLandscapeTvByCroppingSidesWithoutStretching() {
        assertEquals(
            PixelSize(2337, 1080),
            VideoGeometry.fitForTv(PixelSize(1920, 1080), PixelSize(2532, 1170)),
        )
    }

    @Test fun fitsPortraitPhoneInsideTvWithoutStretching() {
        assertEquals(
            PixelSize(498, 1080),
            VideoGeometry.fitForTv(PixelSize(1920, 1080), PixelSize(1179, 2556)),
        )
    }

    @Test fun sixteenByNineLandscapeNeedsNoCrop() {
        assertEquals(
            PixelSize(1920, 1080),
            VideoGeometry.fitForTv(PixelSize(1920, 1080), PixelSize(1920, 1080)),
        )
    }

    @Test fun fallsBackToLegacyDecoderSizeWhenMetadataIsMissing() {
        val geometry = VideoGeometry.fromAirPlay(0, 0, 0, 0)
        assertEquals(PixelSize(1920, 1080), geometry.display)
        assertEquals(PixelSize(1920, 1080), geometry.encoded)
    }
}
