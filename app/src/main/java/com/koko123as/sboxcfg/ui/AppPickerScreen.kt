package com.koko123as.sboxcfg.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.koko123as.sboxcfg.config.ConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    initialSelected: Set<String>,
    onDone: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    var selected by remember { mutableStateOf(initialSelected.toMutableSet()) }
    var query by remember { mutableStateOf("") }
    var hideSystem by remember { mutableStateOf(true) }
    var hideIcon by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }

    // 后台加载应用列表（QUERY_ALL_PACKAGES 权限下可拿到全部）
    val apps by produceState<List<AppItem>?>(initialValue = null, retryKey) {
        value = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val list = if (Build.VERSION.SDK_INT >= 33)
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                else
                    pm.getInstalledApplications(0)
                list.filter { it.packageName != ConfigBuilder.SELF_PKG }
                    .map {
                        val label = try {
                            pm.getApplicationLabel(it).toString()
                        } catch (_: Exception) {
                            it.packageName
                        }
                        AppItem(
                            label = label,
                            packageName = it.packageName,
                            isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // 图标懒加载缓存
    val iconCache = remember { mutableStateMapOf<String, Drawable?>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择应用（${selected.size} 已选）", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onDone(selected.toSet()) }) {
                        Icon(Icons.Filled.Check, contentDescription = "完成")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用名 / 包名", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text("隐藏系统应用", fontSize = 13.sp)
                Switch(checked = hideSystem, onCheckedChange = { hideSystem = it })
                Spacer(Modifier.width(16.dp))
                Text("隐藏图标", fontSize = 13.sp)
                Switch(checked = hideIcon, onCheckedChange = { hideIcon = it })
            }

            when (val list = apps) {
                null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.weight(1f))
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text("正在读取应用列表…", fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                    }
                }
                else -> if (list.size <= 1) {
                    // 降级页：拿不到列表（某些 ROM 阉割 QUERY_ALL_PACKAGES）
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
                        Text(
                            "无法读取应用列表",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "可能是系统限制了应用查询权限。请到系统设置中为「SBox 配置助手」开启相关权限后重试。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Row {
                            OutlinedButton(onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                            }) { Text("打开应用设置") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { retryKey++ }) { Text("重试") }
                        }
                    }
                } else {
                    val q = query.trim().lowercase()
                    val filtered = list.filter { app ->
                        (!hideSystem || !app.isSystem) &&
                            (q.isEmpty() || app.label.lowercase().contains(q) ||
                                app.packageName.lowercase().contains(q))
                    }.sortedWith(
                        compareByDescending<AppItem> { selected.contains(it.packageName) }
                            .thenBy { it.label.lowercase() }
                    )
                    Text(
                        "共 ${filtered.size} 个应用",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = selected.contains(app.packageName)
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        selected = selected.toMutableSet().apply {
                                            if (checked) remove(app.packageName) else add(app.packageName)
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!hideIcon) {
                                    val drawable by produceState<Drawable?>(
                                        initialValue = iconCache[app.packageName],
                                        app.packageName
                                    ) {
                                        if (!iconCache.containsKey(app.packageName)) {
                                            value = withContext(Dispatchers.IO) {
                                                try {
                                                    pm.getApplicationIcon(app.packageName)
                                                } catch (_: Exception) {
                                                    null
                                                }
                                            }
                                            iconCache[app.packageName] = value
                                        }
                                    }
                                    if (drawable != null) {
                                        Image(
                                            bitmap = drawable!!.toBitmap(96, 96).asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else {
                                        Spacer(Modifier.size(36.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, fontSize = 14.sp)
                                    Text(
                                        app.packageName,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = selected.toMutableSet().apply {
                                            if (it) add(app.packageName) else remove(app.packageName)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
