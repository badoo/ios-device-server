package com.badoo.automation.deviceserver.data

data class DesiredCapabilities(
        val udid: String?,
        val model: String?,
        val os: String?,
        val headless: Boolean = true,
        val existing: Boolean = true,
        val arch: String? = null,
        val debug: Boolean = false
)
