package com.joy.android.data

data class JoySettings(
    val presetId: String = DEFAULT_PRESET_ID,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** 首页产品模式：回复助手 / 群 Bot（布局与配置分叉） */
    val appMode: AppMode = AppMode.ASSISTANT,
    /** 群 Bot：微信昵称，用于识别 @ */
    val groupBotNickname: String = "",
    val groupBotGender: GroupBotGender = GroupBotGender.UNSPECIFIED,
    val groupBotTemper: GroupBotTemper = GroupBotTemper.MILD,
    val groupBotProfession: String = "",
    /** 群 Bot 人设长文（与助手 systemPrompt 隔离） */
    val groupBotPersona: String = DEFAULT_GROUP_BOT_PERSONA,
    /** 仅被 @ 时响应 */
    val groupBotRequireAt: Boolean = true,
    /** 两次回复最短间隔（秒） */
    val groupBotCooldownSec: Int = 8,
    /** 选图识聊天：默认视觉（群聊角色更稳）；可切本地 OCR */
    val imageCaptureMode: ImageCaptureMode = ImageCaptureMode.VISION,
    /** 视觉通道服务商（可与回复不同） */
    val visionPresetId: String = DEFAULT_VISION_PRESET_ID,
    /** 视觉 Base URL；空则回退到回复的 baseUrl */
    val visionBaseUrl: String = "",
    /** 视觉 API Key；空则回退到回复的 apiKey */
    val visionApiKey: String = "",
    /** 视觉模式专用模型（可与回复模型不同） */
    val visionModel: String = DEFAULT_VISION_MODEL,
    /** 视觉模式下嵌图气泡处理策略 */
    val visionImagePolicy: VisionImagePolicy = VisionImagePolicy.PLACEHOLDER,
    /** 启动应用时自动打开悬浮球（需已授权悬浮窗） */
    val autoStartOverlay: Boolean = false,
    /** 主球环境色 ARGB；默认浅蓝 */
    val overlayCoreColorArgb: Int = DEFAULT_OVERLAY_CORE_COLOR,
    /** 副球环境色 ARGB；0 表示无着色（默认玻璃） */
    val overlaySatelliteColorArgb: Int = DEFAULT_OVERLAY_SATELLITE_COLOR,
) {
    fun resolvedVisionModel(): String =
        visionModel.trim().ifEmpty { DEFAULT_VISION_MODEL }

    fun resolvedVisionBaseUrl(): String =
        visionBaseUrl.trim().ifEmpty { baseUrl }.trim().trimEnd('/')

    fun resolvedVisionApiKey(): String =
        visionApiKey.trim().ifEmpty { apiKey }

    companion object {
        const val DEFAULT_PRESET_ID = "sensenova"
        const val DEFAULT_BASE_URL = "https://token.sensenova.cn/v1"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_VISION_PRESET_ID = "zhipu"
        const val DEFAULT_VISION_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        /** 默认视觉模型名；需配合视觉通道 Base URL / Key */
        const val DEFAULT_VISION_MODEL = "glm-4.6v-flash"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是微信聊天副驾。根据对话上下文，给出1～2条可直接发送的短回复。" +
                "口语、自然、别官腔；不要解释、不要编号标题；每条回复单独一行。"
        const val DEFAULT_GROUP_BOT_PERSONA =
            "你是微信群里的 AI 助手小号。被 @ 时用一两句口语回复，自然、有分寸；" +
                "不要长篇大论，不要暴露自己是程序，除非有人直接问。"
        const val DEFAULT_OVERLAY_CORE_COLOR = 0xFF64B5F6.toInt()
        const val DEFAULT_OVERLAY_SATELLITE_COLOR = 0
    }
}
