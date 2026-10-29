package com.koko123as.sboxcfg.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.NodeEntry
import com.koko123as.sboxcfg.parse.HandshakeValidator
import com.koko123as.sboxcfg.parse.LatencyChecker
import com.koko123as.sboxcfg.parse.NodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodesScreen(
    data: AppConfigData,
    onUpdate: ((AppConfigData) -> AppConfigData) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSkipped by remember { mutableStateOf(false) }
    var stat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<NodeEntry?>(null) }
    var deletingNode by remember { mutableStateOf<NodeEntry?>(null) }
    var showSubDialog by remember { mutableStateOf(false) }
    var subUrl by remember { mutableStateOf("") }
    // ④ 文件导入
    var showImportTypeDialog by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf(NodeParser.ImportType.AUTO) }
    // ① 多选模式
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearAll by remember { mutableStateOf(false) }
    // ⑧ 有效性验证结果（in-memory，不持久化）；v2.2：HandshakeValidator 四档结果
    var validations by remember { mutableStateOf<Map<String, HandshakeValidator.ValidationResult>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }

    fun exitSelectMode() {
        selectMode = false
        selected = emptySet()
    }

    fun mergeParsed(nodes: List<NodeEntry>, errs: List<String>, skips: List<String>) {
        val used = data.nodes.map { it.tag }.toMutableSet()
        val fixed = nodes.map { n ->
            var tag = n.tag
            if (!used.add(tag)) {
                var i = 2
                while (!used.add("$tag-$i")) i++
                tag = "$tag-$i"
            }
            n.copy(tag = tag)
        }
        if (fixed.isNotEmpty()) {
            onUpdate { d -> d.copy(nodes = d.nodes + fixed) }
        }
        errors = errs
        skipped = skips
        showSkipped = false
        stat = buildString {
            if (fixed.isNotEmpty()) {
                append("成功 ${fixed.size} 个节点（共 ${data.nodes.size + fixed.size} 个）")
            } else if (errs.isNotEmpty()) {
                append("没有解析出可用节点")
            }
            if (skips.isNotEmpty()) {
                if (isNotEmpty()) append("，")
                append("跳过 ${skips.size} 行")
            }
        }
    }

    fun doParse(text: String) {
        val r = NodeParser.parseText(text)
        mergeParsed(r.nodes, r.errors, r.skipped)
        if (r.nodes.isNotEmpty()) input = ""
    }

    fun doImportFile(uri: Uri) {
        busy = true
        stat = ""
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        ins.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalArgumentException("无法读取文件")
                }
                val r = withContext(Dispatchers.Default) {
                    NodeParser.parseFile(text, importType)
                }
                val errs = r.errors.toMutableList()
                if (r.nodes.isEmpty() && errs.isEmpty() && r.skipped.isEmpty())
                    errs.add("文件中没有识别出可用节点")
                mergeParsed(r.nodes, errs, r.skipped)
            } catch (e: Exception) {
                errors = listOf("文件读取失败：${e.message}")
                stat = ""
            } finally {
                busy = false
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImportFile(uri) }

    /** v2.2：协议握手有效性验证（vless/trojan 真握手，其余仅连通性） */
    fun startProbe(targets: List<NodeEntry>) {
        if (targets.isEmpty() || probing) return
        probing = true
        scope.launch {
            val results = HandshakeValidator.validateAll(targets)
            validations = validations + results.associate { it.tag to it }
            probing = false
        }
    }

    fun deleteNodes(tags: Set<String>) {
        onUpdate { d ->
            // ⑦ 删除节点时清空指向它的 detour
            d.copy(nodes = d.nodes.filter { it.tag !in tags }
                .map { if (it.detourTag in tags) it.copy(detourTag = null) else it })
        }
        validations = validations.filterKeys { it !in tags }
    }

    // ② v2.2：整页单个 LazyColumn（照抄 RulesScreen v2.1 模式），解决内容大时划不动
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectMode) {
            // ① 选择模式顶栏
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = ::exitSelectMode) {
                        Icon(Icons.Filled.Close, contentDescription = "退出选择")
                    }
                    Text(
                        "已选 ${selected.size} 项",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        selected = if (selected.size == data.nodes.size) emptySet()
                        else data.nodes.map { it.tag }.toSet()
                    }) {
                        Text(if (selected.size == data.nodes.size) "全不选" else "全选", fontSize = 13.sp)
                    }
                }
            }
            item {
                var showDelSel by remember { mutableStateOf(false) }
                ButtonRow {
                    SecondaryButton(
                        text = "删除选中（${selected.size}）",
                        onClick = { showDelSel = true },
                        enabled = selected.isNotEmpty(),
                        danger = true,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = if (probing) "验证中…" else "验证选中",
                        onClick = { startProbe(data.nodes.filter { it.tag in selected }) },
                        enabled = selected.isNotEmpty() && !probing,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showDelSel) {
                    ConfirmDialog(
                        text = "删除选中的 ${selected.size} 个节点？",
                        onConfirm = {
                            deleteNodes(selected)
                            exitSelectMode()
                            showDelSel = false
                        },
                        onDismiss = { showDelSel = false }
                    )
                }
            }
        } else {
            item {
                Text(
                    "粘贴节点链接 / 订阅内容（自动识别格式），或拉取订阅、从文件导入",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text("vless://…\nss://…\nhttps://订阅…（用拉取订阅）", fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = "解析",
                    onClick = { doParse(input) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ButtonRow {
                    SecondaryButton(
                        text = "拉取订阅",
                        onClick = { showSubDialog = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "文件导入",
                        onClick = { showImportTypeDialog = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                ButtonRow {
                    SecondaryButton(
                        text = if (probing) "验证中…" else "验证节点",
                        onClick = { startProbe(data.nodes) },
                        enabled = !busy && !probing && data.nodes.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "清空",
                        onClick = { showClearAll = true },
                        enabled = data.nodes.isNotEmpty(),
                        danger = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (busy) {
            item {
                Text("处理中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (stat.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (skipped.isNotEmpty()) {
                        TextButton(onClick = { showSkipped = !showSkipped }) {
                            Text(if (showSkipped) "收起" else "看原因", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (showSkipped && skipped.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        skipped.take(10).forEach {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (skipped.size > 10) Text("…等共 ${skipped.size} 行", fontSize = 12.sp)
                    }
                }
            }
        }
        if (errors.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        errors.take(8).forEach {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        if (errors.size > 8) Text("…等共 ${errors.size} 行", fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Text(
                "已解析节点（${data.nodes.size}）" + if (!selectMode) " · 长按多选" else "",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            if (validations.isNotEmpty()) {
                Text(
                    "验证节点做协议握手（vless/trojan）；其余协议仅测连通，供参考",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(data.nodes, key = { it.tag }) { n ->
            val isSel = selected.contains(n.tag)
            val v = validations[n.tag]
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = {
                            if (selectMode) {
                                selected = if (isSel) selected - n.tag else selected + n.tag
                            }
                        },
                        onLongClick = {
                            if (!selectMode) {
                                selectMode = true
                                selected = setOf(n.tag)
                            }
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectMode) {
                        Icon(
                            if (isSel) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = "选择",
                            tint = if (isSel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                n.tag,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (v != null) {
                                Spacer(Modifier.width(6.dp))
                                // v2.2：四档结果显示
                                when (v.validity) {
                                    HandshakeValidator.Validity.VALID -> Text(
                                        "有效 ${v.ms}ms",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    HandshakeValidator.Validity.AUTH_FAIL -> Text(
                                        "认证失败",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    HandshakeValidator.Validity.UNSUPPORTED -> if (v.ms != null) Text(
                                        "${v.ms}ms·仅连通",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ) else Text(
                                        "不通（${LatencyChecker.reasonText(v.reason)}）",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    HandshakeValidator.Validity.UNREACHABLE -> Text(
                                        "不通（${LatencyChecker.reasonText(v.reason)}）",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Text(
                            "${n.protocol} · ${n.server}:${n.port}" +
                                (n.detourTag?.let { " · 经由 $it" } ?: ""),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!selectMode) {
                        AssistChip(onClick = {}, label = { Text(n.protocol, fontSize = 11.sp) })
                        IconButton(onClick = { editingNode = n }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { deletingNode = n }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
    // 编辑节点弹窗：改名＋前置代理（⑦）
    editingNode?.let { n ->
        var newTag by remember(n.tag) { mutableStateOf(n.tag) }
        var detour by remember(n.tag) { mutableStateOf(n.detourTag ?: "") }
        var showDetourPick by remember { mutableStateOf(false) }
        val others = remember(data.nodes, n.tag) {
            data.nodes.map { it.tag }.filter { it != n.tag }
        }
        AlertDialog(
            onDismissRequest = { editingNode = null },
            title = { Text("编辑节点", fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("节点名称", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("前置代理（链式代理，可选）", fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showDetourPick = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (detour.isEmpty()) "无（直连落地）" else detour,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "该节点的流量先经由前置代理发出；环路会被自动检测并丢弃",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = newTag.trim()
                    if (t.isNotEmpty()) {
                        onUpdate { d ->
                            val oldTag = n.tag
                            val newDetour = detour.ifEmpty { null }
                            d.copy(nodes = d.nodes.map {
                                var x = it
                                if (it.tag == oldTag) x = it.copy(
                                    tag = t,
                                    detourTag = newDetour
                                )
                                // 改名后，指向旧 tag 的 detour 跟着改
                                if (x.detourTag == oldTag && x.tag != t) x = x.copy(detourTag = t)
                                x
                            })
                        }
                    }
                    editingNode = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingNode = null }) { Text("取消") } }
        )
        if (showDetourPick) {
            OutboundPickerDialog(
                title = "选择前置代理",
                options = listOf("（无）") + others,
                current = if (detour.isEmpty()) "（无）" else detour,
                onPick = {
                    detour = if (it == "（无）") "" else it
                    showDetourPick = false
                },
                onDismiss = { showDetourPick = false }
            )
        }
    }

    deletingNode?.let { n ->
        ConfirmDialog(
            text = "删除节点「${n.tag}」？引用它的规则组会生成警告，指向它的前置代理会被清空。",
            onConfirm = {
                deleteNodes(setOf(n.tag))
                deletingNode = null
            },
            onDismiss = { deletingNode = null }
        )
    }

    if (showClearAll) {
        ConfirmDialog(
            text = "清空全部 ${data.nodes.size} 个节点？此操作不可恢复。",
            onConfirm = {
                onUpdate { d -> d.copy(nodes = emptyList()) }
                validations = emptyMap()
                exitSelectMode()
                showClearAll = false
            },
            onDismiss = { showClearAll = false }
        )
    }

    // ④ 文件导入：类型选择
    if (showImportTypeDialog) {
        val types = listOf(
            NodeParser.ImportType.AUTO to "自动识别（推荐）",
            NodeParser.ImportType.CLASH to "Clash YAML",
            NodeParser.ImportType.SINGBOX to "sing-box JSON",
            NodeParser.ImportType.V2RAY to "v2ray 文本/订阅",
            NodeParser.ImportType.WIREGUARD to "WireGuard conf"
        )
        AlertDialog(
            onDismissRequest = { showImportTypeDialog = false },
            title = { Text("从文件导入", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        "选择文件类型（默认自动识别）；解析出的节点会合并进当前列表",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    types.forEach { (t, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = importType == t,
                                onClick = { importType = t }
                            )
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportTypeDialog = false
                    fileLauncher.launch(arrayOf("*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = { TextButton(onClick = { showImportTypeDialog = false }) { Text("取消") } }
        )
    }

    // 订阅拉取弹窗
    if (showSubDialog) {
        AlertDialog(
            onDismissRequest = { showSubDialog = false },
            title = { Text("拉取订阅", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…", fontSize = 13.sp) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = subUrl.trim()
                    if (url.isEmpty()) return@TextButton
                    showSubDialog = false
                    busy = true
                    stat = ""
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) {
                                NodeParser.fetchSubscription(url)
                            }
                            val r = withContext(Dispatchers.Default) {
                                NodeParser.parseText(text)
                            }
                            val errs = r.errors.toMutableList()
                            if (r.nodes.isEmpty() && errs.isEmpty() && r.skipped.isEmpty())
                                errs.add("订阅内容为空或无法识别")
                            mergeParsed(r.nodes, errs, r.skipped)
                        } catch (e: Exception) {
                            errors = listOf("订阅拉取失败：${e.message}，请手动复制订阅内容粘贴")
                            stat = ""
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("拉取") }
            },
            dismissButton = { TextButton(onClick = { showSubDialog = false }) { Text("取消") } }
        )
    }
}
