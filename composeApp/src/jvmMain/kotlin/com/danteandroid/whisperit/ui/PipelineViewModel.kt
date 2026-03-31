package com.danteandroid.whisperit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danteandroid.whisperit.utils.OsUtils
import com.danteandroid.whisperit.translate.OpenAiTranslator
import com.danteandroid.whisperit.process.PipelinePhase
import com.danteandroid.whisperit.process.isCancellableByStopAll
import com.danteandroid.whisperit.translate.AppleTranslateBinary
import com.danteandroid.whisperit.translate.AppleTranslator
import com.danteandroid.whisperit.translate.DeepLTranslator
import com.danteandroid.whisperit.translate.GoogleTranslator
import com.danteandroid.whisperit.native.BundledNativeTools
import com.danteandroid.whisperit.settings.ToolingSettings
import com.danteandroid.whisperit.settings.ToolingSettingsStore
import com.danteandroid.whisperit.settings.TranscriptionCacheKeyDto
import com.danteandroid.whisperit.settings.TranscriptionCacheStore
import com.danteandroid.whisperit.translate.TargetLanguageMapper
import com.danteandroid.whisperit.translate.TranslationEngine
import com.danteandroid.whisperit.process.ProcessRunner
import com.danteandroid.whisperit.srt.SubtitleExporter
import com.danteandroid.whisperit.whisper.WhisperCliArgs
import com.danteandroid.whisperit.whisper.WhisperJsonParser
import com.danteandroid.whisperit.whisper.WhisperModelDownloader
import com.danteandroid.whisperit.whisper.WhisperModelOption
import com.danteandroid.whisperit.whisper.WhisperParseResult
import com.danteandroid.whisperit.whisper.TranscriptSegment
import com.danteandroid.whisperit.whisper.WhisperVadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.danteandroid.whisperit.utils.JvmResourceStrings
import com.danteandroid.whisperit.utils.subtitleOutputFile
import com.danteandroid.whisperit.utils.toReadableByteSize
import java.util.concurrent.atomic.AtomicInteger
import whisperit.composeapp.generated.resources.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.io.deleteRecursively

/** OpenAI / DeepL 等按批翻译时的每批条数（降低单次请求上下文压力） */
private const val TranslationChunkSizeOpenAiStyle = 15

/** 多批并行翻译；与较小分片搭配，减轻末尾块排队（Google / DeepL） */
private const val TranslationConcurrency = 12

/** 自定义大模型（中转）对并发敏感，过高易 520；单独限流降至 2 求稳 */
private const val TranslationConcurrencyOpenAi = 2

private class TranslationMetrics {
    val requestCount = AtomicInteger(0)
    val retryCount = AtomicInteger(0)
}

private data class SubtitleBuildResult(
    val files: List<SubtitleExporter.ExportFile>,
    val translationStats: TranslationTaskStats?,
)

class PipelineViewModel : ViewModel() {

    private val _tooling = MutableStateFlow(ToolingSettingsStore.loadOrDefault())
    val tooling: StateFlow<ToolingSettings> = _tooling.asStateFlow()

    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()

    private val _modelDownload = MutableStateFlow(ModelDownloadUiState())
    val modelDownload: StateFlow<ModelDownloadUiState> = _modelDownload.asStateFlow()

    private val fileQueue = ArrayDeque<Pair<String, File>>()
    private var pipelineJob: Job? = null
    private var currentRunningTaskId: String? = null
    private var modelDownloadJob: Job? = null

    private fun transcriptionCacheKey(file: File, cfg: ToolingSettings): TranscriptionCacheKeyDto {
        val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return TranscriptionCacheKeyDto(
            fileKey = path,
            fileSize = file.length(),
            fileLastModified = file.lastModified(),
            whisperModel = cfg.whisperModel.trim(),
            whisperLanguage = cfg.whisperLanguage.trim().lowercase().ifEmpty { "auto" },
            whisperVadEnabled = cfg.whisperVadEnabled,
            whisperThreadCount = WhisperCliArgs.threadCount(),
        )
    }

