package com.localair.airplay

internal data class PixelSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width in 1..8192 && height in 1..8192
}

internal data class StreamGeometry(
    val display: PixelSize,
    val encoded: PixelSize,
)

internal object VideoGeometry {
    private val fallback = PixelSize(1920, 1080)

    fun fromAirPlay(
        sourceWidth: Int,
        sourceHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): StreamGeometry {
        val source = PixelSize(sourceWidth, sourceHeight).takeIf { it.isValid }
        val video = PixelSize(videoWidth, videoHeight).takeIf { it.isValid }
        return StreamGeometry(
            display = source ?: video ?: fallback,
            encoded = video ?: source ?: fallback,
        )
    }

    fun fitInside(container: PixelSize, content: PixelSize): PixelSize {
        if (!container.isValid || !content.isValid) return container
        val widthLimitedHeight = container.width.toLong() * content.height / content.width
        return if (widthLimitedHeight <= container.height) {
            PixelSize(container.width, widthLimitedHeight.toInt().coerceAtLeast(1))
        } else {
            val heightLimitedWidth = container.height.toLong() * content.width / content.height
            PixelSize(heightLimitedWidth.toInt().coerceAtLeast(1), container.height)
        }
    }
}
