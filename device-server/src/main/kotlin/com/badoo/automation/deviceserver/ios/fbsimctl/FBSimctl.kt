package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.util.ensure
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

class FBSimctl(
        private val shellCommand: IShellCommand,
        private val parser: IFBSimctlResponseParser = FBSimctlResponseParser()
) : IFBSimctl {
    companion object {
        private val SIMULATOR_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val FBSIMCTL_BIN = "/usr/local/bin/fbsimctl"
        const val RESPONSE_FORMAT = "--json"
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override fun installApp(udid: UDID, bundlePath: File) {
        val response = fbsimctl(listOf("install", bundlePath.absolutePath), udid, raiseOnError = true)
        val result = parser.parseInstallApp(response)
        if (!result.isSuccess) {
            throw FBSimctlError("Failed to install application [${bundlePath.absolutePath}]. Error:\n${result.errorMessage}")
        }
    }

    override fun listSimulators(): List<FBSimctlDevice> {
        val cmd = listOf("--simulators", "list")
        return parser.parseDeviceList(fbsimctl(cmd, raiseOnError = false))
    }

    override fun listDevices(): List<FBSimctlDevice> {
        val cmd = listOf("--devices", "list")
        // TODO: Untrusted devices will have empty name, model, os, etc. Consider rejecting them
        return parser.parseDeviceList(fbsimctl(cmd, raiseOnError = false))
    }

    override fun listDevice(udid: UDID): FBSimctlDevice? {
        val devices = parser.parseDeviceList(fbsimctl("list", udid, raiseOnError = false))

        return if (devices.isEmpty()) {
            null
        } else {
            devices.first()
        }
    }

    override fun listApps(udid: UDID): List<FBSimctlAppInfo> = parser.parseApplicationsList(fbsimctl(cmd = "list_apps", udid = udid))

    /**
     * returns path to device sets
     * E.g. "/Users/qa/Library/Developer/CoreSimulator/Devices"
     */
    override fun defaultDeviceSet(): String {
        val response = fbsimctl("list_device_sets", jsonFormat = true)
        val deviceSet = parser.parseDeviceSets(response).firstOrNull()

        if (deviceSet == null) {
            throw FBSimctlError("No device_sets returned by fbsimctl")
        } else {
            return deviceSet
        }
    }

    override fun eraseSimulator(udid: UDID) = fbsimctl(cmd = "erase", udid = udid)

    override fun create(model: String?, os: String?, transitional: Boolean): FBSimctlDevice {
        val args = mutableListOf("create")

        // FIXME: escaping should be part of exec implementation and hidden from caller. Fix in separate ticket.
        if (os != null) { args.add(shellCommand.escape(os)) }
        if (model != null) { args.add(shellCommand.escape(model)) }

        val result = fbsimctl(args)

        if (result.isEmpty()) {
            // FIXME: We don't take available device types and runtimes in account when routing device creation request.
            // We only take in account existing devices when matching node to desired capabilities.
            // This means that if none of the nodes have at least one of desired model devices already,
            // we will choose a random one to handle creation request which might be the one that can not create
            // specific model, while other nodes could.
            val suggestions = suggestCreationArgs()
            throw(RuntimeException("Could not create simulator \"$model\" \"$os\"\n$suggestions"))
        }
        return parser.parseDeviceCreation(result, transitional)
    }

    private fun suggestCreationArgs(): String {
        val types = SimulatorTypes(shellCommand)
        val models = types.availableModels()
        val osVersions = types.availableRuntimes()

        return "Available models:\n  " + models.joinToString("\n  ") + "\nAvailable os versions:\n  " + osVersions.joinToString("\n  ")
    }

    override fun diagnose(udid: UDID): FBSimctlDeviceDiagnosticInfo {
        return parser.parseDiagnosticInfo(fbsimctl(cmd = "diagnose", udid = udid))
    }

    override fun shutdown(udid: UDID): CommandResult {
        return shellCommand.exec("/usr/bin/xcrun simctl shutdown $udid".split(" "), timeOut = SIMULATOR_SHUTDOWN_TIMEOUT, returnFailure = true)
    }

    override fun shutdownAllBooted() = shellCommand.exec("/usr/bin/xcrun simctl shutdown all".split(" "), timeOut = Duration.ofMinutes(3), returnFailure = true).stdOut

    override fun delete(udid: UDID) = fbsimctl("delete", udid)

    override fun terminateApp(udid: UDID, bundleId: String, raiseOnError: Boolean)
            = fbsimctl(listOf("terminate", bundleId), udid, raiseOnError = raiseOnError)

    override fun uninstallApp(udid: UDID, bundleId: String) {
        fbsimctl(listOf("uninstall", bundleId), udid, raiseOnError = true)
    }

    private fun fbsimctl(
        cmd: String,
        udid: UDID? = null,
        jsonFormat: Boolean = true,
        timeOut: Duration = Duration.ofSeconds(30),
        raiseOnError: Boolean = true
    ) = fbsimctl(
        cmd.split(" "),
        udid,
        jsonFormat,
        timeOut = timeOut,
        raiseOnError = raiseOnError
    )

    private fun fbsimctl(
        cmd: List<String>,
        udid: UDID? = null,
        jsonFormat: Boolean = true,
        timeOut: Duration = Duration.ofSeconds(30),
        raiseOnError: Boolean = false
    ): String {
        val fbsimctlCommand = buildFbsimctlCommand(jsonFormat, udid, cmd)

        val result = try {
            shellCommand.exec(fbsimctlCommand, timeOut = timeOut, returnFailure = true)
        } catch (e: SshConnectionException) {
            shellCommand.exec(fbsimctlCommand, timeOut = timeOut, returnFailure = true)
        }

        val errors = filterFailures(result.stdOut)
        errors.forEach { logger.warn("fbsimctl warnings: ${it["subject"]}") }

        if (raiseOnError) {
            ensure(errors.isEmpty()) { FBSimctlError("fbsimctl failed: $errors", null) }
            if (result.exitCode != 0) {
                throw FBSimctlError("Error while running command. Result: $result", null)
            }
        }

        return result.stdOut.trim() // remove last new_line
    }

    private fun buildFbsimctlCommand(jsonFormat: Boolean, udid: UDID?, command: List<String>): ArrayList<String> {
        val cmd = arrayListOf<String>()
        cmd.add(FBSIMCTL_BIN)

        if (jsonFormat) {
            cmd.add(RESPONSE_FORMAT)
        }

        if (udid != null) {
            cmd.add(udid)
        }

        cmd.addAll(command)
        return cmd
    }

    private fun filterFailures(fbsimctlResponse: String): List<Map<String, Any>> {
        return parser.parseFailures(fbsimctlResponse)
    }
}
