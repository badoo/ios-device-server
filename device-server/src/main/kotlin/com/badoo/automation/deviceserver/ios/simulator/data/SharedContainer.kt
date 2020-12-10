package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.nio.file.Path

class SharedContainer(
    private val remote: IRemote,
    private val basePath: File
): SimulatorFilesystemContainer(remote) {

    fun delete(path: Path) {
        val expandedPath = expandPath(path, basePath)
        remote.shell("rm -rf $expandedPath", false)
    }

    fun writeFile(data: ByteArray, path: Path) {
        val dataContainerFile = expandPath(path, basePath).toFile()
        super.writeFile(dataContainerFile, data)
    }

    fun readFile(path: Path): ByteArray {
        val expandedPath = sshNoEscapingWorkaround(expandPath(path, basePath).toString())

        return super.readFile(expandedPath)
    }
}
