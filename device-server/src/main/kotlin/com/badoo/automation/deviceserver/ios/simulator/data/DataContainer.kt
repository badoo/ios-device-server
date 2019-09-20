package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.withDefers
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

class DataContainer(
    private val remote: IRemote,
    internal val basePath: Path,
    private val bundleId: String
) {
    private val logger = LoggerFactory.getLogger(DataContainer::class.java.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    ))

    fun listFiles(path: Path): List<String> {
        val expandedPath = sshNoEscapingWorkaround(expandPath(path).toString())

        val result = remote.execIgnoringErrors(listOf("ls", "-1", "-p", expandedPath))
        if (!result.isSuccess) {
            throw(DataContainerException("Could not list $path for $bundleId: $result"))
        }

        return result.stdOut.lines().filter { it.isNotEmpty() }
    }

    fun readFile(path: Path): ByteArray {
        val expandedPath = sshNoEscapingWorkaround(expandPath(path).toString())

        val result = remote.captureFile(File(expandedPath))
        if (!result.isSuccess) {
            throw DataContainerException("Could not read file $path for $bundleId: $result")
        }
        return result.stdOutBytes
    }

    fun writeFile(file: File, data: ByteArray) {
        val dataContainerFile = File(basePath.toFile().absolutePath, file.name)

        if (remote.isLocalhost()) {
            dataContainerFile.writeBytes(data)
            logger.debug(logMarker, "Successfully wrote data to file ${dataContainerFile.absolutePath}")
        } else {
            withDefers(logger) {
                val tmpFile = File.createTempFile("${file.nameWithoutExtension}.", ".${file.extension}")
                defer { tmpFile.delete() }
                tmpFile.writeBytes(data)
                remote.scp(tmpFile.absolutePath, dataContainerFile.absolutePath)
                logger.debug(logMarker, "Successfully wrote data to remote file ${dataContainerFile.absolutePath}")
            }
        }
    }

    private fun sshNoEscapingWorkaround(path: String): String {
        // FIXME: fix escaping on ssh side and remove workarounds
        return when {
            remote.isLocalhost() -> path
            else -> ShellUtils.escape(path)
        }
    }

    private fun expandPath(path: Path): Path {
        val expanded = basePath.resolve(path).normalize()
        if (!expanded.startsWith(basePath)) {
            throw DataContainerException("$path points outside the container of $bundleId")
        }

        return expanded
    }
}
