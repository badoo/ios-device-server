package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Test
import kotlin.test.assertEquals


class DesiredCapabilitiesTest {
    private val headless = true

    private fun fromJson(json: String): DesiredCapabilities {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesEmptyCapabilities() {
        val json = "{}"
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, headless), actual)
    }

    @Test
    fun fromJsonParsesModelAndVersionCapabilities() {
        val json = """{"model":"iPhone 6", "os": "iOS 11.0"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, "iPhone 6", "iOS 11.0", headless), actual)
    }

    @Test
    fun fromJsonParsesUdidCapability() {
        val udid = "CD391B89-64C6-4106-BE37-EC1956956D28"
        val json = """{"udid":"$udid"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(udid, null, null, headless), actual)
    }

    @Test
    fun fromJsonParsesHeadlessFalseCapability() {
        val json = """{"headless": false}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, false), actual)
    }

    @Test
    fun fromJsonParsesHeadlessBoolAsTextCapability() {
        val json = """{"headless": "false"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, false), actual)
    }

    @Test
    fun fromJsonParsesHeadlessDefaultCapability() {
        val json = """{}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, true, true), actual)
    }

    @Test
    fun fromJsonParsesUseWdaFalseCapability() {
        val json = """{"use_wda": false}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, true, true, useWda = false, useAppium = false), actual)
    }

    @Test
    fun fromJsonParsesUseWdaBoolAsTextCapability() {
        val json = """{"use_wda": "false"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, true, useWda = false, useAppium = false), actual)
    }
}
