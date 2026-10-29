package com.koko123as.sboxcfg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koko123as.sboxcfg.config.ConfigBuilder
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.RuleConditions
import com.koko123as.sboxcfg.model.RuleGroup

private fun parseLines(s: String): List<String> =
    s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    draft: RuleGroup,
    data: AppConfigData,
    onDraftChange: (RuleGroup) -> Unit,
    onSave: (RuleGroup) -> Unit,
    onCancel: () -> Unit,
    onPickPackages: () -> Unit
) {
    val available = remember(data) { ConfigBuilder.availableOutboundTags(data) }
    var showOutboundPicker by remember { mutableStateOf(false) }
    var showGeositePicker by remember { mutableStateOf(false) }
    var showGeoipPicker by remember { mutableStateOf(false) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val c = draft.conditions
    fun setConditions(nc: RuleConditions) = onDraftChange(draft.copy(conditions = nc))

    fun doSave() {
        val errs = mutableListOf<String>()
        val name = draft.name.trim()
        if (name.isEmpty()) errs.add("规则组名称不能为空")
        if (c.isEmpty()) errs.add("至少填写一种匹配条件")
        val codeRe = Regex("^[A-Za-z0-9@!_.\\-]+$")
        for (code in c.geosite) {
            if (!codeRe.matches(code)) errs.add("geosite 代码不合法：$code")
        }
        for (code in c.geoip) {
            if (!codeRe.matches(code)) errs.add("geoip 代码不合法：$code")
        }
        for (u in c.ruleSetUrls) {
            val lu = u.lowercase()
            if (!lu.startsWith("https://")) errs.add("规则集 URL 必须以 https:// 开头：$u")
            else if (!(lu.endsWith(".srs") || lu.endsWith(".json")))
                errs.add("规则集 URL 必须以 .srs 或 .json 结尾：$u")
        }
        for (p in c.ports) {
            val norm = p.replace("：", ":").replace("—", "-").replace("－", "-")
            if (!Regex("^\\d+([:-]\\d+)?$").matches(norm))
                errs.add("端口格式不合法：$p（示例：443 或 8000-8100）")
        }
        if (errs.isNotEmpty()) {
            validationErrors = errs
            return
        }
        // 规范化：geosite/geoip 去掉用户可能手输的前缀
        val norm: (String) -> String = { it.removePrefix("geosite:").removePrefix("geoip:").trim() }
        onSave(
            draft.copy(
                name = name,
                conditions = c.copy(
                    geosite = c.geosite.map(norm).filter { it.isNotEmpty() }.distinct(),
                    geoip = c.geoip.map(norm).filter { it.isNotEmpty() }.distinct()
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.name.isEmpty()) "新建规则组" else "编辑规则组", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = ::doSave) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("基本")
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                label = { Text("规则组名称", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Text("多条件关系", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = draft.mode == "or",
                    onClick = { onDraftChange(draft.copy(mode = "or")) },
                    label = { Text("满足任一条件") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = draft.mode == "and",
                    onClick = { onDraftChange(draft.copy(mode = "and")) },
                    label = { Text("满足全部条件") }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("出口：", fontSize = 14.sp)
                AssistChip(
                    onClick = { showOutboundPicker = true },
                    label = { Text(draft.outboundTag) }
                )
            }

            SectionHeader("域名（每行一个）")
            MultiLineInput(
                label = "域名后缀",
                placeholder = "google.com\nyoutube.com",
                text = c.domainSuffix.joinToString("\n"),
                onTextChange = { setConditions(c.copy(domainSuffix = parseLines(it))) }
            )
            MultiLineInput(
                label = "完整域名",
                placeholder = "www.example.com",
                text = c.domain.joinToString("\n"),
                onTextChange = { setConditions(c.copy(domain = parseLines(it))) }
            )
            MultiLineInput(
                label = "域名关键字",
                placeholder = "telegram",
                text = c.domainKeyword.joinToString("\n"),
                onTextChange = { setConditions(c.copy(domainKeyword = parseLines(it))) }
            )
            MultiLineInput(
                label = "域名正则",
                placeholder = "^.*\\.example\\.com$",
                text = c.domainRegex.joinToString("\n"),
                onTextChange = { setConditions(c.copy(domainRegex = parseLines(it))) }
            )

            SectionHeader("IP / 端口 / 协议")
            MultiLineInput(
                label = "IP CIDR（每行一个）",
                placeholder = "8.8.8.8/32\n10.0.0.0/8",
                text = c.ipCidr.joinToString("\n"),
                onTextChange = { setConditions(c.copy(ipCidr = parseLines(it))) }
            )
            MultiLineInput(
                label = "端口（每行一个，可写范围如 8000-8100）",
                placeholder = "443\n8000-8100",
                text = c.ports.joinToString("\n"),
                onTextChange = { setConditions(c.copy(ports = parseLines(it))) }
            )
            Text("网络协议", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("tcp", "udp").forEach { proto ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = c.protocols.contains(proto),
                            onCheckedChange = {
                                val next = if (it) c.protocols + proto else c.protocols - proto
                                setConditions(c.copy(protocols = next))
                            }
                        )
                        Text(proto.uppercase(), fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }

            SectionHeader("应用包名")
            if (c.packageNames.isEmpty()) {
                Text("未选择应用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow {
                    c.packageNames.forEach { pkg ->
                        AssistChip(
                            onClick = {},
                            label = { Text(pkg, fontSize = 12.sp) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    setConditions(c.copy(packageNames = c.packageNames - pkg))
                                }) { Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.padding(0.dp)) }
                            },
                            modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            TextButton(onClick = onPickPackages) { Text("+ 选择应用（${c.packageNames.size} 已选）") }

            SectionHeader("规则集")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("geosite：", fontSize = 13.sp)
                AssistChip(
                    onClick = { showGeositePicker = true },
                    label = { Text(if (c.geosite.isEmpty()) "选择" else c.geosite.joinToString(", "), fontSize = 12.sp) }
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("geoip：", fontSize = 13.sp)
                AssistChip(
                    onClick = { showGeoipPicker = true },
                    label = { Text(if (c.geoip.isEmpty()) "选择" else c.geoip.joinToString(", "), fontSize = 12.sp) }
                )
            }
            MultiLineInput(
                label = "自定义规则集 URL（每行一个，https 开头、.srs/.json 结尾）",
                placeholder = "https://example.com/mylist.srs",
                text = c.ruleSetUrls.joinToString("\n"),
                onTextChange = { setConditions(c.copy(ruleSetUrls = parseLines(it))) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showOutboundPicker) {
        OutboundPickerDialog(
            title = "选择出口",
            options = available,
            current = draft.outboundTag,
            onPick = {
                onDraftChange(draft.copy(outboundTag = it))
                showOutboundPicker = false
            },
            onDismiss = { showOutboundPicker = false }
        )
    }
    if (showGeositePicker) {
        CodeMultiSelectDialog(
            title = "选择 geosite",
            hint = "勾选常用代码，或在下方手输补充",
            codes = CommonCodes.GEOSITE,
            selected = c.geosite.toSet(),
            prefix = "geosite:",
            onConfirm = {
                setConditions(c.copy(geosite = it.toList()))
                showGeositePicker = false
            },
            onDismiss = { showGeositePicker = false }
        )
    }
    if (showGeoipPicker) {
        CodeMultiSelectDialog(
            title = "选择 geoip",
            hint = "勾选常用代码，或在下方手输补充",
            codes = CommonCodes.GEOIP,
            selected = c.geoip.toSet(),
            prefix = "geoip:",
            onConfirm = {
                setConditions(c.copy(geoip = it.toList()))
                showGeoipPicker = false
            },
            onDismiss = { showGeoipPicker = false }
        )
    }
    if (validationErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { validationErrors = emptyList() },
            title = { Text("保存失败", fontSize = 16.sp) },
            text = {
                Column {
                    validationErrors.forEach { Text("• $it", fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { validationErrors = emptyList() }) { Text("回去修改") }
            }
        )
    }
}
