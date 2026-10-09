package com.joy.android.data

/**
 * 产品级模式：影响首页布局与配置菜单，与悬浮球「手动/自动/智驾」正交。
 */
enum class AppMode(val id: String, val label: String) {
    ASSISTANT("assistant", "回复助手"),
    GROUP_BOT("group_bot", "群 Bot"),
    ;

    companion object {
        fun fromId(id: String?): AppMode =
            entries.firstOrNull { it.id == id } ?: ASSISTANT
    }
}
