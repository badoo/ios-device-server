package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.nio.file.Path

class DataContainer(
    private val remote: IRemote,
    internal val basePath: Path,
    private val bundleId: String
    ) {

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
