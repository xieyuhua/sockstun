# ProTun 文档

ProTun 是一个轻量的 Android VPN 代理客户端。它内嵌 **mihomo（Clash.Meta）** 作为代理引擎，App 只负责拉起 TUN、生成配置和界面，路由 / 协议握手 / 流量统计全部交给内核。因此订阅里的 **vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard** 等节点都能直接用，不止 SOCKS5。

| 项 | 值 |
| --- | --- |
| 应用名 | **ProTun**（应用图标下的名字） |
| 包名 / 源码包 | `com.tunvpn` |
| 内嵌内核 | `libmihomo-android v0.3.3`，对应 mihomo `v1.19.30` |
| 支持 ABI | `arm64-v8a`、`armeabi-v7a` |
| SDK | `minSdk 24`，`compileSdk / targetSdk 34` |

---

## 文档地图

| 文档 | 面向 | 内容 |
| --- | --- | --- |
| [用户手册](用户手册.md) | 使用者 | 安装与上手、订阅与测速、备份 / WebDAV、服务器、规则与 DNS、设置项、活跃连接 / 最近请求、故障速查 |
| [开发与架构](开发与架构.md) | 开发者 | 组件与进程、内嵌内核与动作桥、启动流程、配置生成、两层路由、关键文件速查 |
| [订阅与测速](订阅与测速.md) | 开发者 | 多订阅拉取与合并、备份 / 导入 / WebDAV、节点识别与选择生效链路、测速全链路与判定口径、稳定性约束、测速日志 |
| [运行与统计](运行与统计.md) | 开发者 | 统计采样、通知、活跃连接 / 最近请求、高频路径性能约定、持久化结构、日志、版本号 |
| [内核接口参考](内核接口参考.md) | 开发者 | mihomo external-controller API 全量接口与字段、本 App 实际开放的端点、坑位 |

建议读法：使用者只读《用户手册》；改代码从《开发与架构》进，遇到测速 / 订阅 / 统计再跳到对应分支篇。

---

## 三分钟上手

**A. 用订阅（推荐，多协议）**

1. **设置 → 订阅配置** → 「新增订阅」填地址（或粘贴 base64 / 备份文本）→ 返回。
2. 在「订阅配置」点「更新订阅」。
3. 回 **订阅** 页点「测速全部」，等节点出现延迟。
4. 在想用的节点上点「使用」。
5. 回 **首页** 点「连接」，在系统 VPN 授权框点确定。

**B. 只用手动服务器（不用订阅）**

1. 底部 **服务器** → 「新增服务器」→ 填名称 / 协议 / 地址 / 端口 → 保存（地址栏可直接粘 `socks5://user:pass@host:port`）。
2. 在列表里点该服务器的「**启用**」，它就成为上游（**忽略订阅**），已连接时立即生效。
3. 回首页点「连接」。

> Android 13+ 首次会请求**通知权限**，请允许，否则看不到常驻通知。

---

## 功能特性

**上游与节点**

- 两种上游，二选一：远程 `clash.yml` 订阅（可多份合并成一个池），或手动节点（SOCKS5 / 其它协议粘贴 clash 定义）。
- 订阅节点列表：拉取、测速、按国家 / 协议筛选、按延迟排序；默认只列**可用**节点，可在 设置 → 订阅 打开「显示不可用节点」。
- 长按节点可把它加入「服务器」列表（或直接启用、忽略订阅），非 SOCKS5 会连原始 clash 定义一起搬运。

**测速**

- 内核**真实转发**延迟（不是端口连通性）：可用 / 不可用 / 未测速三态。
- 大池也不卡：分组快测（内核并发拨测）+ TCP 负向预筛（只判死）+ 剩余节点串行权威探测；单轮互斥、进度跨页面存活、每 15 个节点增量落盘、卡住自动收轮。
- 时间旋钮只有一个「延迟测试超时」，另有「无响应判为不可用」决定超时算不算死。
- 自动选择：内核 `url-test` 定时测速自动用最快节点，也可按国家分组。

