package com.badoo.automation.deviceserver.data

import io.ktor.util.logging.Logger
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.jvm.javaClass

class DataPersistenceService(
    val file: File,
    val logger: Logger = LoggerFactory.getLogger(DataPersistenceService::javaClass.name)
) {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    inline fun <reified T> saveToJsonFile(data: T) {
        try {
            val jsonString = json.encodeToString(data)
            file.writeText(jsonString)
        } catch (e: Exception) {
            logger.error("Error saving data to file: ${e.javaClass} ${e.message}", e)
        }
    }

    inline fun <reified T> loadFromJsonFile(): T? {
        try {
            if (!file.exists()) {
                logger.error("File ${file.absolutePath} does not exist.")
                return null
            }
            val jsonString = file.readText()
            return json.decodeFromString<T>(jsonString)
        } catch (e: Exception) {
            logger.error("Error loading data from file: ${e.javaClass} ${e.message}", e)
            return null
        }
    }
}