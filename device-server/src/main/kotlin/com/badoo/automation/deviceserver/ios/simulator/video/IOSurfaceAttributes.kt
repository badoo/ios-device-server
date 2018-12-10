package com.badoo.automation.deviceserver.ios.simulator.video

data class IOSurfaceAttributes(
    val bytesPerRow: Int,
    val size: Int,
    val bytesPerElement: Int,
    val pixelFormat: String
) {
    val width = bytesPerRow / bytesPerElement
    val height = size / bytesPerRow

    companion object {
        fun fromFbSimctlLog(input: String): IOSurfaceAttributes {
            val rowSize = intValueFor("\"row_size\"", input)
            val frameSize = intValueFor("\"frame_size\"", input)
            val pixelFormat = stringValueFor("format", input)

            if (rowSize == null || frameSize == null || pixelFormat == null) {
                throw RuntimeException(
                    "Cannot parse IOSurface attributes from [$input]." +
                            "row size: $rowSize, frame size: $frameSize, pixel format $pixelFormat"
                )
            }

            if (!pixelFormat.contains("BGRA")) {
                throw RuntimeException("Unsupported format $pixelFormat. Only BGRA is supported.")
            }

            val bytesPerElement = 4 // default for BGRA

            return IOSurfaceAttributes(
                bytesPerRow = rowSize,
                size = frameSize,
                bytesPerElement = bytesPerElement,
                pixelFormat = pixelFormat
            )
        }

        private fun intValueFor(key: String, input: String): Int? {
            val pattern = """^\s*${Regex.escape(key)}\s*=\s(\d+);$""".toRegex(RegexOption.MULTILINE)

            return pattern.find(input)?.groupValues?.get(1)?.toInt()
        }

        private fun stringValueFor(key: String, input: String): String? {
            val pattern = """^\s*${Regex.escape(key)}\s*=\s(.+);$""".toRegex(RegexOption.MULTILINE)

            return pattern.find(input)?.groupValues?.get(1)
        }
    }
}