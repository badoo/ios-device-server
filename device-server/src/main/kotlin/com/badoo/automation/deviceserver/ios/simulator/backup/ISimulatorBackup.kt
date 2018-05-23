package com.badoo.automation.deviceserver.ios.simulator.backup

interface ISimulatorBackup {
    fun isExist(): Boolean
    fun create()
    fun restore()
    fun delete()
}