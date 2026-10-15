package com.example.voicechanger

data class Preset(
    val name: String,
    val semitones: Float,
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val robot: Boolean = false,
    val presenceDb: Float = 0f,
    val match: Boolean = false
)

object Presets {
    val ALL = listOf(
        Preset("★ Default Voice (from my mp3)", 0f, match = true),
        Preset("Original (no change)", 0f),
        Preset("Male - Deep", -6f, bassDb = 6f, trebleDb = -3f),
        Preset("Male - Normal", -4f, bassDb = 4f, trebleDb = -2f),
        Preset("Male - Young", -2.5f, bassDb = 2f, trebleDb = -1f),
        Preset("Male - Old", -3.5f, bassDb = 3f, trebleDb = -6f),
        Preset("Male from Female - Light", -7f, bassDb = 5f, trebleDb = -4f, presenceDb = -2f),
        Preset("Male from Female - Normal", -9f, bassDb = 6f, trebleDb = -5f, presenceDb = -3f),
        Preset("Male from Female - Deep", -11f, bassDb = 8f, trebleDb = -6f, presenceDb = -4f),
        Preset("Female - Normal", 4f, bassDb = -3f, trebleDb = 2f),
        Preset("Female - Soft", 5f, bassDb = -4f, trebleDb = 1f),
        Preset("Female - High", 7f, bassDb = -4f, trebleDb = 3f),
        Preset("Child", 9f, bassDb = -5f, trebleDb = 3f),
        Preset("Robot", -2f, robot = true)
    )
}
