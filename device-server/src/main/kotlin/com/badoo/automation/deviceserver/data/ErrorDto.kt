package com.badoo.automation.deviceserver.data

data class ErrorDto(
        val type: String,
        val message: String?,
        val stackTrace: List<String>
)

fun Throwable.toDto(): ErrorDto {
    return ErrorDto(
            type = this.javaClass.name,
            message = this.message ?: "",
            stackTrace = this.stackTrace
                .takeWhile {
                    !it.className.startsWith("io.ktor")
                }
                .map { it.toString() }
    )
}
