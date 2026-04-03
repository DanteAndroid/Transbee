@file:OptIn(ExperimentalMaterial3Api::class)

package com.danteandroid.transbee.screen

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.stringResource
import transbee.composeapp.generated.resources.*
import transbee.composeapp.generated.resources.Res
import androidx.compose.ui.text.font.FontWeight
import com.danteandroid.transbee.AppLanguage
import com.danteandroid.transbee.AppLocale
import com.danteandroid.transbee.AppTheme
import com.danteandroid.transbee.TransbeeTheme
import com.danteandroid.transbee.ui.PipelineViewModel
import com.danteandroid.transbee.ui.labelRes
import com.danteandroid.transbee.utils.SmokeTestResult
import com.danteandroid.transbee.utils.runServiceSmokeTest
import com.danteandroid.transbee.utils.smokeTestSourceText
import com.danteandroid.transbee.whisper.WhisperModelCatalog
import com.danteandroid.transbee.whisper.isDownloaded
import com.danteandroid.transbee.whisper.modelFile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import transbee.composeapp.generated.resources.dialog_translation_test_elapsed_ms
import transbee.composeapp.generated.resources.dialog_translation_test_hint_tap_run
import transbee.composeapp.generated.resources.dialog_translation_test_running
import transbee.composeapp.generated.resources.dialog_translation_test_title
import transbee.composeapp.generated.resources.subtitle_output_source
import transbee.composeapp.generated.resources.subtitle_output_target

