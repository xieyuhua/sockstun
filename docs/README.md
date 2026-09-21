# ProTun 文档

ProTun 是一个轻量的 Android VPN 代理客户端。它内嵌 **mihomo（Clash.Meta）** 作为代理引擎，App 只负责拉起 TUN、生成配置和界面，路由/协议握手/流量统计全部交给内核。因此订阅里的 **vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard** 等节点都能直接用，不止 SOCKS5。

- 应用名：**ProTun**（应用图标下的名字）
- 包名 / 源码包：`com.tunvpn`
- 内嵌内核：`libmihomo-android v0.3.3`，对应 mihomo `v1.19.30`
- 支持 ABI：`arm64-v8a`、`armeabi-v7a`

---

## 文档导航

| 文档 | 面向 | 内容 |
| --- | --- | --- |
| [用户手册](用户手册.md) | 使用者 | 安装、订阅 / 手动服务器、规则分流、设置项、活跃连接 / 最近请求、常见问题 |
| [开发与架构](开发与架构.md) | 开发者 | 组件与进程、启动时序、配置生成、两层路由、内核接入（动作桥）、测速、统计、持久化 |
| [内核接口参考](内核接口参考.md) | 开发者 | mihomo external-controller API 全量接口与字段、访问方式、坑位 |

---

## 三分钟上手

**A. 用订阅（推荐，多协议）**

1. 底部 **订阅** → 点右上角 ⋮「订阅配置」新增订阅地址 → 返回点「拉取订阅」。
2. 点「测速全部」，等节点出现延迟。
3. 在想用的节点上点「使用」。
4. 回 **首页** 点「连接」，在系统 VPN 授权框点确定。

**B. 只用手动服务器（不用订阅）**

1. 底部 **服务器** → 「新增服务器」→ 填名称 / 协议 / 地址 / 端口 → 保存。
2. 在列表里点该服务器的「**启用**」，它就成为上游（**忽略订阅**）。
3. 回首页点「连接」。

> Android 13+ 首次会请求**通知权限**，请允许，否则看不到常驻通知。

---

## 功能特性

- 多协议节点：内核支持的全部协议。
- 两种上游，二选一：远程 `clash.yml` 订阅（可多个合并），或手动节点（SOCKS5 / 其它协议粘贴 clash 定义）。
- 订阅节点列表：拉取、测速、按国家/协议筛选，按延迟排序；默认只展示**可用**节点，可在 设置 → 订阅 打开「显示不可用节点」把它们一并列出。
- 长按节点可把它加入「服务器」列表（或直接启用、忽略订阅），非 SOCKS5 会连原始 clash 定义一起搬运。
- 测速可控：`单目标超时` / `每节点测速上限` / `无响应判为不可用` / `未连接时内核测速` 都能在 设置 → 订阅 调整（见用户手册 §8）。
- 自动选择：内核 `url-test` 定时测速，自动用最快节点；也可按国家分组选最快。
- 切换节点 / 国家立即生效：已连接时选节点直接切（切不过去会自动重建配置），切国家自动重连应用。
- 两层路由：先按 App（全局 / 部分应用）决定谁进隧道，再按规则决定代理还是直连。
- 路由规则：域名后缀 / 精确域名 / 关键字 / GeoIP / GeoSite / IP / IP 段 / 进程名 / 进程路径 / 端口 / 网络类型 → 代理或直连，含「国内直连」预设。
- 流量统计：实时 / 本次 / 累计；活跃连接与最近请求（带时间、去向、流量）。
- 常驻通知、快捷设置磁贴、开机自启、多配置档（最多 13 套）、5 套主题。

---

## 项目结构

```
app/src/main/java/com/tunvpn/
├── TProxyService.java       # VpnService：建 TUN、拉起内核、配置生成、统计、通知（:native 进程）
├── MihomoConfig.java        # 生成 mihomo config.yaml
├── ClashParser.java         # clash.yml 解析（块式 + 单行内联）
├── ClashNode.java           # 节点数据与序列化（含延迟）
├── MainActivity.java        # 首页 + 底部导航
├── SubscribeActivity.java   # 订阅：拉取 / 测速 / 筛选 / 选用节点
├── SubscribeConfigActivity.java  # 多订阅地址管理
├── ServerListActivity.java / ServerEditActivity.java / SocksServer.java  # 手动上游
├── RulesHubActivity.java    # 规则页：分流 + DNS + 路由规则
├── AppListActivity.java     # 部分应用模式选应用
├── SettingsActivity.java    # 设置页
├── LogActivity.java / ConfigActivity.java
├── ConnectionsActivity.java     # 活跃连接
├── RecentRequestsActivity.java  # 最近请求
├── Preferences.java         # 多配置档与持久化
├── GeoIp.java / MMDB.java / Country.java  # 节点国家识别
├── QSTileService.java / ServiceReceiver.java  # 磁贴 / 开机自启
└── ThemeManager.java / BaseActivity.java
app/thirdparty/mihomo/       # 从 AAR 解包的 classes.jar + jni/<abi>/*.so
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

---

## 许可

内嵌的 mihomo 内核为 **GPL-3.0**（Go 静态链接），分发时请遵守相应许可。
