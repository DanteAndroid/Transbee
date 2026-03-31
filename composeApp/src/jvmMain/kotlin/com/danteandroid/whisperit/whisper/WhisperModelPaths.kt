package com.danteandroid.whisperit.whisper

import java.io.File

object WhisperModelPaths {
    fun modelsDirectory(): File {
        val dir = File(System.getProperty("user.home"), ".whisperit/models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
