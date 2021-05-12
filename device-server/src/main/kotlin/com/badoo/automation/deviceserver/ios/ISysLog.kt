package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.SysLogCaptureOptions
import java.io.File

interface ISysLog {
    val osLogFile: File
    val osLogStderr: File
    fun truncate(): Boolean
    fun content(process: String?): String
    fun deleteLogFiles()
    fun stopWritingLog()
    fun startWritingLog(sysLogCaptureOptions: SysLogCaptureOptions)
}
