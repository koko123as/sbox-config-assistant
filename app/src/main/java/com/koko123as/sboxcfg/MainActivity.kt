package com.koko123as.sboxcfg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.koko123as.sboxcfg.config.TemplateLoader
import com.koko123as.sboxcfg.config.TemplatePresets
import com.koko123as.sboxcfg.model.AppConfigData
import com.koko123as.sboxcfg.model.RuleGroup
import com.koko123as.sboxcfg.store.ConfigStore
import com.koko123as.sboxcfg.ui.AppPickerScreen
import com.koko123as.sboxcfg.ui.DnsScreen
import com.koko123as.sboxcfg.ui.GenerateScreen
import com.koko123as.sboxcfg.ui.NodesScreen
import com.koko123as.sboxcfg.ui.RuleEditorScreen
import com.koko123as.sboxcfg.ui.RulesScreen

class MainActivity : ComponentActivity() {

    private lateinit var store: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(filesDir)
        setContent {
            MaterialTheme {
                AppRoot(store)
            }
        }
    }
}

@Composable
fun AppRoot(store: ConfigStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf(store.load()) }
    // v3.0 规则模版初始化与迁移
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            val tplJson = TemplateLoader.load(context)
            val migrated = TemplatePresets.migrateOnLaunch(data, tplJson)
            if (migrated != data) {
                data = migrated
                store.save(data)
            }
        } catch (_: Exception) {
        }
    }
    fun update(fn: (AppConfigData) -> AppConfigData) {
        data = fn(data)
        store.save(data) // 每次变更即落盘，无保存按钮
    }

    var tab by remember { mutableStateOf(0) }
    // 规则编辑器草稿：null=关闭；编辑的是副本，保存/取消都不污染线上配置
    var draft by remember { mutableStateOf<RuleGroup?>(null) }
    var pickingPackages by remember { mutableStateOf(false) }

    BackHandler(enabled = pickingPackages) { pickingPackages = false }
    BackHandler(enabled = !pickingPackages && draft != null) { draft = null }

    val tabs = listOf(
        "节点" to Icons.Filled.Dns,
        "分流" to Icons.Filled.Tune,
        "DNS" to Icons.Filled.Public,
        "生成" to Icons.Filled.Build
    )

    Scaffold(
        bottomBar = {
            if (draft == null && !pickingPackages) {
                NavigationBar {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                pickingPackages -> {
                    AppPickerScreen(
                        initialSelected = draft?.conditions?.packageNames?.toSet() ?: emptySet(),
                        onDone = { selected ->
                            draft = draft?.copy(
                                conditions = draft!!.conditions.copy(
                                    packageNames = selected.toList()
                                )
                            )
                            pickingPackages = false
                        },
                        onBack = { pickingPackages = false }
                    )
                }
                draft != null -> {
                    RuleEditorScreen(
                        draft = draft!!,
                        data = data,
                        onDraftChange = { draft = it },
                        onSave = { saved ->
                            update { d ->
                                val exists = d.ruleGroups.any { it.id == saved.id }
                                d.copy(
                                    ruleGroups = if (exists)
                                        d.ruleGroups.map { if (it.id == saved.id) saved else it }
                                    else
                                        d.ruleGroups + saved
                                )
                            }
                            draft = null
                        },
                        onCancel = { draft = null },
                        onPickPackages = { pickingPackages = true }
                    )
                }
                else -> when (tab) {
                    0 -> NodesScreen(data = data, onUpdate = ::update)
                    1 -> RulesScreen(
                        data = data,
                        onUpdate = ::update,
                        onAdd = { draft = RuleGroup(name = "") },
                        onEdit = { id ->
                            data.ruleGroups.find { it.id == id }?.let { draft = it.copy() }
                        }
                    )
                    2 -> DnsScreen(data = data, onUpdate = ::update)
                    else -> GenerateScreen(data = data)
                }
            }
        }
    }
}
