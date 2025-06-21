package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class FileDto(
    @JsonProperty("file_name")
    val fileName: String,

    @JsonProperty("data")
    val data: ByteArray,

    @JsonProperty("bundle_id")
    val bundleId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileDto

        if (fileName != other.fileName) return false
        if (!data.contentEquals(other.data)) return false
        if (bundleId != other.bundleId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (bundleId?.hashCode() ?: 0)
        return result
    }
}
