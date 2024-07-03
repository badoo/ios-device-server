package com.badoo.automation.deviceserver.ios.simulator.video

interface VideoRecorder {
    fun start()
    fun stop()
    fun getRecording(): ByteArray
    fun getRecordingLog(): String
    fun delete()
    fun dispose()
}
