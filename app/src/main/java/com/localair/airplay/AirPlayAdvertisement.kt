package com.localair.airplay

import java.util.Locale

internal data class AirPlayAdvertisement(
    val hostName: String,
    val raopName: String,
    val airplayTxt: Map<String, String>,
    val raopTxt: Map<String, String>
) {
    companion object {
        const val PUBLIC_KEY = "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7"

        fun create(deviceId: String, displayName: String): AirPlayAdvertisement {
            val compactId = deviceId.replace(":", "").toUpperCase(Locale.US)
            return AirPlayAdvertisement(
                hostName = "airplay44-" + compactId.takeLast(6).toLowerCase(Locale.US),
                raopName = "$compactId@$displayName",
                airplayTxt = linkedMapOf(
                    "deviceid" to deviceId,
                    "features" to "0x5A7FFFF7,0x1E",
                    "srcvers" to "377.25.06",
                    "model" to "AppleTV5,3",
                    "flags" to "0x4",
                    "vv" to "2",
                    "pk" to PUBLIC_KEY
                ),
                raopTxt = linkedMapOf(
                    "cn" to "0,1,2,3",
                    "da" to "true",
                    "et" to "0,3,5",
                    "ft" to "0x5A7FFFF7,0x1E",
                    "sf" to "0x4",
                    "md" to "0,1,2",
                    "am" to "AppleTV5,3",
                    "pk" to PUBLIC_KEY,
                    "tp" to "UDP",
                    "vn" to "65537",
                    "vs" to "377.25.06",
                    "vv" to "2"
                )
            )
        }
    }
}