    fun clearTranscriptionCache() {
        TranscriptionCacheStore.clearAll()
    }

    fun updateTooling(transform: (ToolingSettings) -> ToolingSettings) {
        _tooling.update(transform)
        ToolingSettingsStore.save(_tooling.value)
    }

    fun downloadWhisperModel(option: WhisperModelOption, forceRedownload: Boolean) {
        if (_modelDownload.value.active) return
        _modelDownload.value = ModelDownloadUiState(
            active = true,
            fileName = option.fileName,
            message = if (forceRedownload) {
                JvmResourceStrings.text(Res.string.msg_download_reconnecting)
            } else {
                JvmResourceStrings.text(Res.string.msg_download_connecting)
            },
        )
        modelDownloadJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = WhisperModelDownloader.downloadIfNeeded(
                    option = option,
                    force = forceRedownload,
                    onProgress = { received, total ->
                        val p = if (total != null && total > 0) {
                            (received.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        _modelDownload.value = ModelDownloadUiState(
                            active = true,
                            fileName = option.fileName,
                            progress = p,
                            receivedBytes = received,
                            totalBytes = total,
                            message = received.toReadableByteSize() +
                                    if (total != null && total > 0) " / ${total.toReadableByteSize()}" else "",
                        )
                    },
                )
                updateTooling { it.copy(whisperModel = result.file.absolutePath) }
                _modelDownload.value = ModelDownloadUiState(
                    active = false,
                    fileName = option.fileName,
                    progress = 1f,
                    message = if (result.skipped) {
                        JvmResourceStrings.text(Res.string.msg_download_skipped_local)
                    } else {
                        JvmResourceStrings.text(Res.string.msg_download_complete)
                    },
                    skippedExisting = result.skipped,
                )
            } catch (e: CancellationException) {
                _modelDownload.value = ModelDownloadUiState()
                throw e
            } catch (e: Exception) {
                _modelDownload.value = ModelDownloadUiState(
                    active = false,
                    fileName = option.fileName,
                    error = e.message ?: e.toString(),
                )
            } finally {
                modelDownloadJob = null
            }
        }
    }

    fun cancelModelDownload() {
        if (!_modelDownload.value.active) return
        modelDownloadJob?.cancel()
    }

    fun enqueuePipeline(videoFile: File): String? {
        if (_modelDownload.value.active) {
            return JvmResourceStrings.text(Res.string.err_queue_while_downloading)
        }
        if (!videoFile.isFile) {
            return JvmResourceStrings.text(Res.string.err_file_read, videoFile.path)
        }
        val cfg = _tooling.value
        if (cfg.whisperModel.isBlank()) {
            return JvmResourceStrings.text(Res.string.err_whisper_model_missing)
        }
        when (cfg.translationEngine) {
            TranslationEngine.APPLE -> {
                if (!OsUtils.isMacOs()) {
                    return JvmResourceStrings.text(Res.string.err_apple_translate_macos_only)
                }
                if (AppleTranslateBinary.resolvePath(cfg.appleTranslateBinary) == null) {
                    return JvmResourceStrings.text(Res.string.err_apple_translate_missing)
                }
            }
            TranslationEngine.GOOGLE -> {
                if (cfg.googleApiKey.isBlank()) {
                    return JvmResourceStrings.text(Res.string.err_google_key)
                }
            }
            TranslationEngine.DEEPL -> {
                if (cfg.deeplApiKey.isBlank()) return JvmResourceStrings.text(Res.string.err_deepl_key)
            }
            TranslationEngine.OPENAI -> {
                if (cfg.openAiKey.isBlank()) return JvmResourceStrings.text(Res.string.err_openai_key)
            }
        }
        val id = UUID.randomUUID().toString()
        _tasks.update {
            it + TaskRecord(
                id = id,
                fileName = videoFile.name,
                sourcePath = videoFile.absolutePath,
                phase = PipelinePhase.Queued,
                message = JvmResourceStrings.text(Res.string.phase_queued),
            )
        }
        fileQueue.addLast(id to videoFile)
        startNextIfIdle()
        return null
    }