**生效链路**

- 选节点、切国家、启用服务器、编辑启用中的服务器、恢复备份都会**立即重建配置并生效**。
- 首页与常驻通知显示**内核真正在用的节点**（实时速率始终可见）。

**路由与 DNS**

- 两层路由：先按 App（全局 / 部分应用）决定谁进隧道，再按规则决定代理还是直连。
- 规则：域名后缀 / 精确域名 / 关键字 / GeoSite / GeoIP / IP / IP 段 / 进程名 / 进程路径 / 目标端口 / 源端口 / 网络类型 → 代理或直连；分流策略三选一（规则 / 全局代理 / 全局直连）+ 未命中默认。订阅自带的 `rules` / `rule-providers` 不生效。
- DNS：远程 DNS（fake-ip）或指定内网 DNS（redir-host + `respect-rules`），同一组 DNS 同时下发给内核与 VPN 网卡。
- IPv4 / IPv6 双栈；「UDP relay over TCP」适配只转发 TCP 的上游。

**备份与迁移**

- 订阅备份 / 服务器备份，可恢复（覆盖当前配置）或删除；WebDAV 远程备份 + 从 WebDAV 导入。
- base64 / clash.yml / 备份文本导入，「复制全部地址」「复制全部节点」迁移。

**其它**

- 流量统计（实时 / 本次 / 累计 / 代理专属 / 分应用）、活跃连接、最近请求。
- 局域网共享：开启「允许局域网连接」后，同网段设备可把本机当 HTTP / SOCKS5 代理（默认端口 7890）。
- 底部导航、快捷设置磁贴、开机自启、5 套主题。

> 说明：底层仍按「配置档」存储（上限 13 档），但当前界面没有配置档切换 / 新增入口，实际只使用第 0 档。详见 [用户手册](用户手册.md) §4 与 [运行与统计](运行与统计.md) §5.2。

---

## 项目结构

```
app/src/main/java/com/tunvpn/
├── TProxyService.java          # VpnService：建 TUN、拉起内核、生成配置、节点切换、统计、通知、动作桥（:native 进程）
├── MihomoConfig.java           # 生成 mihomo config.yaml（组 / 规则 / DNS / 端口）
├── ClashParser.java            # clash.yml 解析（块式 + 单行内联 + base64）
├── ClashNode.java              # 节点数据与序列化（含延迟）
├── NodeFormat.java             # 单节点 clash flow map ↔ JSON 互转
├── MainActivity.java           # 首页 + 底部导航
├── SubscribeActivity.java      # 订阅展示 / 测速 / 筛选 / 长按加入服务器 / 备份对话框
├── SubscribeConfigActivity.java# 多订阅管理、更新订阅、导入 / 复制 / WebDAV 导入
├── BackupActivity.java         # 备份管理（订阅备份 + 服务器备份）
├── TestConfigActivity.java     # 测速配置二级页
├── ServerListActivity.java / ServerEditActivity.java / SocksServer.java  # 手动上游
├── RulesHubActivity.java       # 规则页：分流作用域 + DNS + 路由规则
├── AppListActivity.java        # 部分应用模式选应用
├── SettingsActivity.java       # 设置页（分组入口）
├── LogActivity.java / ConfigActivity.java      # 日志 / 配置文件
├── ConnectionsActivity.java / RecentRequestsActivity.java  # 活跃连接 / 最近请求
├── CoreTestHost.java           # App 进程内的无 TUN 测速内核（testDelay / 分组快测）
├── ClashApiServer.java         # App 自建的回环 HTTP 控制接口（REST → 进程内桥）
├── TestLog.java / TestProgress.java  # 日志落盘 / 进程级测速进度
├── WebDav.java                 # WebDAV 备份上传 / 列目录 / 下载
├── Preferences.java            # 持久化（SocksPrefs + SocksStats）、默认值
├── GeoIp.java / MMDB.java / Country.java   # 节点国家识别
├── QSTileService.java / ServiceReceiver.java  # 磁贴 / 开机自启
└── ThemeManager.java / BaseActivity.java      # 主题与 Activity 基类
app/thirdparty/mihomo/                          # 从 AAR 解包的 classes.jar + jni/<abi>/*.so
app/thirdparty/libmihomo-android-v0.3.3.aar     # 首次构建自动下载（需联网）
```

