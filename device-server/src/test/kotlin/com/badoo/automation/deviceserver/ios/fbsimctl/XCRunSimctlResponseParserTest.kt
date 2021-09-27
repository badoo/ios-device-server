package com.badoo.automation.deviceserver.ios.fbsimctl

import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

class XCRunSimctlResponseParserTest {
    private val simulatorsRuntimesString = """
        {
          "runtimes" : [
            {
              "bundlePath" : "\/Applications\/Xcode_13_beta_4.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime",
              "buildversion" : "19A5307d",
              "runtimeRoot" : "\/Applications\/Xcode_13_beta_4.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot",
              "identifier" : "com.apple.CoreSimulator.SimRuntime.iOS-15-0",
              "version" : "15.0",
              "isAvailable" : true,
              "supportedDeviceTypes" : [
                {
                  "bundlePath" : "\/Applications\/Xcode_13_beta_4.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/DeviceTypes\/iPhone 12.simdevicetype",
                  "name" : "iPhone 12",
                  "identifier" : "com.apple.CoreSimulator.SimDeviceType.iPhone-12",
                  "productFamily" : "iPhone"
                }
              ],
              "name" : "iOS 15.0"
            }
          ]
        }
    """.trimIndent()


    @Test @Ignore
    fun parseRuntimes() {
    }
}