@Composable
fun App() {
    var appLanguage by remember { mutableStateOf(AppLanguage.ZH) }
    val viewModel: PipelineViewModel = viewModel { PipelineViewModel() }
    val tooling by viewModel.tooling.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val modelDl by viewModel.modelDownload.collectAsState()
    val presets = remember { WhisperModelCatalog.presets }
    var selectedPreset by remember {
        mutableStateOf(presets.firstOrNull { it.id == "base" } ?: presets.first())
    }
    var showSetting by remember { mutableStateOf(false) }
    var showTranslationTestDialog by remember { mutableStateOf(false) }
    var serviceTestBusy by remember { mutableStateOf(false) }
    var serviceTestResult by remember { mutableStateOf<SmokeTestResult?>(null) }
    var serviceTestError by remember { mutableStateOf<String?>(null) }
    var serviceTestElapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(presets, tooling.whisperModel) {
        val path = tooling.whisperModel
        if (path.isBlank()) return@LaunchedEffect
        val match = presets.firstOrNull { path.endsWith(it.fileName) }
        if (match != null) selectedPreset = match
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var prevModelDlActive by remember { mutableStateOf(false) }
    LaunchedEffect(modelDl.active) {
        if (prevModelDlActive && !modelDl.active) {
            val text = modelDl.error ?: modelDl.message
            if (text.isNotEmpty()) scope.launch { snackbarHostState.showSnackbar(text) }
        }
        prevModelDlActive = modelDl.active
    }

    var serviceTestInputText by remember { mutableStateOf("") }
    LaunchedEffect(showTranslationTestDialog) {
        if (showTranslationTestDialog) {
            serviceTestResult = null
            serviceTestError = null
            serviceTestElapsedMs = 0L
            serviceTestInputText = smokeTestSourceText(tooling)
        }
    }

    TransbeeTheme(darkTheme = true) {
        val spacing = AppTheme.spacing
        key(appLanguage) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    SettingsPanel(
                        selectedPreset = selectedPreset,
                        modelDl = modelDl,
                        tooling = tooling,
                        viewModel = viewModel,
                        onSelectPreset = { selectedPreset = it },
                        onTranslationEngineKeyHint = { msg ->
                            if (msg != null) scope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                        modifier = Modifier.weight(0.32f).fillMaxHeight(),
                    )
                    VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(0.68f).fillMaxHeight()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(spacing.large),
                        ) {
                            AppTaskScreen(
                                tasks = tasks,
                                onFilesSelected = { files ->
                                    val errs =
                                        viewModel.onFilesSelected(files).filter { it.isNotBlank() }
                                    if (errs.isNotEmpty()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = errs.joinToString("\n"),
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    }
                                },
                                onDeleteTask = viewModel::deleteTask,
                                onRetryTask = { id ->
                                    val err = viewModel.retryTask(id)
                                    if (err != null) scope.launch {
                                        snackbarHostState.showSnackbar(err)
                                    }
                                },
                                onStartAll = {
                                    val err = viewModel.startAllTasks()
                                    if (err != null) scope.launch {
                                        snackbarHostState.showSnackbar(err)
                                    }
                                },
                                onPauseAll = viewModel::pauseAllTasks,
                                onDeleteAll = viewModel::deleteAllTasks,
                                modifier = Modifier.fillMaxSize(),
                            )
                            SnackbarHost(
                                snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        StatusBarRow(
                            selectedPresetId = selectedPreset.id,
                            modelDownload = modelDl,
                            onTranslationTestClick = { showTranslationTestDialog = true },
                            onSettingsClick = { showSetting = true },
                        )
                    }
                }
            }
        }

        if (showSetting) {
            AppSettingDialog(
                tooling = tooling,
                onUpdateTooling = viewModel::updateTooling,
                onDismissRequest = { showSetting = false },
                onLocaleZh = { appLanguage = AppLanguage.ZH; AppLocale.apply(AppLanguage.ZH) },
                onLocaleEn = { appLanguage = AppLanguage.EN; AppLocale.apply(AppLanguage.EN) },
                onClearTranscriptionCache = viewModel::clearTranscriptionCache,
            )
        }

        if (showTranslationTestDialog) {
            val engineName = stringResource(tooling.translationEngine.labelRes)
            TranslationTestDialog(
                engineName = engineName,
                inputText = serviceTestInputText,
                onInputTextChange = { serviceTestInputText = it },
                busy = serviceTestBusy,
                result = serviceTestResult,
                error = serviceTestError,
                elapsedMs = serviceTestElapsedMs,
                onRun = {
                    scope.launch {
                        serviceTestBusy = true
                        serviceTestResult = null
                        serviceTestError = null
                        try {
                            val t0 = System.currentTimeMillis()
                            serviceTestResult = runServiceSmokeTest(tooling, serviceTestInputText)
                            serviceTestElapsedMs = System.currentTimeMillis() - t0
                        } catch (e: Exception) {
                            serviceTestError = e.message ?: e.toString()
                        } finally {
                            serviceTestBusy = false
                        }
                    }
                },
                onDismiss = { if (!serviceTestBusy) showTranslationTestDialog = false },
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    selectedPreset: com.danteandroid.transbee.whisper.WhisperModelOption,
    modelDl: com.danteandroid.transbee.ui.ModelDownloadUiState,
    tooling: com.danteandroid.transbee.settings.ToolingSettings,
    viewModel: PipelineViewModel,
    onSelectPreset: (com.danteandroid.transbee.whisper.WhisperModelOption) -> Unit,
    onTranslationEngineKeyHint: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val scope = rememberCoroutineScope()
    
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.large)
    ) {
        val scrollState = rememberScrollState()
        Column(
            Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(spacing.xxLarge),
        ) {
            SettingsSection(stringResource(Res.string.section_model_settings)) {
                ModelSettingCard(
                    selectedPreset = selectedPreset,
                    modelDownload = modelDl,
                    whisperLanguage = tooling.whisperLanguage,
                    onWhisperLanguageChange = { code -> viewModel.updateTooling { it.copy(whisperLanguage = code) } },
                    whisperVadEnabled = tooling.whisperVadEnabled,
                    onWhisperVadChange = { v -> viewModel.updateTooling { it.copy(whisperVadEnabled = v) } },
                    onSelectModel = { opt ->
                        if (opt.isDownloaded()) {
                            onSelectPreset(opt)
                            viewModel.updateTooling { it.copy(whisperModel = opt.modelFile().absolutePath) }
                        }
                    },
                    onDownloadModel = { viewModel.downloadWhisperModel(it, false) },
                    onStopDownload = viewModel::cancelModelDownload,
                )
            }

            SettingsSection(stringResource(Res.string.section_translation)) {
                TranslationSettingCard(
                    tooling = tooling,
                    onUpdateTooling = viewModel::updateTooling,
                    onTranslationEngineChanged = { eng ->
                        onTranslationEngineKeyHint(viewModel.missingKeyMessageForEngine(eng, tooling))
                    },
                )
            }

            SettingsSection(stringResource(Res.string.section_export)) {
                ExportSettingCard(tooling = tooling, onUpdateTooling = viewModel::updateTooling)
            }
            
            Spacer(Modifier.height(spacing.large))
        }

        Spacer(Modifier.height(spacing.large))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        content()
    }
}

@Composable
private fun InitiateArchitectButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF3F51B5), // Indigo
                            Color(0xFF7E57C2)  // Purple
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(Res.string.action_initiate_architect),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                ),
                color = Color.White
            )
        }
    }
}

@Composable
private fun TranslationTestDialog(
    engineName: String,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    busy: Boolean,
    result: SmokeTestResult?,
    error: String?,
    elapsedMs: Long,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_translation_test_title)) },
        text = {
            Column(
                Modifier.height(400.dp).verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Text(
                    stringResource(Res.string.dialog_translation_test_current_engine, engineName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    stringResource(Res.string.subtitle_output_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = !busy,
                )

                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = spacing.medium, end = spacing.small, top = spacing.small, bottom = spacing.small)
                        ) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            val clipboard = LocalClipboardManager.current
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(error)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (result != null) {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.subtitle_output_target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(result.translated, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(Res.string.dialog_translation_test_elapsed_ms, elapsedMs.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!busy && result == null && error == null) {
                    Text(
                        stringResource(Res.string.dialog_translation_test_hint_tap_run),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.action_close)) }
        },
        confirmButton = {
            Button(onClick = onRun, enabled = !busy && inputText.isNotBlank()) {
                Text(if (busy) "请求中" else stringResource(Res.string.action_run_test))
            }
        },
    )
}

