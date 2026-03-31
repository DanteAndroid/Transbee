package com.danteandroid.whisperit.native

import com.danteandroid.whisperit.bundled.BundledNativeDistributionPath
import java.io.File

object BundledNativeTools {

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    fun bundledResourcesDirectory(): File? {
        System.getProperty("compose.application.resources.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            File(it).takeIf { d -> d.isDirectory }?.let { return it }
        }
        val pinned = File(BundledNativeDistributionPath.ABSOLUTE_PATH)
        if (pinned.isDirectory) return pinned
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val fromCwd = File(cwd, "native-distribution")
        if (fromCwd.isDirectory) return fromCwd
        val fromRepoRoot = File(cwd, "composeApp/native-distribution")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        var walk: File? = cwd
        repeat(14) {
            val dir = walk ?: return@repeat
            val gradleStyle = File(dir, "composeApp/native-distribution")
            if (gradleStyle.isDirectory) return gradleStyle
            if (dir.name == "composeApp") {
                val moduleDist = File(dir, "native-distribution")
                if (moduleDist.isDirectory) return moduleDist
            }
            walk = dir.parentFile
        }
        return null
    }

    fun ensureComposeResourcesDirFromDiscovery() {
        if (!System.getProperty("compose.application.resources.dir").isNullOrBlank()) return
        bundledResourcesDirectory()?.let { dir ->
            System.setProperty("compose.application.resources.dir", dir.absolutePath)
        }
    }

    private fun bundledFile(name: String): File? {
        val f = bundledResourcesDirectory()?.let { File(it, name) } ?: return null
        if (!f.isFile) return null
        if (!f.canExecute()) {
            f.setExecutable(true, false)
        }
        return f
    }

    fun bundledFfmpeg(): File? = bundledFile("ffmpeg")

    fun bundledWhisperCli(): File? {
        val dir = bundledResourcesDirectory() ?: return null
        if (isWindows()) {
            val nested = File(dir, "whisper-bin/whisper-cli.exe")
            if (nested.isFile) {
                return nested
            }
            val rootExe = File(dir, "whisper-cli.exe")
            if (rootExe.isFile) {
                return rootExe
            }
            return null
        }
        return bundledFile("whisper-cli")
    }

    fun resolveFfmpegPath(): String {
        bundledFfmpeg()?.let { return it.absolutePath }
        // Do not expose manual path settings; prefer bundled tool.
        return "ffmpeg"
    }

    fun resolveWhisperBinaryPath(): String {
        bundledWhisperCli()?.let { return it.absolutePath }
        // Do not expose manual path settings; prefer bundled tool.
        return if (isWindows()) "whisper-cli.exe" else "whisper-cli"
    }
}