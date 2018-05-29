package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class DesiredCapabilities(
        @JsonProperty("udid")
        val udid: String?,

        @JsonProperty("model")
        val model: String?,

        @JsonProperty("os")
        val os: String?,

        @JsonProperty("headless")
        val headless: Boolean = true,

        @JsonProperty("existing")
        val existing: Boolean = true,

        @JsonProperty("arch")
        val arch: String? = null,

        @JsonProperty("wda_host_app")
        val wdaHostApp: String = "com.apple.Preferences"
) {
        init {
            require(wdaHostApp.isNotBlank(), { "'wda_host_app' cannot be empty" })
        }
}
