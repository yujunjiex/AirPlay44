package com.localair.airplay

internal object H264AnnexB {
    data class ParameterSets(val sps: ByteArray?, val pps: ByteArray?)
    data class Inspection(
        val parameterSets: ParameterSets,
        val hasVideoSlice: Boolean,
        val hasIdr: Boolean,
    )

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

    fun inspect(data: ByteArray): Inspection {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var hasVideoSlice = false
        var hasIdr = false
        val starts = startCodes(data)
        for (index in starts.indices) {
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            if (start + 4 >= end) continue
            when (data[start + 4].toInt() and 0x1f) {
                1 -> hasVideoSlice = true
                5 -> {
                    hasVideoSlice = true
                    hasIdr = true
                }
                7 -> sps = data.copyOfRange(start, end)
                8 -> pps = data.copyOfRange(start, end)
            }
        }
        return Inspection(ParameterSets(sps, pps), hasVideoSlice, hasIdr)
    }

    fun parameterSets(data: ByteArray): ParameterSets = inspect(data).parameterSets

    fun containsVideoSlice(data: ByteArray): Boolean = inspect(data).hasVideoSlice

    fun containsIdr(data: ByteArray): Boolean = inspect(data).hasIdr
}
