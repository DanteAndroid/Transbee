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
import java.time.Duration

object WhisperVadModel {

    const val FILE_NAME = "ggml-silero-v6.2.0.bin"
    private val downloadUrl =
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$FILE_NAME"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun modelFile(): File = File(WhisperModelPaths.modelsDirectory(), FILE_NAME)

    suspend fun ensureDownloaded(
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dest = modelFile()
        if (dest.isFile && dest.length() > 0L) return@withContext dest
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "$FILE_NAME.part")
        part.delete()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .timeout(Duration.ofHours(1))
            .header(
                "User-Agent",
                "Whisperit/1.0 (compatible; Whisper-VAD-Downloader)",
            )
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val code = response.statusCode()
        if (code !in 200..299) {
            runCatching { part.delete() }
            error("VAD 模型下载失败（HTTP $code）")
        }
        val contentLength = response.headers().firstValue("Content-Length")
            .map { it.toLong() }
            .orElse(null)
        response.body().use { input ->
            Files.newOutputStream(
                part.toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
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
        Files.move(
            part.toPath(),
            dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        dest
    }
}
