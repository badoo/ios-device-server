package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.command.ShellCommand
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * embedded.mobileprovision
 */
class ProvisioningProfile(file: File) {
    private val config: XMLPropertyListConfiguration

    init {
        val result = ShellCommand().exec(listOf("/usr/bin/security", "cms", "-D", "-i", file.absolutePath))
        val input = BufferedReader(StringReader(result.stdOut))
        config = XMLPropertyListConfiguration()
        config.read(input)
    }

    fun provisionedDevices(): List<String> = config.getList("ProvisionedDevices") as List<String>
}