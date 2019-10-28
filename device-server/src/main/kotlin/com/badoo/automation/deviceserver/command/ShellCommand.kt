package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.ios.proc.LongRunningProcessListener
import com.badoo.automation.deviceserver.util.ensure
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import com.zaxxer.nuprocess.NuProcessHandler
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.time.Duration
import java.util.concurrent.TimeUnit

open class ShellCommand(
        private val builderFactory: (cmd: List<String>, env: Map<String, String>) -> NuProcessBuilder = ::defaultNuProcessBuilder, //for testing
        private val commonEnvironment: Map<String, String> = mapOf()
    ) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))

    companion object {
        fun defaultNuProcessBuilder(cmd: List<String>, env: Map<String, String>): NuProcessBuilder = NuProcessBuilder(cmd, env)
    }

    override fun exec(command: List<String>, environment: Map<String, String>, timeOut: Duration,
                      returnFailure: Boolean, logMarker: Marker?, processListener: IShellCommandListener): CommandResult {
        val process: NuProcess = startProcessInternal(command, environment, processListener)

        var exitCode = Int.MIN_VALUE

        val commandString = command.joinToString(" ")

        try {
            exitCode = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            logger.warn(logMarker, "Error while running command: $commandString", e)
        }

        if (exitCode == Int.MIN_VALUE) { // waiting timed out
            logger.error(logMarker, "Command has failed to complete in time. Command: $commandString")

            try {
                process.destroy(false)
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Error while terminating command: $commandString", e)
            }
            try {
                process.destroy(true)
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Error while terminating command forcibly: $commandString", e)
            }
        }

        val result = CommandResult(
                stdOut = processListener.stdOut,
                stdErr = processListener.stdErr,
                stdOutBytes = processListener.bytes,
                exitCode = processListener.exitCode,
                cmd = command // Store actual command - including ssh stuff.
        )
        ensure(processListener.exitCode == 0 || returnFailure) {
            val errorMessage = "Error while running command: $commandString Result=$result"
            logger.error(logMarker, errorMessage)
            ShellCommandException(errorMessage)
        }
        return result
    }

    override fun startProcess(command: List<String>, environment: Map<String, String>, logMarker: Marker?, processListener: LongRunningProcessListener) {
        startProcessInternal(command, environment, processListener)
    }

    override fun escape(value: String): String {
        return value
    }

    private fun startProcessInternal(command: List<String>, environment: Map<String, String>, processListener: NuProcessHandler): NuProcess {
        logger.debug(logMarker, "Executing command: ${command.joinToString(" ")}")
        val cmdEnv = environment + commonEnvironment
        val processBuilder = builderFactory(command, cmdEnv)
        processBuilder.setProcessListener(processListener)
        return processBuilder.start()
    }
}
