package com.sahayak.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelsTest {
    @Test fun ranksFlashFirstAndSkipsOddModels() {
        val names = listOf(
            "models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-2.5-pro",
            "models/gemini-3-flash-preview", "models/gemini-2.5-flash-image", "models/gemini-embedding-001",
            "models/gemma-3-27b-it", "models/gemini-2.5-flash-preview-tts", "models/gemini-flash-latest",
        )
        assertEquals(listOf("gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"), GeminiModels.rank(names))
    }

    @Test fun keepsLatestWhenNothingElse() {
        assertEquals(listOf("gemini-flash-latest"), GeminiModels.rank(listOf("models/gemini-flash-latest")))
    }

    @Test fun labels() {
        assertEquals("2.5 Flash Lite", GeminiModels.label("gemini-2.5-flash-lite"))
    }
}
