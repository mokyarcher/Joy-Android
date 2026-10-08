package com.joy.android.data

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val notes: String = "",
    val models: List<String> = emptyList(),
)

object ProviderPresets {
    val SENSENOVA_MODELS = listOf(
        "deepseek-v4-flash",
        "sensenova-6.8-flash-lite",
        "deepseek-flash",
        "glm-5.2",
        "kimi-k3",
        "deepseek-v4-pro",
        "deepseek-v4.1-flash",
    )

    /**
     * 支持图文的视觉模型。排序：更轻/更快靠前。
     * 识图慢时优先试 6.7-flash-lite；国内到 OpenAI 可能更慢。
     */
    val SENSENOVA_VISION_MODELS = listOf(
        "sensenova-6.7-flash-lite",
        "sensenova-6.8-flash-lite",
    )

    val OPENAI_VISION_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4.1-mini",
        "gpt-4o",
    )

    /** 智谱免费档视觉；高峰可能 429 */
    val ZHIPU_VISION_MODELS = listOf(
        "glm-4.6v-flash",
        "glm-4.6v-flashx",
        "glm-4.6v",
    )

    val ZHIPU_MODELS = listOf(
        "glm-4.7-flash",
        "glm-4.5-flash",
        "glm-4-flash",
        "glm-4.6v-flash",
    )

    /** DeepSeek 官方视觉（OpenAI 兼容图文） */
    val DEEPSEEK_VISION_MODELS = listOf(
        "deepseek-v4-flash-vision-exp",
    )

    val DEEPSEEK_MODELS = listOf(
        "deepseek-chat",
        "deepseek-reasoner",
        "deepseek-v4-flash-vision-exp",
    )

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "sensenova",
            name = "商汤 SenseNova",
            baseUrl = "https://token.sensenova.cn/v1",
            defaultModel = "deepseek-v4-flash",
            notes = "与电脑端 Joy 默认一致；Free 推荐 deepseek-v4-flash / sensenova-6.8-flash-lite。",
            models = SENSENOVA_MODELS,
        ),
        ProviderPreset(
            id = "zhipu",
            name = "智谱 BigModel",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4.7-flash",
            notes = "免费档：回复 glm-4.7-flash，视觉 glm-4.6v-flash（需在 open.bigmodel.cn 建 Key）。",
            models = ZHIPU_MODELS,
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            notes = "视觉用 deepseek-v4-flash-vision-exp（平台余额扣费）。",
            models = DEEPSEEK_MODELS,
        ),
        ProviderPreset(
            id = "kimi",
            name = "Kimi / Moonshot",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
        ),
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
        ),
        ProviderPreset(
            id = "custom",
            name = "自定义",
            baseUrl = "",
            defaultModel = "",
            notes = "自行填写 Base URL / Model",
        ),
    )

    fun byId(id: String): ProviderPreset =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == "sensenova" }

    fun matchPresetId(baseUrl: String, model: String): String {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return "custom"
        val hit = ALL.firstOrNull {
            it.id != "custom" && it.baseUrl.trimEnd('/') == url
        }
        return hit?.id ?: "custom"
    }

    fun visionModelsForPreset(presetId: String): List<String> = when (presetId) {
        "sensenova" -> SENSENOVA_VISION_MODELS
        "zhipu" -> ZHIPU_VISION_MODELS
        "deepseek" -> DEEPSEEK_VISION_MODELS
        "openai" -> OPENAI_VISION_MODELS
        else -> emptyList()
    }

    /** 有视觉模型的服务商 + 自定义，供「视觉」区单独选 */
    fun visionCapablePresets(): List<ProviderPreset> =
        ALL.filter { it.id == "custom" || visionModelsForPreset(it.id).isNotEmpty() }

    fun defaultVisionModelForPreset(presetId: String): String =
        visionModelsForPreset(presetId).firstOrNull()
            ?: JoySettings.DEFAULT_VISION_MODEL
}
