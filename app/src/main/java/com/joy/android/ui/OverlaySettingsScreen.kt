package com.joy.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joy.android.data.JoySettings
import com.joy.android.overlay.OverlayController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlaySettingsScreen(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var canOverlay by remember { mutableStateOf(OverlayController.canDrawOverlays(context)) }
    var running by remember { mutableStateOf(OverlayController.isRunning) }

    fun refresh() {
        canOverlay = OverlayController.canDrawOverlays(context)
        running = OverlayController.isRunning
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        OverlayController.start(context)
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("悬浮窗") },
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
            var a11yEnabled by remember {
                mutableStateOf(com.joy.android.overlay.JoyAccessibilityService.isEnabled(context))
            }
            var a11yConnected by remember {
                mutableStateOf(com.joy.android.overlay.JoyAccessibilityService.instance != null)
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        a11yEnabled =
                            com.joy.android.overlay.JoyAccessibilityService.isEnabled(context)
                        a11yConnected =
                            com.joy.android.overlay.JoyAccessibilityService.instance != null
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            Text(
                text = if (canOverlay) "悬浮窗权限：已授权" else "悬浮窗权限：未授权",
                style = MaterialTheme.typography.bodyMedium,
                color = if (canOverlay) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = when {
                    a11yEnabled && a11yConnected -> "无障碍：已开启"
                    a11yEnabled -> "无障碍：未连接"
                    else -> "无障碍：未开启"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (a11yEnabled && a11yConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                text = if (running) "悬浮球：运行中" else "悬浮球：未开启",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "启动应用时自动打开",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = settings.autoStartOverlay,
                    onCheckedChange = { enabled ->
                        viewModel.saveSettings(settings.copy(autoStartOverlay = enabled))
                        if (enabled && canOverlay && !OverlayController.isRunning) {
                            OverlayController.start(context)
                            refresh()
                        }
                    },
                )
            }

            if (!canOverlay) {
                Button(
                    onClick = {
                        context.startActivity(OverlayController.overlaySettingsIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("去开启悬浮窗权限")
                }
            }
            if (!a11yEnabled || !a11yConnected) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(com.joy.android.overlay.JoyAccessibilityService.settingsIntent())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (a11yEnabled) "重新开关无障碍" else "开启无障碍")
                }
            }
            if (canOverlay && !running) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            OverlayController.start(context)
                            refresh()
                            Toast.makeText(context, "悬浮球已开启", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开启悬浮球")
                }
            } else if (running) {
                OutlinedButton(
                    onClick = {
                        OverlayController.stop(context)
                        running = false
                        Toast.makeText(context, "已关闭悬浮球", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("关闭悬浮球")
                }
            }

            Text(
                text = "主题颜色",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "主球",
                style = MaterialTheme.typography.bodyMedium,
            )
            OverlayColorRow(
                selected = settings.overlayCoreColorArgb,
                allowClear = false,
                onPick = { argb ->
                    viewModel.saveSettings(settings.copy(overlayCoreColorArgb = argb))
                },
            )
            Text(
                text = "副球",
                style = MaterialTheme.typography.bodyMedium,
            )
            OverlayColorRow(
                selected = settings.overlaySatelliteColorArgb,
                allowClear = true,
                onPick = { argb ->
                    viewModel.saveSettings(settings.copy(overlaySatelliteColorArgb = argb))
                },
            )
        }
    }
}

private data class OverlayColorSwatch(val argb: Int, val label: String)

private val OverlayColorPalette = listOf(
    OverlayColorSwatch(JoySettings.DEFAULT_OVERLAY_CORE_COLOR, "蓝"),
    OverlayColorSwatch(0xFF26A69A.toInt(), "青绿"),
    OverlayColorSwatch(0xFF5C6BC0.toInt(), "靛"),
    OverlayColorSwatch(0xFFAB47BC.toInt(), "紫"),
    OverlayColorSwatch(0xFFEC407A.toInt(), "粉"),
    OverlayColorSwatch(0xFFEF5350.toInt(), "红"),
    OverlayColorSwatch(0xFFFFA726.toInt(), "橙"),
    OverlayColorSwatch(0xFF66BB6A.toInt(), "绿"),
    OverlayColorSwatch(0xFF26C6DA.toInt(), "青"),
    OverlayColorSwatch(0xFF90A4AE.toInt(), "灰"),
)

@Composable
private fun OverlayColorRow(
    selected: Int,
    allowClear: Boolean,
    onPick: (Int) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allowClear) {
            ColorDot(
                color = Color.Transparent,
                selected = selected == 0,
                outlined = true,
                outlineColor = outline,
                onClick = { onPick(0) },
            )
        }
        OverlayColorPalette.forEach { swatch ->
            ColorDot(
                color = Color(swatch.argb),
                selected = selected == swatch.argb,
                outlined = false,
                outlineColor = outline,
                onClick = { onPick(swatch.argb) },
            )
        }
    }
}

@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    outlined: Boolean,
    outlineColor: Color,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else if (outlined) {
        outlineColor
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (color.alpha < 0.01f) Color(0xFFE0E0E0) else color)
            .border(width = if (selected) 3.dp else 1.dp, color = borderColor, shape = CircleShape)
            .clickable(onClick = onClick),
    )
}
