package com.joy.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val sessionsUi by viewModel.sessions.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val busy = ui.loading || ui.ocrLoading
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!busy) {
                        viewModel.createSession()
                    }
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建会话")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "每个会话单独保存聊天上下文与最近候选；可按微信联系人命名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(sessionsUi.sessions, key = { it.id }) { session ->
                val selected = session.id == sessionsUi.activeId
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = buildString {
                                    append(session.title)
                                    if (selected) append(" · 当前")
                                },
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = session.preview(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = formatUpdatedAt(session.updatedAt) +
                                        if (session.candidates.isNotEmpty()) {
                                            " · ${session.candidates.size} 条候选"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { renameTarget = session },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "重命名")
                                }
                                IconButton(
                                    onClick = { deleteTarget = session },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.clickable(enabled = !busy) {
                            viewModel.switchSession(session.id)
                            onBack()
                        },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("名称（如微信备注）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(target.id, draft)
                        renameTarget = null
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = {
                Text("确定删除「${target.title}」？上下文与候选都会清掉。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun formatUpdatedAt(ms: Long): String {
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return fmt.format(Date(ms))
}
