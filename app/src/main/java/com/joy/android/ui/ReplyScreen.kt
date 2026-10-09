package com.joy.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.ImageCaptureMode

private val ActionButtonShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyScreen(
    viewModel: ReplyViewModel,
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionsUi by viewModel.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busy = ui.loading || ui.ocrLoading
    val activeTitle = sessionsUi.active?.title ?: "会话"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Joy 回复助手") },
                actions = {
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Default.Forum, contentDescription = "会话")
                    }
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
                enabled = !busy,
                onModeChange = { mode ->
                    viewModel.saveSettings(settings.copy(appMode = mode))
                },
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy, onClick = onOpenSessions),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前会话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = activeTitle,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.createSession() },
                        enabled = !busy,
                    ) {
                        Text("新建")
                    }
                }
            }
            OutlinedTextField(
                value = ui.chatText,
                onValueChange = viewModel::onChatTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                enabled = !busy,
                label = { Text("聊天内容") },
                placeholder = { Text("对方：在干嘛呢\n我：刚躺床上刷手机") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::generate,
                    enabled = !busy && ui.chatText.isNotBlank(),
                    shape = ActionButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text(if (ui.loading) "生成中…" else "生成回复", maxLines = 1)
                }
                OutlinedButton(
                    onClick = viewModel::clearChat,
                    enabled = !busy,
                    shape = ActionButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text("清空", maxLines = 1)
                }
            }
            if (busy) {
                BusyProgressCard(
                    phase = ui.busyPhase ?: if (ui.ocrLoading) "识别中…" else "生成中…",
                    elapsedMs = ui.busyElapsedMs,
                    visionHint = ui.ocrLoading &&
                        settings.imageCaptureMode == ImageCaptureMode.VISION,
                    showCancel = true,
                    onCancel = viewModel::cancelBusyTask,
                )
            }
            ui.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            ui.info?.let {
                Text(text = it, color = MaterialTheme.colorScheme.primary)
            }
            ui.candidates.forEachIndexed { index, text ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "候选 ${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = text, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { copyToClipboard(context, text) }) {
                            Text("复制")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusyProgressCard(
    phase: String,
    elapsedMs: Long,
    visionHint: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = phase,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "已用时 ${ReplyViewModel.formatElapsed(elapsedMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (visionHint) {
                    Text(
                        text = "视觉识图稍慢属正常；可取消。想更快：模型配置换更轻的 Vision。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (showCancel) {
                    OutlinedButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("joy_reply", text))
    Toast.makeText(context, "已复制，去微信粘贴发送", Toast.LENGTH_SHORT).show()
}
