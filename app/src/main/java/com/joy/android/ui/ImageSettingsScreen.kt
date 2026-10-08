package com.joy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.joy.android.data.ImageCaptureMode
import com.joy.android.data.VisionImagePolicy

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageSettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var imageCaptureModeId by rememberSaveable { mutableStateOf(settings.imageCaptureMode.id) }
    var visionImagePolicyId by rememberSaveable { mutableStateOf(settings.visionImagePolicy.id) }

    LaunchedEffect(settings) {
        imageCaptureModeId = settings.imageCaptureMode.id
        visionImagePolicyId = settings.visionImagePolicy.id
    }

    val imageCaptureMode = ImageCaptureMode.fromId(imageCaptureModeId)
    val visionImagePolicy = VisionImagePolicy.fromId(visionImagePolicyId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选图识别") },
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
                text = "选图识别",
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageCaptureMode.entries.forEach { mode ->
                    FilterChip(
                        selected = imageCaptureMode == mode,
                        onClick = { imageCaptureModeId = mode.id },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(
                text = when (imageCaptureMode) {
                    ImageCaptureMode.VISION ->
                        "推荐。看图判左右更稳（含群聊）。慢主要在上传+模型；" +
                            "可试智谱免费 glm-4.6v-flash，或商汤 6.7-flash-lite。"
                    ImageCaptureMode.OCR ->
                        "本地快、不耗 API；群聊长绿泡容易把「我」判成「对方」，仅作备用。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (imageCaptureMode == ImageCaptureMode.VISION) {
                Text(
                    text = "嵌图处理",
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VisionImagePolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = visionImagePolicy == policy,
                            onClick = { visionImagePolicyId = policy.id },
                            label = { Text(policy.label) },
                        )
                    }
                }
                Text(
                    text = visionImagePolicy.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    viewModel.saveSettings(
                        settings.copy(
                            imageCaptureMode = ImageCaptureMode.fromId(imageCaptureModeId),
                            visionImagePolicy = VisionImagePolicy.fromId(visionImagePolicyId),
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
