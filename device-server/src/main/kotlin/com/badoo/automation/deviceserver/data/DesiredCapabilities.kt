package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class DesiredCapabilities(
        val udid: String?,
        val model: String?,
        val os: String?,
        val headless: Boolean = true,
        val existing: Boolean = true,
        val arch: String? = null,

        @JsonProperty("use_wda")
        val useWda: Boolean = true
)
