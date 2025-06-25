package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.host.IRemote
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class DataContainer(
    private val remote: IRemote,
    internal val basePath: File,
    private val bundleId: String
): SimulatorFilesystemContainer(remote) {

    fun listFiles(path: Path): List<String> {
        val expandedPath: Path = expandPath(path, basePath)

        if (!Files.exists(expandedPath)) {
            throw DataContainerException("Path $path does not exist in $bundleId")
        }

        if (!Files.isDirectory(expandedPath)) {
            return listOf(expandedPath.absolutePathString())
        }

        return Files.list(expandedPath).toList().map { it.absolutePathString() }
    }

    fun readFile(path: Path): ByteArray {
        val expandedPath = expandPath(path, basePath).toString()

        return super.readFile(expandedPath)
    }

    override fun writeFile(file: File, data: ByteArray) {
        val dataContainerFile =  File(basePath.absolutePath, file.name)
        super.writeFile(dataContainerFile, data)
    }

    fun delete() {
        remote.shell("rm -rf ${basePath.absolutePath}")
        remote.shell("mkdir -p ${basePath.absolutePath}")
    }

    fun setPlistValue(path: Path, key: String, value: String) {
        val expandedPath = expandPath(path, basePath).toString()
        remote.shell("/usr/libexec/PlistBuddy -c 'Set $key $value' $expandedPath", false) // TODO: Simple values only for now
    }

    fun addPlistValue(path: Path, key: String, value: String, type: String) {
        val expandedPath = expandPath(path, basePath).toString()
        remote.shell("/usr/libexec/PlistBuddy -c 'Add $key $type $value' $expandedPath", false) // TODO: Simple values only for now
    }
}
