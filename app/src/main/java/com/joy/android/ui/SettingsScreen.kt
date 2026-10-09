package com.joy.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.AppMode
import com.joy.android.data.ImageCaptureMode
import com.joy.android.data.JoySettings
import com.joy.android.overlay.OverlayController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenImageSettings: () -> Unit,
    onOpenPersonaSettings: () -> Unit,
    onOpenGroupBotSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionsUi by viewModel.sessions.collectAsStateWithLifecycle()

    val sessionsSummary = buildString {
        append("${sessionsUi.sessions.size} 个会话")
        sessionsUi.active?.title?.let {
            append(" · 当前 ")
            append(it)
        }
    }
    val overlaySummary = when {
        !OverlayController.canDrawOverlays(context) -> "需先授权悬浮窗"
        OverlayController.isRunning -> "运行中"
        else -> "未开启 · 微信上层快捷生成"
    }
    val modelSummary = buildString {
        append(settings.model.ifBlank { "未设回复模型" })
        if (settings.imageCaptureMode == ImageCaptureMode.VISION) {
            append(" · 视觉 ")
            append(settings.visionModel.ifBlank { JoySettings.DEFAULT_VISION_MODEL })
            val visionHost = settings.resolvedVisionBaseUrl()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
            if (visionHost.isNotBlank() &&
                visionHost != settings.baseUrl.trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
            ) {
                append(" @ ")
                append(visionHost)
            }
        }
    }
    val imageSummary = buildString {
        append(settings.imageCaptureMode.label)
        if (settings.imageCaptureMode == ImageCaptureMode.VISION) {
            append(" · ")
            append(settings.visionImagePolicy.label)
        }
    }
    val personaSummary = settings.systemPrompt
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.let { if (it.length > 36) it.take(36) + "…" else it }
        ?: "未设置"
    val groupBotSummary = buildString {
        val nick = settings.groupBotNickname.trim().ifEmpty { "未设昵称" }
        append(nick)
        append(" · ")
        append(settings.groupBotGender.label)
        append(" · ")
        append(settings.groupBotTemper.label)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsMenuCard(
                title = "会话管理",
                summary = sessionsSummary,
                icon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                onClick = onOpenSessions,
            )
            SettingsMenuCard(
                title = "悬浮窗",
                summary = overlaySummary,
                icon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                onClick = onOpenOverlaySettings,
            )
            SettingsMenuCard(
                title = "模型配置",
                summary = modelSummary,
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                onClick = onOpenModelSettings,
            )
            SettingsMenuCard(
                title = "选图识别",
                summary = imageSummary,
                icon = { Icon(Icons.Outlined.ImageSearch, contentDescription = null) },
                onClick = onOpenImageSettings,
            )
            if (settings.appMode == AppMode.ASSISTANT) {
                SettingsMenuCard(
                    title = "回复人设",
                    summary = personaSummary,
                    icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                    onClick = onOpenPersonaSettings,
                )
            } else {
                SettingsMenuCard(
                    title = "群 Bot 配置",
                    summary = groupBotSummary,
                    icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                    onClick = onOpenGroupBotSettings,
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuCard(
    title: String,
    summary: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            leadingContent = icon,
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}
