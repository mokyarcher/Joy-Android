package com.joy.android.data

/**
 * 选图识聊天的方式。
 * - [VISION]：多模态看图，角色/群聊更稳，稍慢、耗 API（推荐）
 * - [OCR]：本地 ML Kit，快、免费，群聊长泡容易判错边
 */
enum class ImageCaptureMode(val id: String, val label: String) {
    VISION("vision", "视觉模式"),
    OCR("ocr", "OCR 模式"),
    ;

    companion object {
        fun fromId(id: String?): ImageCaptureMode =
            entries.firstOrNull { it.id == id } ?: VISION
    }
}
