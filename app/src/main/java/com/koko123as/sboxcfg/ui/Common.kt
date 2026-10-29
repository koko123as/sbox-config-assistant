package com.koko123as.sboxcfg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun MultiLineInput(
    label: String,
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    minLines: Int = 2
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = 8,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
    }
}

/** 出口选择弹窗 */
@Composable
fun OutboundPickerDialog(
    title: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(options) { tag ->
                    TextButton(
                        onClick = { onPick(tag) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tag,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (tag == current) FontWeight.Bold else FontWeight.Normal,
                                color = if (tag == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (tag == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** code 多选弹窗（geosite / geoip），支持勾选 + 手输补充 */
@Composable
fun CodeMultiSelectDialog(
    title: String,
    hint: String,
    codes: List<String>,
    selected: Set<String>,
    prefix: String,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sel = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    val extra = remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(codes) { code ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = sel.contains(code),
                                onCheckedChange = {
                                    if (it) sel.add(code) else sel.remove(code)
                                }
                            )
                            Text("$prefix$code", fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = extra.value,
                    onValueChange = { extra.value = it },
                    label = { Text("手输补充（逗号/空格/换行分隔）", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val manual = extra.value.split(",", " ", "\n", "，", "、")
                    .map { it.trim().removePrefix(prefix).trim() }
                    .filter { it.isNotEmpty() }
                onConfirm((sel.toSet() + manual).toSet())
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun ConfirmDialog(
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 常用 geosite / geoip code（Karing 内置规则集思路的轻量版） */
object CommonCodes {
    val GEOSITE = listOf(
        "cn", "geolocation-!cn", "private",
        "google", "youtube", "github", "telegram", "tiktok",
        "netflix", "microsoft", "microsoft@cn", "apple", "apple@cn",
        "onedrive", "steam", "steam@cn", "paypal", "binance", "okx",
        "category-ai-!cn", "category-cryptocurrency"
    )
    val GEOIP = listOf("cn", "private", "telegram", "us", "sg", "jp", "hk", "tw", "kr")
}
