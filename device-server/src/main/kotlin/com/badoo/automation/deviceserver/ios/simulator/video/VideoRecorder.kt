package com.badoo.automation.deviceserver.ios.simulator.video

import java.io.File

interface VideoRecorder {
    fun start()
    fun stop()
    fun getRecording(): File
    fun getRecordingLog(): String
    fun delete()
    fun dispose()
}
