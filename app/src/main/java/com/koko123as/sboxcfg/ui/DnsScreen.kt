package com.koko123as.sboxcfg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.DnsConfig
import com.koko123as.sboxcfg.model.DnsServer

/**
 * ⑥ DNS 设置页：四组服务器＋解析模式三态。
 * 字段模型对标 Karing SettingConfigItemDNS；诚实删减：
 * sing-box 官方 schema 无全局 TTL 字段 → 不做 TTL；
 * client_subnet（EDNS）Karing singbox 口味主动去掉 → v2 不做，留 v3。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsScreen(
    data: AppConfigData,
    onUpdate: ((AppConfigData) -> AppConfigData) -> Unit
) {
    val dns = data.dns
    var editing by remember { mutableStateOf<Pair<String, DnsServer?>?>(null) } // group → server(null=新增)
    var deleting by remember { mutableStateOf<Pair<String, DnsServer>?>(null) }

    fun updateDns(fn: (DnsConfig) -> DnsConfig) {
        onUpdate { d -> d.copy(dns = fn(d.dns)) }
    }

    fun groupList(group: String): List<DnsServer> = when (group) {
        "resolver" -> dns.resolver
        "outbound" -> dns.outbound
        "direct" -> dns.direct
        else -> dns.proxy
    }

    fun setGroup(group: String, list: List<DnsServer>) {
        updateDns { c ->
            when (group) {
                "resolver" -> c.copy(resolver = list)
                "outbound" -> c.copy(outbound = list)
                "direct" -> c.copy(direct = list)
                else -> c.copy(proxy = list)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("DNS 设置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "四组服务器对应不同用途的解析；fakeip 模式会给代理域名返回假 IP（SFA 刷新远程配置前请先断开）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text("代理域名解析模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
        }
        item {
            val modes = listOf(
                "fakeip" to "fakeip（推荐，防 DNS 污染）",
                "proxy" to "proxy（经代理正常解析）",
                "direct" to "direct（直连解析）"
            )
            Column {
                modes.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = dns.resolveMode == value,
                            onClick = { updateDns { it.copy(resolveMode = value) } }
                        )
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "测试域名：${dns.testDomain}（预留，v2 未做测速）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        val groups = listOf(
            "resolver" to "解析器（解析节点域名用）",
            "outbound" to "出站（通用）",
            "direct" to "直连（直连流量用）",
            "proxy" to "代理（代理流量用，也是 dns.final）"
        )
        groups.forEach { (key, title) ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editing = key to null }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加")
                    }
                }
            }
            val list = groupList(key)
            if (list.isEmpty()) {
                item {
                    Text(
                        "（空）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }
            items(list, key = { it.tag }) { s ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.tag, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "${s.type} · ${s.address.ifEmpty { "（无）" }}" +
                                    if (s.detour.isNotEmpty()) " · 经由 ${s.detour}" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editing = key to s }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { deleting = key to s }) {
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
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "说明：sing-box 官方 schema 没有全局 TTL 字段，本页不做 TTL；" +
                    "EDNS Client Subnet 暂不支持（v3 再议）。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 新增/编辑服务器弹窗
    editing?.let { (group, server) ->
        var tag by remember(server) { mutableStateOf(server?.tag ?: "") }
        var type by remember(server) { mutableStateOf(server?.type ?: "https") }
        var address by remember(server) { mutableStateOf(server?.address ?: "") }
        var detour by remember(server) { mutableStateOf(server?.detour ?: "") }
        var err by remember { mutableStateOf("") }
        val types = listOf("https", "tls", "tcp", "udp", "local")
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (server == null) "添加 DNS 服务器" else "编辑 DNS 服务器", fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it },
                        label = { Text("tag（唯一）", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // ① v2.1 修复：类型 chips 改用 FlowRow 自动换行，避免弹窗过窄时 "local" 被竖向压缩
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        types.forEach { t ->
                            AssistChip(
                                onClick = { type = t },
                                label = { Text(t, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                    if (type != "local") {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = {
                                Text(
                                    if (type == "https") "地址（IP 或完整 URL）" else "地址（IP）",
                                    fontSize = 12.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    if (type == "https") "223.5.5.5 或 https://…/dns-query" else "8.8.8.8",
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = detour,
                        onValueChange = { detour = it },
                        label = { Text("detour（前置代理 tag，空=直连）", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    if (err.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = tag.trim()
                    if (t.isEmpty()) {
                        err = "tag 不能为空"
                        return@TextButton
                    }
                    if (type != "local" && address.trim().isEmpty()) {
                        err = "地址不能为空"
                        return@TextButton
                    }
                    val list = groupList(group).toMutableList()
                    val exists = list.any { it.tag == t && it.tag != server?.tag }
                    if (exists) {
                        err = "tag「$t」已存在"
                        return@TextButton
                    }
                    val ns = DnsServer(t, type, address.trim(), detour.trim())
                    if (server == null) list.add(ns)
                    else list.replaceAll { if (it.tag == server.tag) ns else it }
                    setGroup(group, list)
                    editing = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    deleting?.let { (group, s) ->
        ConfirmDialog(
            text = "删除 DNS 服务器「${s.tag}」？引用它的规则可能解析失败。",
            onConfirm = {
                setGroup(group, groupList(group).filter { it.tag != s.tag })
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}
