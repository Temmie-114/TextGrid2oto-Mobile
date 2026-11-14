package com.tg2oto.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tg2oto.mobile.R
import com.tg2oto.mobile.ui.components.SectionCard

/**
 * 开源许可与致谢页。
 *
 * 这一页固定用英文：许可证原文本身是英文，翻译反而会让「许可声明」与原文
 * 对不上；也让海外用户能直接读懂义务。其余界面仍跟随用户选择。
 */
private data class OssEntry(
    val name: String,
    val role: String,
    val license: String,
    val url: String,
    val note: String = ""
)

private val OSS = listOf(
    OssEntry(
        name = "TextGrid2oto",
        role = "Upstream project — the whole label / align / json / oto pipeline originates here",
        license = "GPL-3.0",
        url = "https://github.com/xiaobaijunya/TextGrid2oto",
        note = "This app is a derivative work and stays under GPL-3.0."
    ),
    OssEntry(
        name = "TIFA",
        role = "Phoneme forced aligner — model TIFA-1.0-ST",
        license = "MIT",
        url = "https://github.com/openvpi/TIFA",
        note = "Weights shipped here were exported to ONNX and converted to weight-level fp16 by this project; they are a modified version, not the upstream release artefact."
    ),
    OssEntry(
        name = "HubertFA",
        role = "HuBERT-based forced aligner — model 1218_hfa_model v0.0.7",
        license = "Apache-2.0",
        url = "https://github.com/wolfgitpr/HubertFA",
        note = "model.onnx converted to weight-level fp16 by this project; no other change."
    ),
    OssEntry(
        name = "chinese-hubert-base",
        role = "Acoustic backbone of the HubertFA model",
        license = "MIT",
        url = "https://huggingface.co/TencentGameMate/chinese-hubert-base",
        note = "Redistributed as part of the HubertFA checkpoint."
    ),
    OssEntry(
        name = "OpenUtau",
        role = "Reference implementation for oto.ini semantics and resampler behaviour",
        license = "See upstream",
        url = "https://github.com/stakira/OpenUtau",
        note = "Reference only — no code is copied into this app."
    ),
    OssEntry(
        name = "DiffSinger",
        role = "Reference for dataset layout (transcriptions.csv, ph_num)",
        license = "See upstream",
        url = "https://github.com/openvpi/DiffSinger",
        note = "Reference only — no code is copied."
    ),
    OssEntry(
        name = "Praat / TextGrid",
        role = "TextGrid file format specification",
        license = "See upstream",
        url = "https://github.com/praat/praat.github.io",
        note = "Format reference only."
    ),
    OssEntry(
        name = "Chaquopy",
        role = "Python runtime on Android",
        license = "See upstream",
        url = "https://chaquo.com/chaquopy/",
        note = "Consumed as a Gradle dependency under its own terms."
    ),
    OssEntry(
        name = "ONNX Runtime",
        role = "Inference engine (Android AAR)",
        license = "See upstream",
        url = "https://github.com/microsoft/onnxruntime",
        note = "Consumed as a Gradle dependency under its own terms."
    ),
    OssEntry(
        name = "Material Symbols",
        role = "UI icon set",
        license = "Apache-2.0",
        url = "https://fonts.google.com/icons",
        note = "Embedded as vector drawables."
    )
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 版本号从 applicationId 的包信息里读，别再手写死：1.0.0 那行就是漏改的教训
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val repo = "https://github.com/Temmie-114/TextGrid2oto-Mobile"

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_ms_chevron_right), null, Modifier.size(20.dp).rotate(180f))
                }
                Text(
                    "Open source licenses",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    "TextGrid2oto Mobile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Version ${versionName.ifBlank { "?" }} · GPL-3.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "An Android port of TextGrid2oto: wav → lab → forced alignment → TextGrid → json → oto.ini, " +
                        "entirely on device.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                SectionCard("License of this project") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Text(
                            "TextGrid2oto Mobile is free software: you can redistribute it and/or modify it " +
                                "under the terms of the GNU General Public License as published by the Free " +
                                "Software Foundation, either version 3 of the License, or (at your option) any " +
                                "later version.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; " +
                                "without even the implied warranty of MERCHANTABILITY or FITNESS FOR A " +
                                "PARTICULAR PURPOSE. See the GNU General Public License for more details.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Because it is a derivative work of TextGrid2oto (GPL-3.0), this port cannot be " +
                                "released under a looser license. Full texts of every third-party license, " +
                                "including the MIT and Apache-2.0 notices and our modification statements, " +
                                "ship with the source:",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        LinkRow("THIRD_PARTY_NOTICES.md", repo + "/blob/main/THIRD_PARTY_NOTICES.md", ::open)
                        LinkRow("Source repository", repo, ::open)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Built on these open-source projects",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                OSS.forEach { entry ->
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { open(entry.url) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entry.license,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                entry.role,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (entry.note.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    entry.note,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                entry.url,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SectionCard("Credits") {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Text(
                            "Port and Android implementation: Temmie-114",
                            fontSize = 13.sp
                        )
                        Text(
                            "Original project: xiaobaijunya",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Thanks to everyone maintaining the models, the alignment research and the " +
                                "UTAU/DiffSinger tooling that made this possible.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun LinkRow(text: String, url: String, open: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { open(url) }.padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_ms_article),
            null,
            Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}
