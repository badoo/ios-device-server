package com.badoo.automation.deviceserver.ios.fbsimctl

import XCRunSimctl
import org.junit.Assert.assertEquals
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


    @Test
    fun parseDeviceTypes() {
        deviceTypes.forEach {
            assertEquals(it.value, XCRunSimctl.getDeviceModel(it.key))
        }
    }

    companion object {
        val deviceTypes: Map<String, String> = mapOf(
            "iPhone 4s" to "com.apple.CoreSimulator.SimDeviceType.iPhone-4s",
            "iPhone 5" to "com.apple.CoreSimulator.SimDeviceType.iPhone-5",
            "iPhone 5s" to "com.apple.CoreSimulator.SimDeviceType.iPhone-5s",
            "iPhone 6 Plus" to "com.apple.CoreSimulator.SimDeviceType.iPhone-6-Plus",
            "iPhone 6" to "com.apple.CoreSimulator.SimDeviceType.iPhone-6",
            "iPhone 6s" to "com.apple.CoreSimulator.SimDeviceType.iPhone-6s",
            "iPhone 6s Plus" to "com.apple.CoreSimulator.SimDeviceType.iPhone-6s-Plus",
            "iPhone SE (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPhone-SE",
            "iPhone 7" to "com.apple.CoreSimulator.SimDeviceType.iPhone-7",
            "iPhone 7 Plus" to "com.apple.CoreSimulator.SimDeviceType.iPhone-7-Plus",
            "iPhone 8" to "com.apple.CoreSimulator.SimDeviceType.iPhone-8",
            "iPhone 8 Plus" to "com.apple.CoreSimulator.SimDeviceType.iPhone-8-Plus",
            "iPhone X" to "com.apple.CoreSimulator.SimDeviceType.iPhone-X",
            "iPhone Xs" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XS",
            "iPhone Xs Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XS-Max",
            "iPhone Xʀ" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XR",
            "iPhone XR" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XR",
            "iPhone 11" to "com.apple.CoreSimulator.SimDeviceType.iPhone-11",
            "iPhone 11 Pro" to "com.apple.CoreSimulator.SimDeviceType.iPhone-11-Pro",
            "iPhone 11 Pro Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-11-Pro-Max",
            "iPhone SE (2nd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPhone-SE--2nd-generation-",
            "iPhone 12 mini" to "com.apple.CoreSimulator.SimDeviceType.iPhone-12-mini",
            "iPhone 12" to "com.apple.CoreSimulator.SimDeviceType.iPhone-12",
            "iPhone 12 Pro" to "com.apple.CoreSimulator.SimDeviceType.iPhone-12-Pro",
            "iPhone 12 Pro Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-12-Pro-Max",
            "iPhone 13 Pro" to "com.apple.CoreSimulator.SimDeviceType.iPhone-13-Pro",
            "iPhone 13 Pro Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-13-Pro-Max",
            "iPhone 13 mini" to "com.apple.CoreSimulator.SimDeviceType.iPhone-13-mini",
            "iPhone 13" to "com.apple.CoreSimulator.SimDeviceType.iPhone-13",
            "iPod touch (7th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPod-touch--7th-generation-",
            "iPad 2" to "com.apple.CoreSimulator.SimDeviceType.iPad-2",
            "iPad Retina" to "com.apple.CoreSimulator.SimDeviceType.iPad-Retina",
            "iPad Air" to "com.apple.CoreSimulator.SimDeviceType.iPad-Air",
            "iPad mini 2" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini-2",
            "iPad mini 3" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini-3",
            "iPad mini 4" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini-4",
            "iPad Air 2" to "com.apple.CoreSimulator.SimDeviceType.iPad-Air-2",
            "iPad Pro (9.7-inch)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--9-7-inch-",
            "iPad Pro (12.9-inch) (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro",
            "iPad (5th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad--5th-generation-",
            "iPad Pro (12.9-inch) (2nd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--12-9-inch---2nd-generation-",
            "iPad Pro (10.5-inch)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--10-5-inch-",
            "iPad (6th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad--6th-generation-",
            "iPad (7th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad--7th-generation-",
            "iPad Pro (11-inch) (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--11-inch-",
            "iPad Pro (12.9-inch) (3rd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--12-9-inch---3rd-generation-",
            "iPad Pro (11-inch) (2nd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--11-inch---2nd-generation-",
            "iPad Pro (12.9-inch) (4th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--12-9-inch---4th-generation-",
            "iPad mini (5th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini--5th-generation-",
            "iPad Air (3rd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Air--3rd-generation-",
            "iPad (8th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad--8th-generation-",
            "iPad (9th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-9th-generation",
            "iPad Air (4th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Air--4th-generation-",
            "iPad Pro (11-inch) (3rd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-3rd-generation",
            "iPad Pro (12.9-inch) (5th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-12-9-inch-5th-generation",
            "iPad mini (6th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini-6th-generation",
            "Apple Watch - 38mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-38mm",
            "Apple Watch - 42mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-42mm",
            "Apple Watch Series 2 - 38mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-2-38mm",
            "Apple Watch Series 2 - 42mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-2-42mm",
            "Apple Watch Series 3 - 38mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-3-38mm",
            "Apple Watch Series 3 - 42mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-3-42mm",
            "Apple Watch Series 4 - 40mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-4-40mm",
            "Apple Watch Series 4 - 44mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-4-44mm",
            "Apple Watch Series 5 - 40mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-5-40mm",
            "Apple Watch Series 5 - 44mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-5-44mm",
            "Apple Watch SE - 40mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-SE-40mm",
            "Apple Watch SE - 44mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-SE-44mm",
            "Apple Watch Series 6 - 40mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-6-40mm",
            "Apple Watch Series 6 - 44mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-6-44mm",
            "Apple Watch Series 7 - 41mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-7-41mm",
            "Apple Watch Series 7 - 45mm" to "com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-7-45mm"
        )
    }
}
