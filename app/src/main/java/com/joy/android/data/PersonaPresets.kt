package com.joy.android.data

/**
 * 回复人设快捷预设：点选即写入系统提示，仍可再手改。
 */
data class PersonaPreset(
    val id: String,
    val label: String,
    val prompt: String,
)

object PersonaPresets {
    val ALL: List<PersonaPreset> = listOf(
        PersonaPreset(
            id = "default",
            label = "默认",
            prompt = JoySettings.DEFAULT_SYSTEM_PROMPT,
        ),
        PersonaPreset(
            id = "gentle",
            label = "温柔型",
            prompt = "你是微信聊天副驾，人设温柔体贴。根据对话给出1～2条可直接发送的短回复。" +
                "语气软、会安慰、不催促；口语自然；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "rough",
            label = "粗暴型",
            prompt = "你是微信聊天副驾，人设直爽粗暴。根据对话给出1～2条可直接发送的短回复。" +
                "说话硬、短、冲，可带点不客气，但不人身攻击或脏话过头；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "sarcastic",
            label = "尖酸刻薄",
            prompt = "你是微信聊天副驾，人设尖酸刻薄爱阴阳。根据对话给出1～2条可直接发送的短回复。" +
                "带刺、会挖苦，但像熟人互怼不是网暴；口语短句；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "humor",
            label = "幽默搞怪",
            prompt = "你是微信聊天副驾，人设幽默搞怪。根据对话给出1～2条可直接发送的短回复。" +
                "轻松玩笑、可玩梗，别尬；口语自然；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "cold",
            label = "高冷简洁",
            prompt = "你是微信聊天副驾，人设高冷简洁。根据对话给出1～2条可直接发送的短回复。" +
                "惜字如金、淡淡的，不热情不解释；不要编号标题；每条独占一行。",
        ),
        PersonaPreset(
            id = "sweet",
            label = "撒娇粘人",
            prompt = "你是微信聊天副驾，人设撒娇粘人。根据对话给出1～2条可直接发送的短回复。" +
                "软软的、会求关注，可用语气词，别油腻尴尬；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "business",
            label = "客气商务",
            prompt = "你是微信聊天副驾，人设客气商务。根据对话给出1～2条可直接发送的短回复。" +
                "礼貌得体、简洁专业，不卖萌不玩笑；不要解释、不要编号；每条独占一行。",
        ),
        PersonaPreset(
            id = "bro",
            label = "损友互怼",
            prompt = "你是微信聊天副驾，人设损友互怼。根据对话给出1～2条可直接发送的短回复。" +
                "贫嘴吐槽、像铁哥们抬杠，别恶意伤人；口语短句；不要解释、不要编号；每条独占一行。",
        ),
    )

    fun matchingId(prompt: String): String? {
        val t = prompt.trim()
        return ALL.firstOrNull { it.prompt.trim() == t }?.id
    }
}
