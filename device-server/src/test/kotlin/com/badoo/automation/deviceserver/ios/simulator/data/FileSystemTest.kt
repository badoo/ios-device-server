package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.command.ShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.Remote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfoBundle
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FileSystemTest {

    private val udid: UDID = "udid"
//    private val remote: IRemote = Remote("localhost", "vfrolov", "localhost")
    private val remote: IRemote = mockThis()
    private val fbsimctl: FBSimctl = FBSimctl(ShellCommand())

    private val containerPathStub =
        "/Users/qa/Library/Developer/CoreSimulator/Devices/UDID/data/Containers/Data/Application/A2C79BEC-FD2C-4676-BA9B-B6A62AFE193A/"
    private val bundleInfoStub = FBSimctlAppInfo(
        containerPathStub,
        FBSimctlAppInfoBundle(null, "test.bundle", null, null),
        null
    )

    @Before
    fun setUp() {
        whenever(remote.fbsimctl).thenReturn(fbsimctl)
    }

    @Test
    fun shouldCreateDataContainer() {
        whenever(fbsimctl.listApps(udid)).thenReturn(listOf(bundleInfoStub))

        val container = FileSystem(remote, udid).dataContainer("test.bundle")

        assertEquals(Paths.get(containerPathStub), container.basePath)
    }

    @Test
    fun shouldCreateDataContainer2() {
        val z = fbsimctl.listApps("B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D")
        println(z)
        // {"event_name":"list_apps","timestamp":1573120273,"subject":[{"data_container":null,"bundle":{"path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSlideShow.app","bundle_id":"com.apple.mobileslideshow","binary":{"name":"MobileSlideShow","path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSlideShow.app\/MobileSlideShow","architectures":["x86_64"]},"name":"MobileSlideShow"},"install_type":"system"},{"data_container":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Data\/Application\/12E8AC34-13E7-4017-A85F-B0EA71CDBF92","bundle":{"path":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Bundle\/Application\/41D504A2-EC10-4379-8544-19D64B3A03FF\/Badoo.app","bundle_id":"com.badoo.Badoo.dev","binary":{"name":"Badoo","path":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Bundle\/Application\/41D504A2-EC10-4379-8544-19D64B3A03FF\/Badoo.app\/Badoo","architectures":["x86_64"]},"name":"Badoo"},"install_type":"user"},{"data_container":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Data\/Application\/43578DEF-91C0-473D-B330-4641DD248819","bundle":{"path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/Maps.app","bundle_id":"com.apple.Maps","binary":{"name":"Maps","path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/Maps.app\/Maps","architectures":["x86_64"]},"name":"Maps"},"install_type":"system"},{"data_container":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Data\/Application\/167CFFEE-B532-4203-9150-18E3A20D4AF4","bundle":{"path":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Bundle\/Application\/9CDA04BF-0469-4B5F-87AE-19A148AB89CB\/WebDriverAgentRunner-Runner.app","bundle_id":"com.facebook.WebDriverAgentRunner.dev2.xctrunner","binary":{"name":"WebDriverAgentRunner-Runner","path":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Bundle\/Application\/9CDA04BF-0469-4B5F-87AE-19A148AB89CB\/WebDriverAgentRunner-Runner.app\/WebDriverAgentRunner-Runner","architectures":["x86_64","i386"]},"name":"WebDriverAgentRunner-Runner"},"install_type":"user"},{"data_container":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Data\/Application\/83C1F809-5D21-4BE5-873C-96A2941F9166","bundle":{"path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSMS.app","bundle_id":"com.apple.MobileSMS","binary":{"name":"MobileSMS","path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSMS.app\/MobileSMS","architectures":["x86_64"]},"name":"MobileSMS"},"install_type":"system"},{"data_container":"\/Users\/vfrolov\/Library\/Developer\/CoreSimulator\/Devices\/B893EDAB-0CA0-4F72-8F1D-85D12A8BE78D\/data\/Containers\/Data\/Application\/6655EDCA-AAD0-483A-9B9B-472AB4437313","bundle":{"path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSafari.app","bundle_id":"com.apple.mobilesafari","binary":{"name":"MobileSafari","path":"\/Applications\/Xcode.app\/Contents\/Developer\/Platforms\/iPhoneOS.platform\/Library\/Developer\/CoreSimulator\/Profiles\/Runtimes\/iOS.simruntime\/Contents\/Resources\/RuntimeRoot\/Applications\/MobileSafari.app\/MobileSafari","architectures":["x86_64"]},"name":"MobileSafari"},"install_type":"system"}],"event_type":"discrete"}
//        whenever(fbsimctl.listApps(udid)).thenReturn(listOf(bundleInfoStub))
//
//        val container = FileSystem(remote, udid).dataContainer("test.bundle")
//
//        assertEquals(Paths.get(containerPathStub), container.basePath)
    }

    @Test
    fun shouldFailOnNonExistingBundleId() {
        whenever(fbsimctl.listApps(udid)).thenReturn(listOf(bundleInfoStub))


        assertFails {
            FileSystem(remote, udid).dataContainer("non-existing.bundle.id")
        }
    }
}
