package com.example.mediahub.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mediahub.data.ReaderSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(s: ReaderSettings, onChange: (ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("字号 ${s.fontSize.toInt()}")
            Slider(s.fontSize, { onChange(s.copy(fontSize = it)) }, valueRange = 12f..32f, steps = 19)
            Text("行距 ${"%.1f".format(s.lineSpacing)}")
            Slider(s.lineSpacing, { onChange(s.copy(lineSpacing = it)) }, valueRange = 1.0f..2.6f, steps = 15)
            Text("背景")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReaderSettings.THEMES.forEachIndexed { i, (bg, fg) ->
                    val sel = i == s.theme && !s.night
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(bg))
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onChange(s.copy(theme = i, night = false)) }, contentAlignment = Alignment.Center) {
                        Text("文", color = Color(fg))
                    }
                }
            }
            Text("翻页方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.pageMode == ReaderSettings.MODE_SCROLL, { onChange(s.copy(pageMode = ReaderSettings.MODE_SCROLL)) },
                    label = { Text("上下滚动") })
                FilterChip(s.pageMode == ReaderSettings.MODE_PAGE, { onChange(s.copy(pageMode = ReaderSettings.MODE_PAGE)) },
                    label = { Text("左右翻页") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("夜间模式", Modifier.weight(1f))
                Switch(s.night, { onChange(s.copy(night = it)) })
            }
        }
    }
}
