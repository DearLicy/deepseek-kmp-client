package com.deepseek.client.voice

data class VoiceOption(
    val id: String,
    val displayName: String,
    val description: String,
    val gender: VoiceGender,
    val languages: List<String>,
    val isDefault: Boolean,
)

enum class VoiceGender { Female, Male }

/**
 * Public voices exposed by chat-demo. The official app has four dynamic voice
 * cards from a private endpoint, but the pinned demo explicitly enumerates these two for
 * POST /v1/tools/tts in API-key mode.
 */
object PublicVoiceCatalog {
    val voices: List<VoiceOption> = listOf(
        VoiceOption(
            id = "stella",
            displayName = "海星",
            description = "女声",
            gender = VoiceGender.Female,
            languages = emptyList(),
            isDefault = true,
        ),
        VoiceOption(
            id = "tide",
            displayName = "暗潮",
            description = "男声",
            gender = VoiceGender.Male,
            languages = emptyList(),
            isDefault = false,
        ),
    )

    val defaultVoiceId: String = voices.first { it.isDefault }.id
}
