package com.badoo.automation.deviceserver.ios.simulator.backup

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.dateNowUTC
import com.fasterxml.jackson.core.JsonProcessingException
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.IOException

class SimulatorBackup(
        private val remote: IRemote,
        private val udid: UDID,
        deviceSetPath: String,
        private val simulatorDirectory: File,
        private val simulatorDataDirectory: File,
        config: ApplicationConfiguration = ApplicationConfiguration()
) : ISimulatorBackup {
    private val backupPath: String = File(config.simulatorBackupPath ?: deviceSetPath , udid).absolutePath + "_BACKUP"
    private val backedDataFolder = File(backupPath, "data").absolutePath

    private val metaFilePath: String = File(backupPath, "data/device_server/meta.json").absolutePath
    private val metaFileDirectory = File(metaFilePath).parent
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    ))

    companion object {
        const val CURRENT_VERSION = 5
    }

    data class BackupMeta(val version: Int, val created: String) {
        override fun toString(): String = JsonMapper().toJson(this)
    }

    override fun toString() = "<${this.javaClass.simpleName}: $udid>"

    //region backup exists?
    override fun isExist(): Boolean {
        logger.debug(logMarker, "Checking that backup path exists: [$backupPath]")
        if (!remote.isDirectory(backupPath)) {
            return false
        }

        val result = remote.execIgnoringErrors(listOf("cat", metaFilePath))

        if (!result.isSuccess) {
            logger.debug(logMarker, "$this could not read backup version: ${result.stdErr}")
            return false
        }

        val parsedMeta = parseMeta(result.stdOut.trim())
        return CURRENT_VERSION == parsedMeta?.version
    }

    private fun parseMeta(stdOut: String): BackupMeta? {
        return try {
            JsonMapper().fromJson<BackupMeta>(stdOut)
        } catch(e: JsonProcessingException) {
            null
        }
    }
    //endregion

    //region create backup
    override fun create() {
        remote.execIgnoringErrors(listOf("rm", "-rf", backupPath))
        val result = remote.execIgnoringErrors(listOf("cp", "-Rp", simulatorDirectory.absolutePath, backupPath), timeOutSeconds = 120)

        if (!result.isSuccess) {
            val stdOutLines = result.stdOut.lines().map { it.trim() }.filter { it.isNotBlank() }

            val ignorableFailures = stdOutLines.filter { it.contains("Deleting") && it.contains("No such file or directory") }

            if (ignorableFailures.isNotEmpty()) {
                logger.warn(logMarker, "Failed to copy ignorable files while creating backup for simulator $udid at path: [$backupPath]: ${ignorableFailures.joinToString(", ")}")
            }

            val failures = stdOutLines.filter { !it.contains("Deleting") && !it.contains("No such file or directory") }

            if (failures.isNotEmpty()) {
                val message = "Failed to copy files while creating backup for simulator $udid at path: [$backupPath]: ${failures.joinToString(", ")}"
                logger.error(logMarker, message)
                throw SimulatorBackupError("$this failed to create backup $backupPath: $result", SimulatorBackupError(message))
            }
        }

        writeMeta()
        logger.debug(logMarker, "Created backup for simulator $udid at path: [$backupPath]")
    }

    private fun writeMeta() {
        val meta = BackupMeta(CURRENT_VERSION, dateNowUTC().withNano(0).toString())
        val content = JsonMapper().toJson(meta)

        when {
            remote.isLocalhost() -> {
                if (!File(metaFileDirectory).mkdirs()) {
                    throw SimulatorBackupError("$this could not create $metaFileDirectory directory")
                }

                try {
                    File(metaFilePath).writeText(content)
                } catch (e: IOException) {
                    throw SimulatorBackupError("$this could not write meta.json $e")
                }
            }
            else -> {
                remote.execIgnoringErrors(listOf("mkdir", "-p", metaFileDirectory))
                val result = remote.shell(
                    "echo ${ShellUtils.escape(content)} > $metaFilePath",
                    returnOnFailure = true
                )
                ensureSuccess(result, "$this could not write meta.json: ${result.stdErr}")
            }
        }
    }
    //endregion

    override fun restore() {
        val simulatorDataDirectoryPath = simulatorDataDirectory.absolutePath
        val deleteResult = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", simulatorDataDirectoryPath), timeOutSeconds = 90L)

        if (!deleteResult.isSuccess) {
            logger.error(logMarker, "Failed to delete at path: [$simulatorDataDirectoryPath]. Result: $deleteResult")

            val r = remote.execIgnoringErrors(listOf("/usr/bin/sudo", "/bin/rm", "-rf", simulatorDataDirectoryPath), timeOutSeconds = 90L);

            if (!r.isSuccess) {
                val undeletedFiles = remote.execIgnoringErrors(listOf("/usr/bin/find", simulatorDataDirectoryPath), timeOutSeconds = 90L);
                logger.error(logMarker, "Failed to delete at path: [$simulatorDataDirectoryPath]. Not deleted files: ${undeletedFiles.stdOut}")
            }
        }

        val result = remote.execIgnoringErrors(listOf("/bin/cp", "-Rfp", backedDataFolder, simulatorDirectory.absolutePath), timeOutSeconds = 120L)

        if (!result.isSuccess) {
            logger.error(logMarker, "Failed to restore from backup at path: [$backedDataFolder] to path: [$result]")

            val secondTry = remote.execIgnoringErrors(listOf("/bin/cp", "-Rfp", backedDataFolder, simulatorDirectory.absolutePath), timeOutSeconds = 120L)

            if (!secondTry.isSuccess) {
                val errorMessage = "Failed second attempt to restore from backup at path: [$backedDataFolder] to path: [$secondTry]"
                logger.error(logMarker, errorMessage)
                throw SimulatorBackupError(errorMessage)
            }
        }

        logger.debug(logMarker, "Restored simulator $udid from backup at path: [$backedDataFolder]")
    }

    override fun delete() {
        val result = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", backupPath))

        ensureSuccess(result, "$this failed to delete backup $backupPath: $result")
        logger.debug(logMarker, "Deleted backup for simulator $udid at path: [$backupPath]")
    }

    private fun ensureSuccess(result: CommandResult, errorMessage: String) {
        if (!result.isSuccess) {
            throw SimulatorBackupError(errorMessage)
        }
    }
}
