package com.danteandroid.whisperit.whisper

import kotlinx.serialization.Serializable

@Serializable
data class WhisperParseResult(
    val segments: List<TranscriptSegment>,
    val whisperLanguage: String?,
)
