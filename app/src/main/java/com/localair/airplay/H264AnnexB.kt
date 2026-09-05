package com.localair.airplay

internal object H264AnnexB {
    data class ParameterSets(val sps: ByteArray?, val pps: ByteArray?)

    fun startCodes(data: ByteArray): List<Int> {
        val result = ArrayList<Int>(4)
        var index = 0
        while (index + 4 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()) {
                result.add(index)
                index += 4
            } else {
                index++
            }
        }
        return result
    }

    fun parameterSets(data: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val starts = startCodes(data)
        for (index in starts.indices) {
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            if (start + 4 >= end) continue
            when (data[start + 4].toInt() and 0x1f) {
                7 -> sps = data.copyOfRange(start, end)
                8 -> pps = data.copyOfRange(start, end)
            }
        }
        return ParameterSets(sps, pps)
    }

    fun containsVideoSlice(data: ByteArray): Boolean = startCodes(data).any { start ->
        val type = data[start + 4].toInt() and 0x1f
        type == 1 || type == 5
    }

    fun containsIdr(data: ByteArray): Boolean = startCodes(data).any { start ->
        data[start + 4].toInt() and 0x1f == 5
    }
}
