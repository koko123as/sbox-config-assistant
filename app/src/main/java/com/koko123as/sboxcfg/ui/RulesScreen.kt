package com.koko123as.sboxcfg.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koko123as.sboxcfg.config.ConfigBuilder
import com.koko123as.sboxcfg.config.TemplateLoader
import com.koko123as.sboxcfg.config.TemplatePresets
import com.koko123as.sboxcfg.model.RuleTemplate
import java.util.UUID
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.BuiltInRule
import com.koko123as.sboxcfg.model.RuleGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    data: AppConfigData,
    onUpdate: ((AppConfigData) -> AppConfigData) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit
) {
    var deleting by remember { mutableStateOf<RuleGroup?>(null) }
    var pickingOutbound by remember { mutableStateOf<RuleGroup?>(null) }
    var showBuiltIn by remember { mutableStateOf(false) }
    val available = remember(data) { ConfigBuilder.availableOutboundTags(data) }

    fun setBuiltIn(index: Int, enabled: Boolean) {
        onUpdate { d ->
            d.copy(builtInRules = d.builtInRules.map {
                if (it.index == index) it.copy(enabled = enabled) else it
            })
        }
    }

    fun setAllBuiltIn(enabled: Boolean) {
        onUpdate { d ->
            d.copy(builtInRules = d.builtInRules.map { it.copy(enabled = enabled) })
        }
    }

    fun move(id: String, delta: Int) {
        onUpdate { d ->
            val list = d.ruleGroups.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j < 0 || j >= list.size) return@onUpdate d
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
            d.copy(ruleGroups = list)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加规则组")
            }
        }
    ) { padding ->
        val onCount = data.builtInRules.count { it.enabled }
        // v2.1 修复：整页改为单个 LazyColumn。之前外层是不可滚的 Column，
        // 展开的 34 条内置规则用 forEach 塞在里面，滑不动；现在它们是列表项，随整页滚动。
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            item {
                Text(
                    "每条规则组 = 一组匹配条件 + 一个出口，生成时包成 sing-box logical 规则。顺序越靠上优先级越高。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            // v3.0 规则模版卡片
            item {
                TemplateCard(
                    data = data,
                    onApplyTemplate = { t ->
                        onUpdate { d -> d.copy(builtInRules = t.rules, activeTemplateId = t.id) }
                    },
                    onSaveTemplate = { t ->
                        onUpdate { d ->
                            d.copy(
                                customTemplates = d.customTemplates.filter { it.id != t.id } + t,
                                activeTemplateId = t.id
                            )
                        }
                    },
                    onDeleteTemplate = { id ->
                        onUpdate { d ->
                            val rest = d.customTemplates.filter { it.id != id }
                            // 删的是当前激活模版 → 回退到极简
                            if (d.activeTemplateId == id) {
                                val m = TemplatePresets.minimal()
                                d.copy(customTemplates = rest, activeTemplateId = m.id, builtInRules = m.rules)
                            } else {
                                d.copy(customTemplates = rest)
                            }
                        }
                    }
                )
                Spacer(Modifier.height(4.dp))
            }
            // ② 内置规则分区：模板自带的 34 条，各一个开关＋一键全关/全开
            if (data.builtInRules.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { showBuiltIn = !showBuiltIn }
                            ) {
                                Text(
                                    "内置规则（模板自带 $onCount/${data.builtInRules.size} 开启）",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (showBuiltIn) "收起" else "展开",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            ButtonRow {
                                SecondaryButton(
                                    text = "一键全关",
                                    onClick = { setAllBuiltIn(false) },
                                    modifier = Modifier.weight(1f)
                                )
                                SecondaryButton(
                                    text = "一键全开",
                                    onClick = { setAllBuiltIn(true) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (onCount == 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "纯自定义模式：只生成你的自定义规则组",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (showBuiltIn) {
                    items(data.builtInRules, key = { it.index }) { b ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                b.label,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                color = if (b.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = b.enabled,
                                onCheckedChange = { setBuiltIn(b.index, it) }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
            // v3.1 规则命中测试
            item {
                HitTestCard(data = data)
                Spacer(Modifier.height(4.dp))
            }
            item {
                Text("自定义规则组", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                if (data.ruleGroups.isEmpty()) {
                    Text(
                        "还没有自定义规则组，点右下角 + 添加。\n模板自带的 34 条规则（YouTube/Netflix/Telegram 等）已内置，生成时自动包含。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
            itemsIndexed(data.ruleGroups, key = { _, g -> g.id }) { index, g ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clickable { onEdit(g.id) }
                            ) {
                                Text(g.name.ifEmpty { "（未命名）" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    g.conditions.summary() +
                                        " · ${if (g.mode == "and") "满足全部" else "满足任一"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = g.enabled,
                                onCheckedChange = { checked ->
                                    onUpdate { d ->
                                        d.copy(ruleGroups = d.ruleGroups.map {
                                            if (it.id == g.id) it.copy(enabled = checked) else it
                                        })
                                    }
                                }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("出口：", fontSize = 13.sp)
                            AssistChip(
                                onClick = { pickingOutbound = g },
                                label = { Text(g.outboundTag, fontSize = 13.sp) }
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { move(g.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = { move(g.id, 1) },
                                enabled = index < data.ruleGroups.size - 1
                            ) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = "下移")
                            }
                            IconButton(onClick = { deleting = g }) {
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
    }

    deleting?.let { g ->
        ConfirmDialog(
            text = "删除规则组「${g.name}」？",
            onConfirm = {
                onUpdate { d -> d.copy(ruleGroups = d.ruleGroups.filter { it.id != g.id }) }
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }

    pickingOutbound?.let { g ->
        OutboundPickerDialog(
            title = "选择出口",
            options = available,
            current = g.outboundTag,
            onPick = { tag ->
                onUpdate { d -> d.copy(ruleGroups = d.ruleGroups.map {
                    if (it.id == g.id) it.copy(outboundTag = tag) else it
                })
                }
                pickingOutbound = null
            },
            onDismiss = { pickingOutbound = null }
        )
    }
}

/**
 * v3.0 规则模版卡片：内置[极简分流][原版完整]一键切换；
 * [保存模版]把当前 builtInRules（含开关状态）存为自定义模版；
 * 自定义模版可应用/删除。
 */
@Composable
private fun TemplateCard(
    data: AppConfigData,
    onApplyTemplate: (RuleTemplate) -> Unit,
    onSaveTemplate: (RuleTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    val context = LocalContext.current
    val builtIns = remember {
        try {
            TemplatePresets.builtInTemplates(TemplateLoader.load(context))
        } catch (_: Exception) {
            listOf(TemplatePresets.minimal())
        }
    }
    val activeName = (builtIns + data.customTemplates)
        .find { it.id == data.activeTemplateId }?.name ?: "极简分流"
    var showSave by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                "规则模版（当前：$activeName）",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "极简分流：国内直连、其余走代理；原版完整：34 条完整规则。切换模版会替换下方开关。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            ButtonRow {
                for (t in builtIns) {
                    SecondaryButton(
                        text = t.name,
                        onClick = { onApplyTemplate(t) },
                        modifier = Modifier.weight(1f)
                    )
                }
                SecondaryButton(
                    text = "保存模版",
                    onClick = { nameInput = ""; showSave = true },
                    modifier = Modifier.weight(1f)
                )
            }
            if (data.customTemplates.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for (t in data.customTemplates) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "自定义：" + t.name,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            color = if (t.id == data.activeTemplateId)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(onClick = { onApplyTemplate(t) }) {
                            Text("应用", fontSize = 13.sp)
                        }
                        IconButton(onClick = { onDeleteTemplate(t.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除模版")
                        }
                    }
                }
            }
        }
    }

    if (showSave) {
        val tplJson = try { TemplateLoader.load(context) } catch (_: Exception) { null }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("保存为自定义模版", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("模版名称", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSave = false
                    val cur = TemplatePresets.findTemplate(data, tplJson)
                    onSaveTemplate(
                        RuleTemplate(
                            id = UUID.randomUUID().toString(),
                            name = nameInput.trim().ifEmpty { "自定义模版" },
                            finalOutbound = cur.finalOutbound,
                            rules = data.builtInRules,
                            isBuiltIn = false
                        )
                    )
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("取消") } }
        )
    }
}

/** v3.1 规则命中测试卡片：输入域名/IP，按生成时的规则顺序本地匹配 */
@Composable
private fun HitTestCard(data: AppConfigData) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<com.koko123as.sboxcfg.config.RouteTester.HitResult?>(null) }
    // data 变化时清空旧结果（规则变了，旧结论失效）
    androidx.compose.runtime.LaunchedEffect(data.builtInRules, data.ruleGroups, data.activeTemplateId) {
        result = null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Text("规则命中测试", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "输入域名或 IP，按当前生效规则顺序匹配，看走哪条规则、哪个出口。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("域名或 IP，如 google.com / 8.8.8.8", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                SecondaryButton(
                    text = "测试",
                    onClick = {
                        val tpl = try {
                            TemplateLoader.load(context)
                        } catch (_: Exception) {
                            null
                        }
                        result = com.koko123as.sboxcfg.config.RouteTester.test(data, input, tpl)
                    }
                )
            }
            result?.let { r ->
                Spacer(Modifier.height(6.dp))
                when {
                    r.matchedIndex == null && !r.undetermined && r.label == "输入无效" -> Text(
                        r.note ?: "输入无效",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    r.undetermined -> Column {
                        Text(
                            "可能命中第 ${(r.matchedIndex ?: 0) + 1} 条（无法本地判定）",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(r.label, fontSize = 12.sp)
                        Text(
                            "出口可能是 → ${r.outbound}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        r.note?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    r.matchedIndex != null -> Column {
                        Text(
                            "命中第 ${r.matchedIndex + 1} 条",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(r.label, fontSize = 12.sp)
                        Text("出口 → ${r.outbound}", fontSize = 13.sp)
                    }
                    else -> Column {
                        Text(
                            "未命中任何规则",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "走 final → ${r.outbound}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
