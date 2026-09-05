package com.localair.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayAdvertisementTest {
    @Test fun createsStableAirPlayAndRaopIdentity() {
        val record = AirPlayAdvertisement.create("70:20:84:2D:71:B0", "客厅电视 AirPlay")
        assertEquals("airplay44-2d71b0", record.hostName)
        assertEquals("7020842D71B0@客厅电视 AirPlay", record.raopName)
        assertEquals("70:20:84:2D:71:B0", record.airplayTxt["deviceid"])
    }

    @Test fun publishesRequiredReceiverCapabilities() {
        val record = AirPlayAdvertisement.create("02:44:41:50:34:34", "TV")
        assertEquals("0x5A7FFFF7,0x1E", record.airplayTxt["features"])
        assertEquals("AppleTV5,3", record.airplayTxt["model"])
        assertEquals("0,3,5", record.raopTxt["et"])
        assertEquals("UDP", record.raopTxt["tp"])
        assertTrue(record.raopTxt.containsKey("cn"))
        assertEquals(record.airplayTxt["pk"], record.raopTxt["pk"])
    }
}
