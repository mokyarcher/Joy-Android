package com.joy.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.joy.android.overlay.OverlayController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupBotHomeScreen(
    viewModel: ReplyViewModel,
    onOpenSettings: () -> Unit,
    onOpenBotSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayRunning by remember { mutableStateOf(OverlayController.isRunning) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                overlayRunning = OverlayController.isRunning
                delay(600)
            }
        }
    }
    val nick = settings.groupBotNickname.trim().ifEmpty { "未设置昵称" }
    val personaPreview = settings.groupBotPersona
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.let { if (it.length > 48) it.take(48) + "…" else it }
        ?: "未设置人设"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Joy 群 Bot") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            AppModeSwitcher(
                mode = settings.appMode,
                onModeChange = { mode ->
                    viewModel.saveSettings(settings.copy(appMode = mode))
                },
            )
            Text(
                text = "小号进群开悬浮球即可。" +
                    "群 Bot 用本地 OCR 筛新的 @（不耗视觉 token）；" +
                    "手动点「解析」/自动看屏变动后处理。" +
                    "需 @你的昵称 才回复（智驾会尝试发送）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Bot 名片", style = MaterialTheme.typography.titleSmall)
                    Text("昵称：$nick", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "性别：${settings.groupBotGender.label} · 脾气：${settings.groupBotTemper.label}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (settings.groupBotProfession.isNotBlank()) {
                        Text(
                            text = "职业：${settings.groupBotProfession}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "人设：$personaPreview",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (settings.groupBotRequireAt) {
                            "唤醒：必须 @ · 冷却 ${settings.groupBotCooldownSec}s"
                        } else {
                            "唤醒：不强制 @ · 冷却 ${settings.groupBotCooldownSec}s"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenBotSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("编辑 Bot 配置")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("值班", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (overlayRunning) "悬浮球运行中" else "悬浮球未开启",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (overlayRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (overlayRunning) {
                            OutlinedButton(
                                onClick = {
                                    OverlayController.stop(context)
                                    overlayRunning = false
                                    Toast.makeText(context, "已关闭悬浮球", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("结束值班")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (!OverlayController.canDrawOverlays(context)) {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}"),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                        Toast.makeText(
                                            context,
                                            "请先授权悬浮窗",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@Button
                                    }
                                    if (settings.groupBotNickname.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            "请先设置 Bot 微信昵称",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        onOpenBotSettings()
                                        return@Button
                                    }
                                    OverlayController.start(context)
                                    overlayRunning = true
                                    Toast.makeText(context, "已开启群 Bot 值班", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("开始值班")
                            }
                        }
                    }
                    Text(
                        text = "已按「群 × 群友」记近期对话，避免串戏。可在配置里改昵称/人设/冷却。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
