package com.danteandroid.transbee.settings

import com.danteandroid.transbee.process.PipelinePhase
import com.danteandroid.transbee.ui.TaskRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object TaskRecordStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun tasksFile(): File = File(ToolingSettingsStore.appDataDir(), "transbee_tasks.json")

    fun loadTasks(): List<TaskRecord> {
        val f = tasksFile()
        if (!f.isFile) return emptyList()
        return try {
            val tasks = json.decodeFromString<List<TaskRecord>>(f.readText())
            // Cleanup: reset in-progress tasks to Cancelled on startup
            tasks.map { task ->
                if (task.phase == PipelinePhase.Queued || 
                    task.phase == PipelinePhase.Extracting || 
                    task.phase == PipelinePhase.Transcribing || 
                    task.phase == PipelinePhase.Translating) {
                    task.copy(
                        phase = PipelinePhase.Cancelled,
                        message = "App restarted",
                        progress = 0f
                    )
                } else {
                    task
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveTasks(tasks: List<TaskRecord>) {
        runCatching {
            val dir = ToolingSettingsStore.appDataDir()
            if (!dir.exists()) dir.mkdirs()
            tasksFile().writeText(json.encodeToString(tasks))
        }.onFailure { it.printStackTrace() }
    }
}
