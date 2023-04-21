import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceDiagnosticInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlError
import com.badoo.automation.deviceserver.ios.fbsimctl.ISimulatorControl
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

class XCRunSimctl(
    private val shellCommand: IShellCommand,
    override val fbsimctlBinary: String = "Unsupported here"
) : ISimulatorControl {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override fun installApp(udid: UDID, bundlePath: File) {
        TODO("Not yet implemented")
    }

    override fun listSimulators(): List<FBSimctlDevice> {
        val fbsimctlCommand = listOf("xcrun", "simctl", "list", "--json")

        val timeOut = Duration.ofSeconds(30)

        val result = try {
            shellCommand.exec(fbsimctlCommand, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            logger.error("XCRunSimctl retrying command on SSH error. Command: ${fbsimctlCommand.joinToString(" ")}")
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
        val command: List<String> = listOf("xcrun", "simctl", "erase", udid)

        val timeOut = Duration.ofSeconds(30)

        val result = try {
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            logger.error("XCRunSimctl retrying command on SSH error. Command: ${command.joinToString(" ")}")
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        }

        if (result.exitCode != 0) {
            throw FBSimctlError(
                "Error while running command.  Exit code: ${result.exitCode}\n" +
                        "StdErr: ${result.stdErr}. StdOut: ${result.stdOut}", null
            )
        }

        return result.stdOut.trim()
    }

    override fun create(model: String?, os: String?): FBSimctlDevice {
        val modelString = model ?: "iPhone 7"
        val osString = os ?: "iOS 15.0"

        val deviceModel = getDeviceModel(modelString)
        val deviceName = (model ?: "iPhone 7").replace(simDeviceTypeRegex, "_")

        val deviceRuntime = "com.apple.CoreSimulator.SimRuntime.${osString.replace(runtimeRegex, "-")}"
        val command: List<String> = listOf("xcrun", "simctl", "create", deviceName, deviceModel, deviceRuntime)

        val timeOut = Duration.ofSeconds(30)

        val result = try {
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            logger.error("XCRunSimctl retrying command on SSH error. Command: ${command.joinToString(" ")}")
            shellCommand.exec(command, timeOut = timeOut, returnFailure = true)
        }

        if (result.exitCode != 0) {
            throw FBSimctlError(
                "Error while running command.  Exit code: ${result.exitCode}\n" +
                        "StdErr: ${result.stdErr}. StdOut: ${result.stdOut}", null
            )
        }

        val udid = result.stdOut.trim() // remove last new_line

        return FBSimctlDevice(
            arch = "x86_64",
            state = "Shutdown",
            model = modelString,
            name = modelString,
            udid = udid,
            os = osString
        )
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

    companion object {
        val quirkyDeviceTypes: Map<String, String> = mapOf(
            "iPhone SE (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPhone-SE",
            "iPhone SE (2nd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPhone-SE--2nd-generation-",
            "iPhone SE (3rd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation",
            "iPhone Xs" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XS",
            "iPhone Xs Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XS-Max",
            "iPhone Xʀ" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XR",
            "iPhone XR" to "com.apple.CoreSimulator.SimDeviceType.iPhone-XR",
            "iPad Pro (12.9-inch) (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro",
            "iPad Pro (11-inch) (1st generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro--11-inch-",
            "iPad (9th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-9th-generation",
            "iPad Pro (11-inch) (3rd generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-3rd-generation",
            "iPad Pro (12.9-inch) (5th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-12-9-inch-5th-generation",
            "iPad mini (6th generation)" to "com.apple.CoreSimulator.SimDeviceType.iPad-mini-6th-generation",
            "iPhone 14" to "com.apple.CoreSimulator.SimDeviceType.iPhone-14",
            "iPhone 14 Plus" to "com.apple.CoreSimulator.SimDeviceType.iPhone-14-Plus",
            "iPhone 14 Pro" to "com.apple.CoreSimulator.SimDeviceType.iPhone-14-Pro",
            "iPhone 14 Pro Max" to "com.apple.CoreSimulator.SimDeviceType.iPhone-14-Pro-Max"
        )

        private val simDeviceTypeRegex = Regex("[ ().]")
        private val runtimeRegex = Regex("[ .]")

        fun getDeviceModel(model: String): String {
            return quirkyDeviceTypes[model] ?: "com.apple.CoreSimulator.SimDeviceType.${
                if (model.contains("Watch")) {
                    model.replace(" - ", " ").replace(simDeviceTypeRegex, "-")
                } else {
                    model.replace(simDeviceTypeRegex, "-")
                }
            }"
        }
    }
}
