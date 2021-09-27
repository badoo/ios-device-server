import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceDiagnosticInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlError
import com.badoo.automation.deviceserver.ios.fbsimctl.ISimulatorControl
import com.badoo.automation.deviceserver.util.ensure
import java.io.File
import java.time.Duration

class XCRunSimctl(
    private val shellCommand: IShellCommand,
    override val fbsimctlBinary: String = "Unsupported here"
) : ISimulatorControl {

    override fun installApp(udid: UDID, bundlePath: File) {
        TODO("Not yet implemented")
    }

    override fun listSimulators(): List<FBSimctlDevice> {
        val fbsimctlCommand = listOf("xcrun", "simctl", "list", "--json")

        val timeOut = Duration.ofSeconds(30)

        val result = try {
            shellCommand.exec(fbsimctlCommand, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            shellCommand.exec(fbsimctlCommand, timeOut = timeOut, returnFailure = true)
        }


        val raiseOnError = false
        if (raiseOnError) {
            if (result.exitCode != 0) {
                throw FBSimctlError("Error while running command. Result: $result", null)
            }
        }

        result.stdOut.trim() // remove last new_line

        return listOf()
    }

    override fun listDevices(): List<FBSimctlDevice> {
        TODO("Not yet implemented")
    }

    override fun listDevice(udid: UDID): FBSimctlDevice? {
        TODO("Not yet implemented")
    }

    override fun listApps(udid: UDID): List<FBSimctlAppInfo> {
        TODO("Not yet implemented")
    }

    override fun defaultDeviceSet(): String {
        TODO("Not yet implemented")
    }

    override fun eraseSimulator(udid: UDID): String {
        TODO("Not yet implemented")
    }

    override fun create(model: String?, os: String?): FBSimctlDevice {
        val deviceName = "$model-$os".replace(" ", "-")
        val command = listOf("xcrun", "simctl", "create", deviceName, model ?: "iPhone 7", os?.replace(" ", "") ?: "iOS15.0")

        val timeOut = Duration.ofSeconds(30)

        val result = try {
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        }


        val raiseOnError = false
        if (raiseOnError) {
            if (result.exitCode != 0) {
                throw FBSimctlError("Error while running command. Result: $result", null)
            }
        }

        result.stdOut.trim() // remove last new_line

        return FBSimctlDevice(state = "", udid = "")
    }

    override fun diagnose(udid: UDID): FBSimctlDeviceDiagnosticInfo {
        TODO("Not yet implemented")
    }

    override fun shutdown(udid: UDID): CommandResult {
        TODO("Not yet implemented")
    }

    override fun shutdownAllBooted(): String {
        TODO("Not yet implemented")
    }

    override fun delete(udid: UDID): String {
        TODO("Not yet implemented")
    }

    override fun terminateApp(udid: UDID, bundleId: String, raiseOnError: Boolean): String {
        TODO("Not yet implemented")
    }

    override fun uninstallApp(udid: UDID, bundleId: String, raiseOnError: Boolean) {
        TODO("Not yet implemented")
    }

}
