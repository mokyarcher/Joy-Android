package com.joy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.JoySettings
import com.joy.android.data.ProviderPresets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val probe by viewModel.connectionProbe.collectAsStateWithLifecycle()

    var presetId by rememberSaveable { mutableStateOf(settings.presetId) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable { mutableStateOf(settings.model) }

    var visionPresetId by rememberSaveable { mutableStateOf(settings.visionPresetId) }
    var visionBaseUrl by rememberSaveable { mutableStateOf(settings.visionBaseUrl) }
    var visionApiKey by rememberSaveable { mutableStateOf(settings.visionApiKey) }
    var visionModel by rememberSaveable { mutableStateOf(settings.visionModel) }

    LaunchedEffect(settings) {
        presetId = settings.presetId
        baseUrl = settings.baseUrl
        apiKey = settings.apiKey
        model = settings.model
        visionPresetId = settings.visionPresetId
        visionBaseUrl = settings.visionBaseUrl
        visionApiKey = settings.visionApiKey
        visionModel = settings.visionModel
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearConnectionProbe() }
    }

    val replyPreset = ProviderPresets.byId(presetId)
    val modelOptions = replyPreset.models
    val visionOptions = ProviderPresets.visionModelsForPreset(visionPresetId)
    val draft = settings.copy(
        presetId = presetId,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        visionPresetId = visionPresetId,
        visionBaseUrl = visionBaseUrl,
        visionApiKey = visionApiKey,
        visionModel = visionModel,
    )
    val visionUsesReplyFallback =
        visionBaseUrl.isBlank() || visionApiKey.isBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型配置") },
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
            Text(
                text = "回复（思考）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "生成短回复用的通道，可与视觉分开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderChipRow(
                selectedId = presetId,
                presets = ProviderPresets.ALL,
                onSelect = { item ->
                    presetId = item.id
                    if (item.id != "custom") {
                        baseUrl = item.baseUrl
                        model = item.defaultModel
                    }
                    viewModel.clearConnectionProbe()
                },
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    presetId = ProviderPresets.matchPresetId(it, model)
                    viewModel.clearConnectionProbe()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("回复 Base URL") },
                placeholder = { Text(JoySettings.DEFAULT_BASE_URL) },
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    viewModel.clearConnectionProbe()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("回复 API Key") },
            )
            ModelDropdownField(
                value = model,
                options = modelOptions,
                label = "回复 Model",
                onValueChange = {
                    model = it
                    viewModel.clearConnectionProbe()
                },
            )
            ProbeButton(
                label = "测试回复通道",
                busy = probe.busy && probe.target == ConnectionProbeTarget.REPLY,
                enabled = !probe.busy,
                onClick = {
                    viewModel.testConnection(draft, ConnectionProbeTarget.REPLY)
                },
            )
            if (probe.target == ConnectionProbeTarget.REPLY) {
                ProbeMessage(probe)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = "视觉（识图）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "截图解图专用。可走智谱免费 glm-4.6v-flash，与回复服务商无关。" +
                    if (visionUsesReplyFallback) {
                        " 未填的 URL/Key 会暂用回复通道。"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderChipRow(
                selectedId = visionPresetId,
                presets = ProviderPresets.visionCapablePresets(),
                onSelect = { item ->
                    visionPresetId = item.id
                    if (item.id != "custom") {
                        visionBaseUrl = item.baseUrl
                        visionModel = ProviderPresets.defaultVisionModelForPreset(item.id)
                    }
                    viewModel.clearConnectionProbe()
                },
            )
            TextButton(
                onClick = {
                    visionBaseUrl = ""
                    visionApiKey = ""
                    visionPresetId = ProviderPresets.matchPresetId(baseUrl, visionModel)
                    viewModel.clearConnectionProbe()
                },
            ) {
                Text("视觉 URL/Key 与回复相同")
            }
            OutlinedTextField(
                value = visionBaseUrl,
                onValueChange = {
                    visionBaseUrl = it
                    visionPresetId = ProviderPresets.matchPresetId(
                        it.ifBlank { baseUrl },
                        visionModel,
                    )
                    viewModel.clearConnectionProbe()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("视觉 Base URL") },
                placeholder = {
                    Text(baseUrl.ifBlank { JoySettings.DEFAULT_VISION_BASE_URL })
                },
            )
            OutlinedTextField(
                value = visionApiKey,
                onValueChange = {
                    visionApiKey = it
                    viewModel.clearConnectionProbe()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("视觉 API Key") },
                placeholder = { Text(if (apiKey.isNotBlank()) "留空则用回复 Key" else "必填") },
            )
            ModelDropdownField(
                value = visionModel,
                options = visionOptions,
                label = "视觉 Model",
                placeholder = JoySettings.DEFAULT_VISION_MODEL,
                onValueChange = { visionModel = it },
            )
            ProbeButton(
                label = "测试视觉通道",
                busy = probe.busy && probe.target == ConnectionProbeTarget.VISION,
                enabled = !probe.busy,
                onClick = {
                    viewModel.testConnection(draft, ConnectionProbeTarget.VISION)
                },
            )
            if (probe.target == ConnectionProbeTarget.VISION) {
                ProbeMessage(probe)
            }

            Button(
                onClick = {
                    viewModel.saveSettings(draft, onDone = onBack)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChipRow(
    selectedId: String,
    presets: List<com.joy.android.data.ProviderPreset>,
    onSelect: (com.joy.android.data.ProviderPreset) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { item ->
            FilterChip(
                selected = selectedId == item.id,
                onClick = { onSelect(item) },
                label = { Text(item.name) },
            )
        }
    }
}

@Composable
private fun ProbeButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text("连通测试中…")
            }
        } else {
            Text(label)
        }
    }
}

@Composable
private fun ProbeMessage(probe: ConnectionProbeUi) {
    val msg = probe.message ?: return
    val color = when (probe.success) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = msg,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdownField(
    value: String,
    options: List<String>,
    label: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val canDropdown = options.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded && canDropdown,
        onExpandedChange = { if (canDropdown) expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = {
                if (canDropdown) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
        )
        if (canDropdown) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}
