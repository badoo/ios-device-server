package com.badoo.automation.deviceserver.util

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import java.io.File

/**
 * Info.plist
 */
class InfoPlist(file: File) {
    private val config: XMLPropertyListConfiguration = XMLPropertyListConfiguration().apply {
        // Read file and remove DOCTYPE declaration that causes DTD fetch
        val xmlContent = file.readText()
        val cleanedXml = xmlContent.replace(Regex("""<!DOCTYPE[^>]*>"""), "")

        // Parse the cleaned XML (no external DTD fetch)
        read(java.io.StringReader(cleanedXml))
    }

    fun bundleIdentifier(): String = config.getString("CFBundleIdentifier")
    fun bundleName(): String = config.getString("CFBundleName")
}
