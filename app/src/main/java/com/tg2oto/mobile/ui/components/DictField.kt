package com.tg2oto.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.core.DictFile
import com.tg2oto.mobile.ui.i18n.L

/**
 * 字典选择器：内置字典 + 已导入字典 + 「导入 txt」入口。
 */
@Composable
fun DictField(
    label: String,
    value: String,
    builtIn: List<Pair<String, String>>,
    imported: List<DictFile>,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onDelete: (DictFile) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = remember(value, builtIn, imported) {
        builtIn.firstOrNull { it.second == value }?.first
            ?: imported.firstOrNull { it.path == value }?.name
            ?: value.substringAfterLast('/').ifBlank { L.s("未选择") }
    }

    Column(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_ms_translate), null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currentName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_ms_expand_more), null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (builtIn.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(L.s("内置字典"), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {}
                )
                builtIn.forEach { (name, path) ->
                    DropdownMenuItem(
                        text = { Text(name, fontSize = 13.sp) },
                        onClick = {
                            onSelect(path)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
            }
            if (imported.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(L.s("我导入的字典"), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {}
                )
                imported.forEach { dict ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(dict.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDelete(dict) }) {
                                    Text(L.s("删除"), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        onClick = {
                            onSelect(dict.path)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_ms_upload),
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(L.s("导入字典 txt…"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = {
                    expanded = false
                    onImport()
                }
            )
        }
    }
}
