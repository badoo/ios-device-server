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

class DataContainer(
    private val remote: IRemote,
    internal val basePath: File,
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

        try {
            return remote.captureFile(File(expandedPath))
        } catch (e: RuntimeException) {
            throw DataContainerException("Could not read file $path for $bundleId", e)
        }
    }

    fun writeFile(file: File, data: ByteArray) {
        val dataContainerFile = File(basePath.absolutePath, file.name)

        if (remote.isLocalhost()) {
            dataContainerFile.writeBytes(data)
            logger.debug(logMarker, "Successfully wrote data to file ${dataContainerFile.absolutePath}")
        } else {
            withDefers(logger) {
                val tmpFile = File.createTempFile("${file.nameWithoutExtension}.", ".${file.extension}")
                defer { tmpFile.delete() }
                tmpFile.writeBytes(data)
                remote.scpToRemoteHost(tmpFile.absolutePath, dataContainerFile.absolutePath)
                logger.debug(logMarker, "Successfully wrote data to remote file ${dataContainerFile.absolutePath}")
            }
        }
    }

    fun delete() {
        remote.shell("rm -rf ${basePath.absolutePath}")
        remote.shell("mkdir -p ${basePath.absolutePath}")
    }

    fun setPlistValue(path: Path, key: String, value: String) {
        val expandedPath = sshNoEscapingWorkaround(expandPath(path).toString())
        remote.shell("/usr/libexec/PlistBuddy -c 'Set $key $value' $expandedPath", false) // TODO: Simple values only for now
    }

    fun addPlistValue(path: Path, key: String, value: String, type: String) {
        val expandedPath = sshNoEscapingWorkaround(expandPath(path).toString())
        remote.shell("/usr/libexec/PlistBuddy -c 'Add $key $type $value' $expandedPath", false) // TODO: Simple values only for now
    }

    private fun sshNoEscapingWorkaround(path: String): String {
        // FIXME: fix escaping on ssh side and remove workarounds
        return when {
            remote.isLocalhost() -> path
            else -> ShellUtils.escape(path)
        }
    }

    private fun expandPath(path: Path): Path {
        val expanded = basePath.toPath().resolve(path).normalize()
        if (!expanded.startsWith(basePath.absolutePath)) {
            throw DataContainerException("$path points outside the container of $bundleId")
        }

        return expanded
    }
}
