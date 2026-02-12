package com.badoo.automation.deviceserver.util

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class InfoPlist(val file: File) {
    val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val cachedConfig: XMLPropertyListConfiguration by lazy {
        logger.info("Reading Info.plist from: ${file.absolutePath}")

        XMLPropertyListConfiguration().apply {
            file.bufferedReader().use { reader ->
                read(reader)
            }
        }
    }

    fun bundleIdentifier(): String = cachedConfig.getString("CFBundleIdentifier")
    fun bundleName(): String = cachedConfig.getString("CFBundleName")
}
