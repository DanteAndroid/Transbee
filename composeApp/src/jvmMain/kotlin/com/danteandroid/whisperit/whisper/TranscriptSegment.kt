package com.danteandroid.whisperit.whisper

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String,
)
