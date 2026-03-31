package com.danteandroid.whisperit.translate

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationEngine {
    APPLE,
    GOOGLE,
    DEEPL,
    OPENAI,
}
