# SBox 配置助手

安卓 App：图形界面配 sing-box 节点与分流规则，一键生成完整 sing-box JSON，分享/保存后导入官方 sing-box 安卓客户端（SFA）使用。

**只做一件事**：链接转换 + 图形化分流。不做 VPN、不做测速、不做订阅管理。

## 功能（v3.1.3）

v3 新增：
- **规则模版**：极简分流（国内直连、其余走代理）/ 原版完整（34 条）一键切换，可保存自定义模版；新装机默认极简。
- **引用裁剪**：生成时只保留被规则实际引用的分组选择器，空组自动剔除。
- **规则命中测试**：输入域名/IP（支持完整 URL），看命中哪条规则、走哪个出口；geosite/geoip 本地无库时明确标注"无法本地判定"。
- **节点探测**：vless/trojan 做协议级握手验证（区分"有效/认证失败/不通"）；其余协议标"仅连通"。
- **本地 HTTP 服务**：127.0.0.1:18080/config.json，可作 SFA 远程配置导入。

v2 功能：

- **节点**：粘贴节点链接 / 订阅内容 / sing-box JSON，或订阅 URL 拉取、**从文件导入**（txt/json/yaml/yml/conf，类型可选、默认自动识别）。URI 支持 vless / vmess / ss / trojan / trojan-go / hysteria(v1) / hysteria2 / tuic / wireguard / socks / http(s)代理 / ssh / anytls；clash YAML（只转 `proxies:`）、sing-box JSON（提取 outbounds）、WireGuard conf、ss JSON 订阅。**容错解析**：坏行跳过不整体失败，导入后显示"成功 N 个，跳过 M 行"（可展开看原因）。节点支持**长按多选删除**、**一键清空**、改名；**前置代理**（链式代理，生成 `detour`，环路自动检测丢弃）；**延迟检测**（TCP＋TLS 握手，结果仅标注不自动删；UDP 系协议仅供参考）。
- **分流**：规则组列表（开关、排序、删除，每行点选出口）；**内置规则分区**：模板自带 34 条各一个开关＋一键全关/全开，全关即纯自定义模式。规则编辑器 = 条件清单（域名后缀/完整/关键字/正则、IP CIDR、端口、协议、应用包名、geosite/geoip、自定义 rule_set URL）+ AND/OR 开关 + 出口绑定；生成时包成 sing-box `logical` 规则，并同步生成 DNS 联动规则。学自 Karing 的规则编辑器设计。
- **DNS**：四组服务器（解析器/出站/直连/代理，可增删改）＋解析模式三态（fakeip/proxy/direct）；fakeip 模式会提醒"SFA 刷新远程配置前先断开"。
- **生成**：完整 JSON 预览、生成前自检（出口引用不悬空、空组剔除、detour 环路、DNS 引用）、复制 / 保存文件 / 系统分享。

内置模板与远程 `/custom-config` 同款：DNS（ali/google/local）、34 条路由规则、分组结构（默认/AI/AI应用/账号/Google/Final/手动/自动/GLOBAL/直连）。

### 明确不支持的（诚实说明）

- **ssr://**：可识别但跳过——sing-box 官方构建无 `with_shadowsocksr` 编译标签，官方客户端（SFA）跑不起来。
- **QuantumultX / Surge / Loon 等 iOS 专有格式**：不做。
- **DNS 全局 TTL**：sing-box 官方 schema 没有该字段，不做。
- **EDNS Client Subnet**：sing-box 1.14 支持但 Karing 的 singbox 导出口味主动去掉（兼容性），v2 不做，留 v3。
- v2 删除了"按应用分流"页（SFA 自带该功能）；规则编辑器里的应用包名条件保留。

## 构建

需要 JDK 17 + Android SDK（platform 34, build-tools 34.0.0）+ Gradle 8.7：

```bash
export JAVA_HOME=~/jdk17
export ANDROID_HOME=~/android-sdk
./gradlew assembleDebug   # 或 gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## License

GPL-3.0-or-later（sing-box 生态惯例）。
