package com.joy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.GroupBotGender
import com.joy.android.data.GroupBotTemper
import com.joy.android.data.JoySettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupBotSettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var nickname by rememberSaveable { mutableStateOf(settings.groupBotNickname) }
    var genderId by rememberSaveable { mutableStateOf(settings.groupBotGender.id) }
    var temperId by rememberSaveable { mutableStateOf(settings.groupBotTemper.id) }
    var profession by rememberSaveable { mutableStateOf(settings.groupBotProfession) }
    var persona by rememberSaveable { mutableStateOf(settings.groupBotPersona) }
    var requireAt by rememberSaveable { mutableStateOf(settings.groupBotRequireAt) }
    var cooldownText by rememberSaveable {
        mutableStateOf(settings.groupBotCooldownSec.toString())
    }

    LaunchedEffect(settings) {
        nickname = settings.groupBotNickname
        genderId = settings.groupBotGender.id
        temperId = settings.groupBotTemper.id
        profession = settings.groupBotProfession
        persona = settings.groupBotPersona
        requireAt = settings.groupBotRequireAt
        cooldownText = settings.groupBotCooldownSec.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("群 Bot 配置") },
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
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Bot 微信昵称") },
                placeholder = { Text("与群里显示名一致，用于识别 @") },
            )

            Text("性别", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GroupBotGender.entries.forEach { g ->
                    FilterChip(
                        selected = genderId == g.id,
                        onClick = { genderId = g.id },
                        label = { Text(g.label) },
                    )
                }
            }

            Text("脾气", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GroupBotTemper.entries.forEach { t ->
                    FilterChip(
                        selected = temperId == t.id,
                        onClick = { temperId = t.id },
                        label = { Text(t.label) },
                    )
                }
            }

            OutlinedTextField(
                value = profession,
                onValueChange = { profession = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("职业 / 身份（可选）") },
                placeholder = { Text("群助理、程序员、情感树洞…") },
            )

            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                label = { Text("Bot 人设") },
                placeholder = { Text(JoySettings.DEFAULT_GROUP_BOT_PERSONA) },
            )

            RowSwitch(
                title = "必须 @ 才回复",
                checked = requireAt,
                onCheckedChange = { requireAt = it },
            )

            OutlinedTextField(
                value = cooldownText,
                onValueChange = { cooldownText = it.filter { ch -> ch.isDigit() }.take(3) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("冷却秒数") },
                placeholder = { Text("8") },
            )

            Button(
                onClick = {
                    val cooldown = cooldownText.toIntOrNull()?.coerceIn(0, 600) ?: 8
                    viewModel.saveSettings(
                        settings.copy(
                            groupBotNickname = nickname.trim(),
                            groupBotGender = GroupBotGender.fromId(genderId),
                            groupBotTemper = GroupBotTemper.fromId(temperId),
                            groupBotProfession = profession.trim(),
                            groupBotPersona = persona.trim().ifEmpty {
                                JoySettings.DEFAULT_GROUP_BOT_PERSONA
                            },
                            groupBotRequireAt = requireAt,
                            groupBotCooldownSec = cooldown,
                        ),
                        onDone = onBack,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun RowSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
