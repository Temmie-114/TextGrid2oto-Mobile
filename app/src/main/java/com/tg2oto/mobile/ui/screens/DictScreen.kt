package com.tg2oto.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.ui.AppViewModel
import com.tg2oto.mobile.ui.components.ScreenColumn
import com.tg2oto.mobile.ui.components.SectionCard
import com.tg2oto.mobile.ui.i18n.L

@Composable
fun DictScreen(vm: AppViewModel, onBack: () -> Unit) {
    val dicts by vm.dicts.collectAsState()

    val dictPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importDict(uri, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.'))
        }
    }

    ScreenColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_ms_chevron_right),
                    contentDescription = L.s("返回"),
                    modifier = Modifier.rotate(180f).size(20.dp)
                )
            }
            Column {
                Text(L.s("字典库"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    L.s("导入的字典存放在应用私有目录，对齐与 JSON 页可直接选用"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard(L.s("已导入")) {
            Text(
                L.s("两段式字典：每行「音素<TAB>音素序列」。中文用 zh.txt，日文用 ja-hira-假名.txt，英文用 en-arpasing.txt。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            dicts.forEach { dict ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dict.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            String.format(L.s("%.1f KB · 已导入"), dict.file.length() / 1024.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.deleteDict(dict) }) {
                        Icon(painterResource(R.drawable.ic_ms_delete), L.s("删除"), Modifier.size(18.dp))
                    }
                }
            }
            if (dicts.isEmpty()) {
                Text(
                    L.s("还没有导入字典。也可以直接用内置的 zh.txt / ja-hira / en-arpasing。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { dictPicker.launch(arrayOf("text/plain", "*/*")) }) {
                Icon(painterResource(R.drawable.ic_ms_upload), null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(L.s("导入字典 txt"), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(70.dp))
    }
}
