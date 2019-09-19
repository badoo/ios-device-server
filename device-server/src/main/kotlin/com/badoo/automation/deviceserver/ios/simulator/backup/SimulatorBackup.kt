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
        private const val CURRENT_VERSION = 1
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
        val result = remote.execIgnoringErrors(listOf("cp", "-R", srcPath, backupPath))

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
                remote.execIgnoringErrors("mkdir -p $metaFileDirectory".split(" "))
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
        val deleteResult = remote.execIgnoringErrors(listOf("rm", "-rf", srcPath), timeOutSeconds = 60L)
        if (!deleteResult.isSuccess) {
            logger.error(logMarker, "Failed to delete at path: [$backupPath]")
            val killResult = remote.shell("lsof -a -l -n -M -R -P -t +D $srcPath | xargs -n 1 kill -HUP", true)
            logger.debug(logMarker, "Trying to kill processes holding $backupPath. Exit code: ${killResult.exitCode}")
            val secondDeleteResult = remote.execIgnoringErrors(listOf("rm", "-rf", srcPath), timeOutSeconds = 60L)
            logger.error(logMarker, "Trying to delete $srcPath second time. Exit code: ${secondDeleteResult.exitCode}")
            remote.shell("lsof -a -l -n -M -R -P -t +D $srcPath | xargs -n 1 kill -HUP", true)
        }

        val result = remote.execIgnoringErrors(listOf("cp", "-R", backupPath, srcPath), timeOutSeconds = 120L)

        ensureSuccess(result, "$this failed to restore from backup $backupPath: $result")
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