package com.joy.android.data

/**
 * 视觉模式下，聊天里「嵌套图片/表情包/截图气泡」怎么处理。
 */
enum class VisionImagePolicy(
    val id: String,
    val label: String,
    val hint: String,
) {
    IGNORE(
        id = "ignore",
        label = "忽略嵌图",
        hint = "图片气泡不进摘录，只要文字上下文。",
    ),
    PLACEHOLDER(
        id = "placeholder",
        label = "标注占位",
        hint = "保留气泡位置，写成「[图片]」，不读图内文字。",
    ),
    DESCRIBE(
        id = "describe",
        label = "简述内容",
        hint = "写成「[图片:短描述]」，便于根据图意回复（略耗 Token）。",
    ),
    ;

    companion object {
        fun fromId(id: String?): VisionImagePolicy =
            entries.firstOrNull { it.id == id } ?: PLACEHOLDER
    }
}
