package com.joy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joy.android.data.AppMode

@Composable
fun AppModeSwitcher(
    mode: AppMode,
    enabled: Boolean = true,
    onModeChange: (AppMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppMode.entries.forEach { item ->
            FilterChip(
                selected = mode == item,
                onClick = { if (enabled && mode != item) onModeChange(item) },
                enabled = enabled,
                label = { Text(item.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
