package com.danteandroid.whisperit.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration

data class WhisperDownloadResult(
    val file: File,
    val skipped: Boolean,
)

object WhisperModelDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun downloadIfNeeded(
        option: WhisperModelOption,
        force: Boolean,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): WhisperDownloadResult = withContext(Dispatchers.IO) {
        val dir = WhisperModelPaths.modelsDirectory()
        val dest = File(dir, option.fileName)
        if (!force && dest.isFile && dest.length() > 0L) {
            return@withContext WhisperDownloadResult(dest, true)
        }
        if (force) {
            dest.delete()
        }
        val part = File(dir, option.fileName + ".part")
        part.delete()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(option.url))
            .timeout(Duration.ofHours(4))
            .header(
                "User-Agent",
                "Whisperit/1.0 (compatible; Whisper-Model-Downloader)",
            )
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        }
        val code = response.statusCode()
        if (code !in 200..299) {
            runCatching { part.delete() }
            runCatching { response.body().close() }
            error("下载失败（错误码 $code），请检查网络后重试。")
        }
        val contentLength = response.headers().firstValue("Content-Length")
            .map { it.toLong() }
            .orElse(null)
        try {
            response.body().use { input ->
                Files.newOutputStream(
                    part.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        onProgress(received, contentLength)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        }
        Files.move(
            part.toPath(),
            dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        WhisperDownloadResult(dest, false)
    }
}
