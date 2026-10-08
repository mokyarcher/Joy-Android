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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.joy.android.data.PersonaPresets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaSettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var systemPrompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }

    LaunchedEffect(settings) {
        systemPrompt = settings.systemPrompt
    }

    val selectedPresetId = PersonaPresets.matchingId(systemPrompt)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回复人设") },
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PersonaPresets.ALL.forEach { preset ->
                    FilterChip(
                        selected = selectedPresetId == preset.id,
                        onClick = { systemPrompt = preset.prompt },
                        label = { Text(preset.label) },
                    )
                }
            }

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("系统提示") },
                leadingIcon = {
                    Icon(Icons.Outlined.Psychology, contentDescription = null)
                },
            )

            Button(
                onClick = {
                    viewModel.saveSettings(
                        settings.copy(systemPrompt = systemPrompt),
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
