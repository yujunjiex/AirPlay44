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
            // The Surface contains the encoded/cropped video, so its dimensions
            // are authoritative for display aspect ratio. iOS may briefly keep
            // the source dimensions in the previous orientation while entering
            // or leaving full-screen video.
            display = video ?: source ?: fallback,
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

    fun fillInside(container: PixelSize, content: PixelSize): PixelSize {
        if (!container.isValid || !content.isValid) return container
        val heightLimitedWidth = container.height.toLong() * content.width / content.height
        return if (heightLimitedWidth >= container.width) {
            PixelSize(heightLimitedWidth.toInt().coerceAtLeast(1), container.height)
        } else {
            val widthLimitedHeight = container.width.toLong() * content.height / content.width
            PixelSize(container.width, widthLimitedHeight.toInt().coerceAtLeast(1))
        }
    }

    /** Landscape mirrors fill the TV without distortion; portrait mirrors remain fully visible. */
    fun fitForTv(container: PixelSize, content: PixelSize): PixelSize =
        if (content.width >= content.height) fillInside(container, content) else fitInside(container, content)
}
