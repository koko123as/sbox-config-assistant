package com.koko123as.sboxcfg.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.koko123as.sboxcfg.config.ConfigBuilder
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.server.ConfigHttpServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GenerateScreen(data: AppConfigData) {
    val context = LocalContext.current

    // 数据变化时重新生成（含自检警告）
    val buildResult = remember(data) {
        try {
            ConfigBuilder.build(data, context)
        } catch (e: Exception) {
            null
        }
    }
    val jsonText = remember(buildResult) {
        try {
            buildResult?.config?.toString(2) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    var expanded by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    // 保存到文件（SAF，用户选位置，无需存储权限）
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && jsonText.isNotEmpty()) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(jsonText.toByteArray(Charsets.UTF_8))
                }
                toast = "已保存"
            } catch (e: Exception) {
                toast = "保存失败：${e.message}"
            }
        }
    }

    fun copyJson() {
        if (jsonText.isEmpty()) {
            toast = "没有可复制的内容"
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sing-box config", jsonText))
        toast = "已复制到剪贴板，到 SFA 里从剪贴板导入"
    }

    fun shareJson() {
        if (jsonText.isEmpty()) {
            toast = "没有可分享的内容"
            return
        }
        try {
            // 写缓存文件，用 FileProvider 分享（SFA 可从文件导入）
            val dir = File(context.cacheDir, "shared")
            dir.mkdirs()
            val f = File(dir, "singbox-config.json")
            f.writeText(jsonText)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享配置文件"))
        } catch (e: Exception) {
            // 降级：分享文本
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, jsonText)
            }
            context.startActivity(Intent.createChooser(intent, "分享配置文件"))
        }
    }

    // ⑤ 远程链接：本地 HTTP 服务（只绑 127.0.0.1，每次请求现场生成最新配置）
    val dataRef = rememberUpdatedState(data)
    val httpServer = remember {
        ConfigHttpServer(buildJson = {
            runCatching { ConfigBuilder.build(dataRef.value, context).config.toString(2) }
                .getOrDefault("{\"error\":\"build failed\"}")
        })
    }
    var serverOn by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var serverErr by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { httpServer.stop() }
    }

    fun copyLink() {
        if (serverUrl.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sing-box remote config", serverUrl))
        toast = "链接已复制，到 SFA 里新建远程配置粘贴"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("生成 sing-box 配置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "模板与远程 /custom-config 同款（DNS、34 条路由规则、分组结构）；" +
                "你的自定义规则组会插到 AI 应用规则之后、geosite 通用规则之前，并同步生成 DNS 联动规则。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        val warnings = buildResult?.warnings ?: listOf("生成失败，请检查节点与规则配置")
        if (warnings.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("自检提醒", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    warnings.forEach {
                        Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                "自检通过：出口引用无悬空，空国家组已剔除。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        }

        Row {
            Text("配置预览", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开全部")
            }
        }
        val preview = if (expanded) jsonText
        else jsonText.lineSequence().take(40).joinToString("\n") +
            if (jsonText.lines().size > 40) "\n…（共 ${jsonText.lines().size} 行）" else ""
        Text(
            preview.ifEmpty { "（暂无内容：请先添加节点）" },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = if (expanded) 2000.dp else 320.dp)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .padding(8.dp)
        )
        Spacer(Modifier.height(12.dp))
        ButtonRow {
            SecondaryButton(
                text = "复制",
                onClick = ::copyJson,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "保存文件",
                onClick = {
                    val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    saveLauncher.launch("singbox-$ts.json")
                },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(text = "分享", onClick = ::shareJson, modifier = Modifier.weight(1f))
        }
        // ⑤ 远程链接卡片
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("远程链接", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "SFA 用远程配置导入下方链接；改节点/规则/DNS 后，SFA 点更新即同步最新配置。" +
                        "刷新前先断开 SFA；更新时本 App 需保持运行；链接仅本机可访问。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (serverOn) {
                    Text(
                        serverUrl,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    ButtonRow {
                        SecondaryButton(
                            text = "复制链接",
                            onClick = ::copyLink,
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryButton(
                            text = "停止服务",
                            onClick = {
                                httpServer.stop()
                                serverOn = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    if (serverErr.isNotEmpty()) {
                        Text(serverErr, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    PrimaryButton(
                        text = "开启本地服务",
                        onClick = {
                            try {
                                httpServer.start()
                                serverUrl = httpServer.url
                                serverOn = true
                                serverErr = ""
                            } catch (e: Exception) {
                                serverErr = "启动失败：${e.message}（18080-18089 全被占用）"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (toast.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(toast, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "导入 SFA 前建议先用 sing-box check 过一遍；check 通过不等于 SFA 一定能启动，以设备实际运行为准。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
