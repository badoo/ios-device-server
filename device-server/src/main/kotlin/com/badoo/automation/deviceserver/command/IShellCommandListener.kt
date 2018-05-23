package com.badoo.automation.deviceserver.command

import com.zaxxer.nuprocess.NuProcessHandler

interface IShellCommandListener : NuProcessHandler {
    val stdOut: String
    val stdErr: String
    val bytes: ByteArray // binary data. for ex.: video recording
    val exitCode: Int
}
