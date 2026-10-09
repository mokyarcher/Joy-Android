package com.joy.android.data

enum class GroupBotTemper(val id: String, val label: String) {
    MILD("mild", "温和"),
    LIVELY("lively", "活泼"),
    DRY("dry", "冷淡"),
    SHARP("sharp", "毒舌"),
    WARM("warm", "暖心"),
    ;

    companion object {
        fun fromId(id: String?): GroupBotTemper =
            entries.firstOrNull { it.id == id } ?: MILD
    }
}
