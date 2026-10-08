package com.joy.android.overlay

/**
 * 悬浮球辅助模式（外观可切换；截屏/填入/自动发送明日再接）。
 */
enum class OverlayAssistMode(val shortLabel: String) {
    MANUAL("手动"),
    AUTO("自动"),
    PILOT("智驾"),
    ;

    fun next(): OverlayAssistMode = when (this) {
        MANUAL -> AUTO
        AUTO -> PILOT
        PILOT -> MANUAL
    }
}

/** 收缩态左右贴边半隐 */
enum class OverlayEdgeDock {
    None,
    Left,
    Right,
}

data class OverlayUiState(
    val expanded: Boolean = false,
    val edgeDock: OverlayEdgeDock = OverlayEdgeDock.None,
    val mode: OverlayAssistMode = OverlayAssistMode.MANUAL,
    val sessionTitle: String = "会话",
    val busyHint: String? = null,
    val coreColorArgb: Int = 0xFF64B5F6.toInt(),
    val satelliteColorArgb: Int = 0,
)