---

## 构建

```bash
git clone --recursive <repo>
cd sockstun
./gradlew assembleDebug
```

首次构建会自动从 GitHub Releases 下载 `libmihomo-android-v0.3.3.aar` 到 `app/thirdparty/`，因此**需要联网**。打包任务（`assemble` / `bundle` / `build` / `install`）会自增 `versionName += 0.01`、`versionCode += 1`。

Docker 构建（可选）：

```bash
docker run --rm \
  -v "$PWD":/project \
  -v "$HOME/.gradle-cache":/root/.gradle \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  mingc/android-build-box \
  bash -lc 'cd /project && ./gradlew assembleDebug --no-daemon'
```

> 若在宿主机与容器之间来回构建而报 `java.io.IOException: Input/output error` 或 `sdk.dir ... Directory does not exist`，先在容器内自己的文件系统里构建（`cp -a /project /work`）、清掉 `app/build` / `build` / `.gradle` 与宿主机专属的 `local.properties`。详见根目录 [README](../README.md)。

---

## 术语速查

| 说法 | 在本项目里的准确含义 |
| --- | --- |
| **上游** | 隧道出口的来源，二选一：订阅节点池，或「服务器」页启用的那台手动服务器（启用即忽略订阅） |
| **节点池** | 所有**已启用**订阅的节点合并、去重后的集合；国家筛选会把它限制到某国 |
| **本地订阅 / 备份** | `local=true` 的订阅，内容直接存在本地、不联网更新；订阅备份与导入产生的都是它 |
| **`tunvpn` 组** | App 生成的顶层 `select` 策略组，内核的"当前节点"就取自它的 `now` |
| **`url-test` 组** | 自动模式下的国家组 / 全局组，由内核按 `interval` 定时测速自动挑最快的成员 |
| **测速内核** | App 进程内那个**无 TUN** 的第二个 mihomo 实例（`CoreTestHost`），专门用真实转发来量延迟 |
| **动作桥** | `Clash.INSTANCE.invokeAction()` 这条 JNI 通道，把 REST 语义的请求直接发给内核（单回调、需串行） |
| **mixed 端口** | 同时接受 HTTP 与 SOCKS5 的本地代理端口（默认 7890）；开「允许局域网连接」后才对同网段开放 |
| **fake-ip / redir-host** | 内核的两种 DNS 模式：前者把域名原样交给代理端解析（默认），后者用你指定的 DNS 拿真实 IP |
| **分流作用域** | 第一层路由：全局 / 部分应用，决定**哪些 App** 的流量进隧道 |
| **分流策略** | 第二层路由：规则模式 / 全局代理 / 全局直连，决定进了隧道的流量走代理还是直连 |
| **配置生成** | 每次连接（或重连）时由 `MihomoConfig` 写出的 `files/config.yaml`——改了要生效，就得重连 |

---

## 文档维护约定

- **单一事实源**：同一件事只在一篇里写全，其它篇只做一句摘要 + 链接。当前分工：界面操作 → 用户手册；机制与代码路径 → 开发与架构及其分支篇。
- **数字必须可核对**：默认值（超时、端口、上限、阈值）都要能在 `Preferences` / `MihomoConfig` 里找到对应常量。
- **改了行为就改文档**：涉及默认值、判定口径、跨进程约定、自动化 action 的改动，同一次提交里更新对应章节。

---

## 许可

内嵌的 mihomo 内核为 **GPL-3.0**（Go 静态链接），分发时请遵守相应许可。
