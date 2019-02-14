package com.badoo.automation.deviceserver.host.management

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesiredCapabilitiesMatcherTest {
    @Test
    fun isRuntimeMatchShouldMatchExact() {
        val matcher = DesiredCapabilitiesMatcher()

        assertTrue {
            matcher.isRuntimeMatch("iOS 11", "iOS 11.2")
        }
    }

    @Test
    fun isRuntimeMatchShouldMatchPartial() {
        val matcher = DesiredCapabilitiesMatcher()

        assertTrue {
            matcher.isRuntimeMatch("iOS 11", "iOS 11.2")
        }
    }

    @Test
    fun isRuntimeMatchShouldNotMatchDifferentNames() {
        val matcher = DesiredCapabilitiesMatcher()

        assertFalse {
            matcher.isRuntimeMatch("tvOS 11.2", "iOS 11.2")
        }
    }

    @Test
    fun isRuntimeMatchShouldNotMatchDifferentVersions() {
        val matcher = DesiredCapabilitiesMatcher()

        assertFalse {
            matcher.isRuntimeMatch("iOS 11.3", "iOS 11.2")
        }
    }
}