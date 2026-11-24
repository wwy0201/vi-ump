package com.vi.ump.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「切换数据源」面板（2026-11-24）。
 *
 * 为什么要有它：首页加载失败 / 空结果时，顶栏的源下拉还在，但用户的注意力在屏幕中间，
 * 还得抬头去找那颗按钮。这里给一个"就在眼前"的出口：点一下列出来的源即切换，
 * 一个启用的源都没有时，直接给"去设置"（导入新配置或启用停用的那份）。
 *
 * 只列**启用的**源（停用的已从下拉里消失，这里保持同一口径）；
 * 当前源标「当前」，点它等于关掉面板，不做无意义的重建。
 */
@Composable
fun SwitchSourceDialog(
    current: SourceEntry?,
    sources: List<SourceEntry>,
    onSelect: (SourceEntry) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val others = sources.filter { it.id != current?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换数据源") },
        text = {
            Column {
                Text(
                    text = current?.let { "当前：${it.label}" } ?: "当前没有可用的数据源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (others.isEmpty()) {
                    Text(
                        text = "没有其它启用的源。到设置里导入一份新配置，或启用一个已停用的源。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp).padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(others.size) { index ->
                            val entry = others[index]
                            TextButton(
                                onClick = { onSelect(entry) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!entry.version.isNullOrBlank()) {
                                        Text(
                                            text = "v${entry.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSettings) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
