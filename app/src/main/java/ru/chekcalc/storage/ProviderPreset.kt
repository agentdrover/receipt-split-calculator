package ru.chekcalc.storage

/**
 * Провайдеры, между которыми переключается пользователь.
 *
 * У каждого свой адрес, модель по умолчанию и отдельный ключ: ключ DeepSeek
 * не должен уходить в OpenRouter и наоборот.
 */
enum class ProviderPreset(
    val id: String,
    val title: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val modelHint: String? = null,
) {
    DEEPSEEK(
        id = "deepseek",
        title = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        modelHint = "deepseek-flash читает изображения",
    ),
    OPENROUTER(
        id = "openrouter",
        title = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemini-2.5-flash",
        modelHint = "модель в формате vendor/model",
    ),
    CUSTOM(
        id = "custom",
        title = "Свой адрес",
        defaultBaseUrl = "",
        defaultModel = "",
        modelHint = "любой OpenAI-совместимый сервис",
    ),
    ;

    companion object {
        val DEFAULT: ProviderPreset = DEEPSEEK

        fun byId(id: String?): ProviderPreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
