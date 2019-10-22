package com.badoo.automation.deviceserver.ios.proc

import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessHandler
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

class LongRunningFileLoggingProcessListener(
    private val stdOutFile: File,
    private val stdErrFile: File
): NuProcessHandler {
    private lateinit var nuProcess: NuProcess
    private lateinit var stdErrChannel: WritableByteChannel
    private lateinit var stdOutChannel: WritableByteChannel
    private var exitCode: Int = Int.MIN_VALUE

    override fun onStdinReady(buffer: ByteBuffer): Boolean {
        return false
    }

    override fun onPreStart(nuProcess: NuProcess) {
        this.nuProcess = nuProcess

        stdOutChannel = FileOutputStream(stdOutFile).channel
        stdErrChannel = FileOutputStream(stdErrFile).channel
    }

    override fun onStderr(buffer: ByteBuffer, closed: Boolean) {
        writeBytesToChannel(buffer, stdErrChannel, closed)
    }

    override fun onStdout(buffer: ByteBuffer, closed: Boolean) {
        writeBytesToChannel(buffer, stdOutChannel, closed)
    }

    override fun onExit(exitCode: Int) {
        this.exitCode = exitCode
    }

    override fun onStart(nuProcess: NuProcess) {
    }

    private fun writeBytesToChannel(buffer: ByteBuffer, channel: WritableByteChannel, closed: Boolean) {
        if (buffer.hasRemaining()) {
            channel.write(buffer)

            buffer.compact();
        } else {
            buffer.clear();
        }

        if (closed) {
            channel.close()
        }
    }
}