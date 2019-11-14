package com.badoo.automation.deviceserver.ios.simulator.backup

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
import java.time.Duration

class SimulatorBackup(
        private val remote: IRemote,
        private val udid: UDID,
        deviceSetPath: String
) : ISimulatorBackup {
    private val srcPath: String = File(deviceSetPath, udid).absolutePath
    private val backupPath: String = File(deviceSetPath, udid).absolutePath + "_BACKUP"
    private val metaFilePath: String = File(backupPath, "data/device_server/meta.json").absolutePath
    private val metaFileDirectory = File(metaFilePath).parent
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    ))

    companion object {
        private const val CURRENT_VERSION = 2
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
        val result = remote.execIgnoringErrors(listOf("cp", "-R", srcPath, backupPath), timeOutSeconds = 120)

        ensureSuccess(result, "$this failed to create backup $backupPath: $result")

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
        val deleteResult = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", srcPath), timeOutSeconds = 90L)

        if (!deleteResult.isSuccess) {
            logger.error(logMarker, "Failed to delete at path: [$srcPath]. Result: $deleteResult")

            val r = remote.execIgnoringErrors(listOf("/bin/rm", "-rf", srcPath), timeOutSeconds = 90L);

            if (!r.isSuccess) {
                val undeletedFiles = remote.execIgnoringErrors(listOf("/usr/bin/find", srcPath), timeOutSeconds = 90L);
                logger.error(logMarker, "Failed to delete at path: [$srcPath]. Not deleted files: ${undeletedFiles.stdOut}")
            }
        }

        val result = remote.execIgnoringErrors(listOf("/bin/cp", "-Rfp", backupPath, srcPath), timeOutSeconds = 120L)

        if (!result.isSuccess) {
            logger.error(logMarker, "Failed to restore from backup at path: [$backupPath] to path: [$result]")

            val secondTry = remote.execIgnoringErrors(listOf("cp", "-Rfp", backupPath, srcPath), timeOutSeconds = 120L)

            if (!secondTry.isSuccess) {
                val errorMessage = "Failed second attempt to restore from backup at path: [$backupPath] to path: [$secondTry]"
                logger.error(logMarker, errorMessage)
                throw SimulatorBackupError(errorMessage)
            }
        }

        logger.debug(logMarker, "Restored simulator $udid from backup at path: [$backupPath]")
    }

    override fun delete() {
        val result = remote.execIgnoringErrors("rm -rf $backupPath".split(" "))

        ensureSuccess(result, "$this failed to delete backup $backupPath: $result")
        logger.debug(logMarker, "Deleted backup for simulator $udid at path: [$backupPath]")
    }

    private fun ensureSuccess(result: CommandResult, errorMessage: String) {
        if (!result.isSuccess) {
            throw SimulatorBackupError(errorMessage)
        }
    }
}