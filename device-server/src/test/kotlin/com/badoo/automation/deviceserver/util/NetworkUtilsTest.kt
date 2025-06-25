package com.badoo.automation.deviceserver.util

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun testGetAddresses() {
        val addresses = NetworkUtils.getAddresses()
        assertTrue(addresses.isNotEmpty())
    }
}
