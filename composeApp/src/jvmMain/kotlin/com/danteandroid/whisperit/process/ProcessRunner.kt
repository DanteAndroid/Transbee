package com.danteandroid.whisperit.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset

object ProcessRunner {

    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit = {},
        charset: Charset = Charsets.UTF_8,
    ): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        val process = try {
            pb.start()
        } catch (e: IOException) {
            val program = command.firstOrNull().orEmpty()
            val msg = e.message.orEmpty()
            val isNotFound = msg.contains("error=2") || msg.contains("ENOENT") ||
                    msg.contains("CreateProcess error=2", ignoreCase = true) ||
                    msg.contains("No such file", ignoreCase = true)
            if (isNotFound) {
                throw IOException(
                    "找不到可执行文件「$program」。请填写完整路径，或把程序放到安装包目录，或确保已安装并在系统路径中。",
                    e,
                )
            }
            throw e
        }
        try {
            process.inputStream.bufferedReader(charset).use { reader ->
                reader.lineSequence().forEach { line ->
                    ensureActive()
                    onStdoutLine(line)
                }
            }
            ensureActive()
            process.waitFor()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}