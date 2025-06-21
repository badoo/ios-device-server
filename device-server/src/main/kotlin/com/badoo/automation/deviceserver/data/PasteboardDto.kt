package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class PasteboardDto(
    @JsonProperty("pasteboard_content")
    val pasteboardContent: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PasteboardDto

        return pasteboardContent.contentEquals(other.pasteboardContent)
    }

    override fun hashCode(): Int {
        return pasteboardContent.contentHashCode()
    }
}
