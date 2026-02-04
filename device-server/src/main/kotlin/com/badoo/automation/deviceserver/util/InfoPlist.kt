package com.badoo.automation.deviceserver.util

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Info.plist
 */
class InfoPlist(file: File) {
    private val config: XMLPropertyListConfiguration

    init {
        config = XMLPropertyListConfiguration()
        BufferedReader(FileReader(file)).use { input ->
            config.read(input)
        }
    }

    fun bundleIdentifier(): String = config.getString("CFBundleIdentifier")
    fun bundleName(): String = config.getString("CFBundleName")
}
