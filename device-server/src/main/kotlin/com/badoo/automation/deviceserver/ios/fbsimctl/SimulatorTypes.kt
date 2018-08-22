package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.command.IShellCommand

class SimulatorTypes(private val shellCommand: IShellCommand) {
    private val mapper = JsonMapper()

    fun availableRuntimes(): List<String> {
        return fetch("runtimes")
    }

    fun availableModels(): List<String> {
        return fetch("devicetypes")
    }

    private fun fetch(type: String): List<String> {
        val rv = shellCommand.exec(listOf("xcrun", "simctl", "list", "--json", type))

        if (!rv.isSuccess) {
            return emptyList()
        }

        return mapper.readTree(rv.stdOut.byteInputStream())[type].mapNotNull { it["name"].toString() }.toList()
    }
}