    fun retryTask(id: String): String? {
        return try {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return null
            val src = task.sourcePath?.takeIf { it.isNotBlank() } ?: return JvmResourceStrings.text(
                Res.string.err_file_read,
                task.fileName,
            )
            val file = File(src)
            if (!file.isFile) {
                return JvmResourceStrings.text(Res.string.err_file_read, src)
            }
            val samePath = _tasks.value.filter { it.sourcePath == src }
            for (t in samePath) {
                fileQueue.removeAll { it.first == t.id }
                if (currentRunningTaskId == t.id) {
                    pipelineJob?.cancel()
                }
            }
            _tasks.update { list -> list.filterNot { it.sourcePath == src } }
            enqueuePipeline(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JvmResourceStrings.text(Res.string.err_retry_failed, e.message ?: e.toString())
        }
    }

    fun cancelTask(id: String) {
        fileQueue.removeAll { it.first == id }
        if (currentRunningTaskId == id) {
            pipelineJob?.cancel()
        } else {
            updateTask(id) {
                if (it.phase == PipelinePhase.Queued) {
                    it.copy(
                        phase = PipelinePhase.Cancelled,
                        message = JvmResourceStrings.text(Res.string.phase_cancelled),
                    )
                } else {
                    it
                }
            }
        }
    }

    fun deleteTask(id: String) {
        fileQueue.removeAll { it.first == id }
        if (currentRunningTaskId == id) {
            pipelineJob?.cancel()
        }
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun startAllTasks(): String? {
        val alreadyQueued = fileQueue.map { it.first }.toSet()
        val restartable = _tasks.value.filter {
            it.phase in setOf(PipelinePhase.Queued, PipelinePhase.Cancelled, PipelinePhase.Failed)
                    && !it.sourcePath.isNullOrBlank()
                    && it.id !in alreadyQueued
        }
        var missingCount = 0
        for (task in restartable) {
            val src = task.sourcePath ?: continue
            val file = File(src)
            if (!file.isFile) {
                missingCount++
                updateTask(task.id) {
                    it.copy(
                        phase = PipelinePhase.Failed,
                        message = "",
                        progress = 0f,
                        error = JvmResourceStrings.text(Res.string.err_file_read, src),
                        translationStats = null,
                    )
                }
                continue
            }
            updateTask(task.id) {
                it.copy(phase = PipelinePhase.Queued, progress = 0f, message = "", error = null, translationStats = null)
            }
            fileQueue.addLast(task.id to file)
        }
        startNextIfIdle()
        return if (missingCount > 0) {
            JvmResourceStrings.text(Res.string.warn_start_all_missing_files, missingCount)
        } else {
            null
        }
    }

    fun pauseAllTasks() {
        fileQueue.clear()
        _tasks.update { list ->
            list.map { task ->
                if (task.phase.isCancellableByStopAll()) {
                    task.copy(
                        phase = PipelinePhase.Cancelled,
                        message = JvmResourceStrings.text(Res.string.phase_cancelled),
                        progress = 0f,
                    )
                } else {
                    task
                }
            }
        }
        pipelineJob?.cancel()
    }

    fun deleteAllTasks() {
        fileQueue.clear()
        pipelineJob?.cancel()
        _tasks.update { emptyList() }
    }

    private fun startNextIfIdle() {
        if (pipelineJob?.isActive == true) return
        val next = fileQueue.removeFirstOrNull() ?: return
        val (id, file) = next
        pipelineJob = viewModelScope.launch(Dispatchers.Default) {
            val myJob = coroutineContext[Job]!!
            try {
                runPipelineInternal(id, file)
            } catch (e: CancellationException) {
                updateTask(id) {
                    it.copy(
                        phase = PipelinePhase.Cancelled,
                        message = JvmResourceStrings.text(Res.string.phase_cancelled),
                        progress = 0f,
                    )
                }
                throw e
            } finally {
                if (currentRunningTaskId == id) currentRunningTaskId = null
                if (pipelineJob === myJob) {
                    pipelineJob = null
                    startNextIfIdle()
                }
            }
        }
    }

    private fun updateTask(id: String, transform: (TaskRecord) -> TaskRecord) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 任务已为 Cancelled 时不再被管道进度覆盖（例如全部停止后子进程尚未退出） */
    private fun updateTaskFromPipeline(id: String, transform: (TaskRecord) -> TaskRecord) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id != id) return@map task
                if (task.phase == PipelinePhase.Cancelled) return@map task
                transform(task)
            }
        }
    }

    private suspend fun runPipelineInternal(id: String, file: File) {
        currentRunningTaskId = id
        val cfg = _tooling.value
        var workDir: java.nio.file.Path? = null
        var recognitionDurationMs = 0L
        try {
            val cacheKey = transcriptionCacheKey(file, cfg)
            val cachedDoc = withContext(Dispatchers.IO) { TranscriptionCacheStore.get(cacheKey) }
            val whisperDoc = cachedDoc?.let { hit ->
                updateTaskFromPipeline(id) {
                    it.copy(
                        phase = PipelinePhase.Translating,
                        message = JvmResourceStrings.text(Res.string.msg_transcription_cache_hit),
                        progress = 0.55f,
                    )
                }
                hit
            } ?: run {
                val recStartMs = System.currentTimeMillis()
                updateTaskFromPipeline(id) {
                    it.copy(
                        phase = PipelinePhase.Extracting,
                        message = JvmResourceStrings.text(Res.string.msg_extract_audio),
                        progress = 0.05f,
                        error = null,
                    )
                }
                val doc = run {
                    val ffmpegResolved = BundledNativeTools.resolveFfmpegPath()
                    val whisperResolved = BundledNativeTools.resolveWhisperBinaryPath()
                    val tmpDir = Files.createTempDirectory("whisperit_")
                    workDir = tmpDir
                    val wavPath = tmpDir.resolve("audio.wav")
                    val extractCmd = listOf(
                        ffmpegResolved, "-y", "-i", file.absolutePath,
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                        wavPath.toString(),
                    )
                    val extractCode = ProcessRunner.run(
                        extractCmd,
                        onStdoutLine = { line ->
                            updateTaskFromPipeline(id) { s ->
                                s.copy(
                                    message = JvmResourceStrings.text(
                                        Res.string.msg_processing_line,
                                        line.take(120),
                                    ),
                                )
                            }
                        },
                    )
                    if (extractCode != 0) {
                        error(JvmResourceStrings.text(Res.string.err_audio_extract, extractCode))
                    }
                    updateTaskFromPipeline(id) {
                        it.copy(
                            phase = PipelinePhase.Transcribing,
                            message = JvmResourceStrings.text(Res.string.msg_transcribing),
                            progress = 0.35f,
                        )
                    }
                    if (cfg.whisperVadEnabled) {
                        updateTaskFromPipeline(id) {
                            it.copy(message = JvmResourceStrings.text(Res.string.msg_vad_downloading))
                        }
                        try {
                            WhisperVadModel.ensureDownloaded()
                        } catch (e: Exception) {
                            error(JvmResourceStrings.text(Res.string.err_vad_download, e.message ?: e.toString()))
                        }
                    }
                    val outBase = tmpDir.resolve("whisper_out").toAbsolutePath().toString()
                    updateTaskFromPipeline(id) {
                        it.copy(
                            message = JvmResourceStrings.text(Res.string.msg_transcribing),
                            progress = 0.35f,
                        )
                    }
                    val whisperCode = runWhisperCli(
                        id, whisperResolved, cfg,
                        wavPath.toString(), outBase,
                        useVad = cfg.whisperVadEnabled,
                    )
                    if (whisperCode != 0) {
                        error(JvmResourceStrings.text(Res.string.err_whisper, whisperCode))
                    }
                    val jsonText = withContext(Dispatchers.IO) {
                        Files.readString(java.nio.file.Paths.get("$outBase.json"))
                    }
                    WhisperJsonParser.parseResult(jsonText)
                }
                recognitionDurationMs = System.currentTimeMillis() - recStartMs
                withContext(Dispatchers.IO) { TranscriptionCacheStore.put(cacheKey, doc) }
                doc
            }

            val buildResult = buildSubtitleExportFiles(id, cfg, whisperDoc, recognitionDurationMs)
            val outputPaths = withContext(Dispatchers.IO) {
                buildResult.files.map { payload ->
                    val outFile = file.subtitleOutputFile(cfg.exportFormat, payload.nameSuffix)
                    Files.writeString(outFile.toPath(), payload.body)
                    outFile.absolutePath
                }
            }
            updateTaskFromPipeline(id) {
                it.copy(
                    phase = PipelinePhase.Done,
                    message = JvmResourceStrings.text(Res.string.phase_done_short),
                    progress = 1f,
                    outputPath = outputPaths.firstOrNull(),
                    error = null,
                    translationStats = buildResult.translationStats,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            updateTaskFromPipeline(id) {
                it.copy(
                    phase = PipelinePhase.Failed,
                    message = "",
                    progress = 0f,
                    error = e.message ?: e.toString(),
                    translationStats = null,
                )
            }
        } finally {
            workDir?.let { dir ->
                withContext(Dispatchers.IO) {
                    runCatching { dir.toFile().deleteRecursively() }
                }
            }
        }
    }

    private suspend fun buildSubtitleExportFiles(
        id: String,
        cfg: ToolingSettings,
        whisperDoc: WhisperParseResult,
        recognitionDurationMs: Long,
    ): SubtitleBuildResult {
        val segments = whisperDoc.segments
        if (segments.isEmpty()) {
            error(JvmResourceStrings.text(Res.string.err_no_segments))
        }
        val requestedOutputs = cfg.subtitleOutputs.toSet()
        val effectiveOutputs = if (requestedOutputs.isEmpty()) setOf("source") else requestedOutputs
        val needsTranslation = effectiveOutputs.contains("target") || effectiveOutputs.contains("bilingual_single")
        val lineCount = segments.size
        val translated: List<String>
        val stats: TranslationTaskStats?
        if (!needsTranslation) {
            updateTaskFromPipeline(id) {
                it.copy(
                    phase = PipelinePhase.Translating,
                    message = JvmResourceStrings.text(Res.string.msg_skip_translate),
                    progress = 0.8f,
                )
            }
            translated = segments.map { it.text }
            stats = TranslationTaskStats(
                recognitionDurationMs = recognitionDurationMs,
                translationDurationMs = 0L,
                lineCount = lineCount,
                requestCount = 0,
                retryCount = 0,
                skipped = true,
            )
        } else {
            val metrics = TranslationMetrics()
            val t0 = System.currentTimeMillis()
            translated = translateSegments(id, cfg, whisperDoc, segments, metrics)
            val translationDurationMs = System.currentTimeMillis() - t0
            stats = TranslationTaskStats(
                recognitionDurationMs = recognitionDurationMs,
                translationDurationMs = translationDurationMs,
                lineCount = lineCount,
                requestCount = metrics.requestCount.get(),
                retryCount = metrics.retryCount.get(),
                skipped = false,
            )
        }
        val files = SubtitleExporter.exportFiles(
            segments = segments,
            translations = translated,
            format = cfg.exportFormat,
            subtitleOutputs = effectiveOutputs,
            targetSuffix = TargetLanguageMapper.subtitleTargetSuffix(cfg.targetLanguage),
        )
        return SubtitleBuildResult(files = files, translationStats = stats)
    }

    private suspend fun translateSegments(
        id: String,
        cfg: ToolingSettings,
        whisperDoc: WhisperParseResult,
        segments: List<TranscriptSegment>,
        metrics: TranslationMetrics,
    ): List<String> {
        val engine = cfg.translationEngine
        val appleSource = TargetLanguageMapper.whisperLanguageToAppleSource(whisperDoc.whisperLanguage)
        val appleTarget = TargetLanguageMapper.toAppleLocale(cfg.targetLanguage, forTarget = true)
        val googleTarget = TargetLanguageMapper.toGoogleTargetCode(cfg.targetLanguage)
        val deeplTarget = TargetLanguageMapper.toDeepLTargetCode(cfg.targetLanguage)
        updateTaskFromPipeline(id) {
            it.copy(
                phase = PipelinePhase.Translating,
                message = when (engine) {
                    TranslationEngine.APPLE ->
                        JvmResourceStrings.text(Res.string.msg_translate_apple_running)
                    TranslationEngine.GOOGLE ->
                        JvmResourceStrings.text(Res.string.msg_translate_google_running)
                    TranslationEngine.DEEPL ->
                        JvmResourceStrings.text(Res.string.msg_translate_deepl_running)
                    TranslationEngine.OPENAI ->
                        JvmResourceStrings.text(Res.string.msg_translate_openai_running)
                },
                progress = 0.65f,
            )
        }
        return when (engine) {
            TranslationEngine.APPLE -> {
                if (!OsUtils.isMacOs()) {
                    error(JvmResourceStrings.text(Res.string.err_apple_translate_macos_only))
                }
                val appleBin = AppleTranslateBinary.resolvePath(cfg.appleTranslateBinary)
                    ?: error(JvmResourceStrings.text(Res.string.err_apple_binary))
                val translator = AppleTranslator(binaryPath = appleBin)
                translateByChunk(
                    id = id,
                    segments = segments,
                    chunkSize = 6,
                    metrics = metrics,
                    translateChunk = { texts ->
                        try {
                            translator.translateBatch(
                                texts = texts,
                                sourceAppleLocale = appleSource,
                                targetAppleLocale = appleTarget,
                            )
                        } catch (e: Exception) {
                            val hint = JvmResourceStrings.text(
                                Res.string.hint_apple_translate_pair,
                                appleSource,
                                appleTarget,
                                whisperDoc.whisperLanguage ?: "?",
                            )
                            error((e.message ?: e.toString()) + "\n" + hint)
                        }
                    },
                    progressMessage = { done, total ->
                        JvmResourceStrings.text(Res.string.msg_translate_apple_progress, done, total)
                    },
                )
            }

            TranslationEngine.GOOGLE -> {
                val translator = GoogleTranslator(apiKey = cfg.googleApiKey)
                translateByChunk(
                    id = id,
                    segments = segments,
                    chunkSize = 100,
                    metrics = metrics,
                    translateChunk = { texts ->
                        translator.translateBatch(
                            texts = texts,
                            targetGoogleCode = googleTarget,
                        )
                    },
                    progressMessage = { done, total ->
                        JvmResourceStrings.text(Res.string.msg_translate_google_progress, done, total)
                    },
                    concurrency = TranslationConcurrency,
                )
            }

            TranslationEngine.DEEPL -> {
                val translator = DeepLTranslator(
                    authKey = cfg.deeplApiKey,
                    useFreeApiHost = cfg.deeplUseFreeApi,
                )
                translateByChunk(
                    id = id,
                    segments = segments,
                    chunkSize = TranslationChunkSizeOpenAiStyle,
                    metrics = metrics,
                    translateChunk = { texts ->
                        translator.translateBatch(
                            texts = texts,
                            targetDeepL = deeplTarget,
                        )
                    },
                    progressMessage = { done, total ->
                        JvmResourceStrings.text(Res.string.msg_translate_deepl_progress, done, total)
                    },
                    concurrency = TranslationConcurrency,
                )
            }

            TranslationEngine.OPENAI -> {
                val translator = OpenAiTranslator(
                    apiKey = cfg.openAiKey,
                    model = cfg.openAiModel,
                    baseUrl = cfg.openAiBaseUrl,
                )
                translateByChunk(
                    id = id,
                    segments = segments,
                    chunkSize = TranslationChunkSizeOpenAiStyle,
                    metrics = metrics,
                    translateChunk = { texts ->
                        translator.translateBatch(
                            texts = texts,
                            targetLanguage = cfg.targetLanguage,
                        )
                    },
                    progressMessage = { done, total ->
                        JvmResourceStrings.text(Res.string.msg_translate_openai_progress, done, total)
                    },
                    concurrency = TranslationConcurrencyOpenAi,
                )
            }
        }
    }

    private suspend fun translateByChunk(
        id: String,
        segments: List<TranscriptSegment>,
        chunkSize: Int,
        metrics: TranslationMetrics,
        translateChunk: suspend (texts: List<String>) -> List<String>,
        progressMessage: (done: Int, total: Int) -> String,
        concurrency: Int = 1,
    ): List<String> {
        val chunks = segments.chunked(chunkSize)
        val totalSegments = segments.size
        val translatedCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun buildPart(chunk: List<TranscriptSegment>): Triple<List<String>, List<Int>, List<String>> {
            val sourceTexts = chunk.map { it.text }
            val missingTexts = mutableListOf<String>()
            val missingIndexes = mutableListOf<Int>()
            sourceTexts.forEachIndexed { srcIdx, src ->
                if (!translatedCache.containsKey(src)) {
                    missingTexts.add(src)
                    missingIndexes.add(srcIdx)
                }
            }
            return Triple(sourceTexts, missingIndexes, missingTexts)
        }

        val results = if (concurrency <= 1) {
            chunks.mapIndexed { idx, chunk ->
                updateTaskFromPipeline(id) {
                    it.copy(
                        progress = (0.65f + 0.3f * idx / chunks.size.coerceAtLeast(1)).coerceIn(0f, 0.98f),
                        message = progressMessage(doneCount.get(), totalSegments),
                    )
                }
                val (sourceTexts, missingIndexes, missingTexts) = buildPart(chunk)
                val part = MutableList(sourceTexts.size) { i -> translatedCache[sourceTexts[i]] ?: sourceTexts[i] }
                if (missingTexts.isNotEmpty()) {
                    val missingTranslated = translateWithRetry(missingTexts, translateChunk, metrics = metrics)
                    missingIndexes.forEachIndexed { missIdx, srcIdx ->
                        val translatedText = missingTranslated.getOrNull(missIdx) ?: return@forEachIndexed
                        translatedCache[sourceTexts[srcIdx]] = translatedText
                        part[srcIdx] = translatedText
                    }
                }
                doneCount.addAndGet(chunk.size)
                part
            }
        } else {
            val semaphore = Semaphore(concurrency)
            coroutineScope {
                chunks.mapIndexed { idx, chunk ->
                    async {
                        semaphore.withPermit {
                            val (sourceTexts, missingIndexes, missingTexts) = buildPart(chunk)
                            val part = MutableList(sourceTexts.size) { i -> translatedCache[sourceTexts[i]] ?: sourceTexts[i] }
                            if (missingTexts.isNotEmpty()) {
                                val missingTranslated = translateWithRetry(missingTexts, translateChunk, metrics = metrics)
                                missingIndexes.forEachIndexed { missIdx, srcIdx ->
                                    val translatedText = missingTranslated.getOrNull(missIdx) ?: return@forEachIndexed
                                    translatedCache[sourceTexts[srcIdx]] = translatedText
                                    part[srcIdx] = translatedText
                                }
                            }
                            val done = doneCount.addAndGet(chunk.size)
                            updateTaskFromPipeline(id) {
                                it.copy(
                                    progress = (0.65f + 0.3f * done / totalSegments).coerceIn(0f, 0.98f),
                                    message = progressMessage(done, totalSegments),
                                )
                            }
                            part
                        }
                    }
                }.awaitAll()
            }
        }

        updateTaskFromPipeline(id) {
            it.copy(
                progress = 0.95f,
                message = progressMessage(totalSegments, totalSegments),
            )
        }
        return results.flatten()
    }

    private suspend fun translateWithRetry(
        sourceTexts: List<String>,
        translateChunk: suspend (texts: List<String>) -> List<String>,
        maxRetries: Int = 1, // 【修改】重试次数降为 1，不浪费时间死磕
        splitDepth: Int = 0,
        metrics: TranslationMetrics,
    ): List<String> {
        val uniqueTexts = LinkedHashMap<String, MutableList<Int>>()
        sourceTexts.forEachIndexed { i, text ->
            uniqueTexts.getOrPut(text) { mutableListOf() }.add(i)
        }
        val uniqueKeys = uniqueTexts.keys.toList()

        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            try {
                if (attempt > 0) {
                    delay(1500L) // 【修改】缩短等待时间
                    metrics.retryCount.incrementAndGet()
                }
                metrics.requestCount.incrementAndGet()

                // 【修改】降低死磕超时时间，最长不超过 35 秒
                val timeoutMs = if (uniqueKeys.size > 8) 35_000L else 20_000L
                val uniqueTranslated = withTimeout(timeoutMs) {
                    translateChunk(uniqueKeys)
                }

                if (uniqueTranslated.size != uniqueKeys.size) {
                    throw IllegalStateException("模型返回条数不一致 (请求 ${uniqueKeys.size} 条，返回 ${uniqueTranslated.size} 条)")
                }

                val part = MutableList(sourceTexts.size) { sourceTexts[it] }
                uniqueTexts.entries.forEachIndexed { uIdx, (_, indexes) ->
                    val translatedText = uniqueTranslated[uIdx] // 前面已校验过 size 一致性
                    indexes.forEach { srcIdx -> part[srcIdx] = translatedText }
                }
                return part
            } catch (e: TimeoutCancellationException) {
                lastError = e
            } catch (e: CancellationException) {
                throw e // 协程被外部取消时必须向上抛出
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 【核心修改】只允许最浅的一层拆批（splitDepth < 1）。再失败就直接降级原文，不陷在死循环里。
        if (sourceTexts.size > 1 && splitDepth < 1) {
            val mid = sourceTexts.size / 2
            val leftPart = sourceTexts.subList(0, mid)
            val rightPart = sourceTexts.subList(mid, sourceTexts.size)

            // 依然保持顺序执行，避免击穿代理并发
            val left = translateWithRetry(leftPart, translateChunk, maxRetries = 0, splitDepth = splitDepth + 1, metrics = metrics)
            val right = translateWithRetry(rightPart, translateChunk, maxRetries = 0, splitDepth = splitDepth + 1, metrics = metrics)

            return left + right
        }

        System.err.println(
            "[whisperit] 翻译直接放弃降级为原文（深度 ${splitDepth}）：${lastError?.message ?: "unknown"}",
        )
        return sourceTexts
    }

    private suspend fun runWhisperCli(
        id: String,
        whisperResolved: String,
        cfg: ToolingSettings,
        wavPath: String,
        outBase: String,
        useVad: Boolean,
    ): Int {
        val lang = cfg.whisperLanguage.trim().lowercase().ifEmpty { "auto" }
        val cmd = buildList {
            add(whisperResolved)
            add("-t")
            add(WhisperCliArgs.threadCount().toString())
            add("-m")
            add(cfg.whisperModel)
            add("-l")
            add(lang)
            add("-mc")
            add("0")
            add("-f")
            add(wavPath)
            add("-oj")
            add("-of")
            add(outBase)
            if (useVad) {
                add("--vad")
                add("-vm")
                add(WhisperVadModel.modelFile().absolutePath)
            }
        }
        return ProcessRunner.run(
            cmd,
            onStdoutLine = { line ->
                updateTaskFromPipeline(id) { s ->
                    s.copy(
                        message = JvmResourceStrings.text(
                            Res.string.msg_whisper_line,
                            line.take(120),
                        ),
                    )
                }
            },
        )
    }
}