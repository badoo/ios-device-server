package com.badoo.automation.deviceserver.util

import org.slf4j.Logger
import org.slf4j.Marker
import java.io.File

fun File.deleteRecursivelyIfExist(logger: Logger, logMarker: Marker): Boolean {
    return if (exists()) {
        try {
            val deleted = deleteRecursively()
            if (deleted) {
                logger.debug(logMarker, "File ${this.absolutePath} deleted successfully.")
            } else {
                logger.error(logMarker, "File ${this.absolutePath} could not be deleted, it may be in use or locked or wrong permissions.")
            }
            deleted
        } catch (e: SecurityException) {
            logger.error(logMarker, "SecurityException while trying to delete file ${this.absolutePath}: ${e.message}", e)
            false
        }
    } else {
        logger.debug(logMarker, "File ${this.absolutePath} does not exist, nothing to delete.")
        true
    }
}

fun File.ensureDirectoryExists(logger: Logger, logMarker: Marker): Boolean {
    return if (exists()) {
        if (isDirectory) {
            logger.debug(logMarker, "Directory ${this.absolutePath} already exists.")
            true
        } else {
            logger.error(logMarker, "Path ${this.absolutePath} exists but is not a directory.")
            false
        }
    } else {
        try {
            val created = mkdirs()
            if (created) {
                logger.debug(logMarker, "Directory ${this.absolutePath} created successfully.")
            } else {
                logger.error(logMarker, "Failed to create directory ${this.absolutePath}.")
            }
            created
        } catch (e: SecurityException) {
            logger.error(logMarker, "SecurityException while trying to create directory ${this.absolutePath}: ${e.message}", e)
            false
        }
    }
}
