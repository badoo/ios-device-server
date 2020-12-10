package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.withDefers
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.RuntimeException
import java.nio.file.Path

abstract class SimulatorFilesystemContainer(private val remote: IRemote) {
    private val logger = LoggerFactory.getLogger(this.javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remote.hostName
    ))

    open fun writeFile(file: File, data: ByteArray) {
        if (remote.isLocalhost()) {
            file.writeBytes(data)
            logger.debug(logMarker, "Successfully wrote data to file ${file.absolutePath}")
        } else {
            withDefers(logger) {
                val tmpFile = File.createTempFile("${file.nameWithoutExtension}.", ".${file.extension}")
                defer { tmpFile.delete() }
                tmpFile.writeBytes(data)
                remote.scpToRemoteHost(tmpFile.absolutePath, file.absolutePath)
                logger.debug(logMarker, "Successfully wrote data to remote file ${file.absolutePath}")
            }
        }
    }

    fun readFile(path: String): ByteArray {
        try {
            return remote.captureFile(File(path))
        } catch (e: RuntimeException) {
            throw DataContainerException("Could not read file $path", e)
        }
    }

    fun expandPath(path: Path, basePath: File): Path {
        val expanded = basePath.toPath().resolve(path).normalize()

        if (!expanded.startsWith(basePath.absolutePath)) {
            throw DataContainerException("$path points outside the container of ${basePath.absolutePath}")
        }

        return expanded
    }

    internal fun sshNoEscapingWorkaround(path: String): String {
        // FIXME: fix escaping on ssh side and remove workarounds
        return when {
            remote.isLocalhost() -> path
            else -> ShellUtils.escape(path)
        }
    }
}
