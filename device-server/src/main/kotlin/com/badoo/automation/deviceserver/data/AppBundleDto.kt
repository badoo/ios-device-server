package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class AppBundleDto(
    @JsonProperty("app_url")
    val appUrl: String,

    @JsonProperty("bundle_id")
    val bundleId: String,

    @JsonProperty("dsym_url")
    val dsymUrl: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppBundleDto

        if (appUrl != other.appUrl) return false
        if (bundleId != other.bundleId) return false

        return true
    }

    override fun hashCode(): Int {
        return appUrl.hashCode()
    }
}

data class AppBundleDeployDto(
    @JsonProperty("app_url")
    val appUrl: String,

    @JsonProperty("dsym_url")
    val dsymUrl: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppBundleDeployDto

        return appUrl == other.appUrl
    }

    override fun hashCode(): Int {
        return appUrl.hashCode()
    }
}
