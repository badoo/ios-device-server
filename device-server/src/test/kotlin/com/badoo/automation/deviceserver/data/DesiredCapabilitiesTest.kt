package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Test
import kotlin.test.assertEquals


class DesiredCapabilitiesTest {
    private fun fromJson(json: String): DesiredCapabilities {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesEmptyCapabilities() {
        val json = "{}"
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, null, false, true), actual)
    }

    @Test
    fun fromJsonParsesModelAndVersionCapabilities() {
        val json = """{"model":"iPhone 6", "os": "iOS 11.0"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, "iPhone 6", "iOS 11.0", null, false, true), actual)
    }

    @Test
    fun fromJsonParsesUdidCapability() {
        val udid = "CD391B89-64C6-4106-BE37-EC1956956D28"
        val json = """{"udid":"$udid"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(udid, null, null, null, false, true), actual)
    }

    @Test
    fun fromJsonParsesUseWdaFalseCapability() {
        val json = """{"use_wda": false}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, useWda = false), actual)
    }

    @Test
    fun fromJsonParsesUseWdaBoolAsTextCapability() {
        val json = """{"use_wda": "false"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, useWda = false), actual)
    }
}
