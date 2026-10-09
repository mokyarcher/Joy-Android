package com.joy.android.data

enum class GroupBotGender(val id: String, val label: String) {
    UNSPECIFIED("unspecified", "不设定"),
    MALE("male", "男"),
    FEMALE("female", "女"),
    NEUTRAL("neutral", "中性"),
    ;

    companion object {
        fun fromId(id: String?): GroupBotGender =
            entries.firstOrNull { it.id == id } ?: UNSPECIFIED
    }
}
