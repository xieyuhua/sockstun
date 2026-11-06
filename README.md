# ProTun

一个轻量、简洁的 Android VPN 代理客户端。内嵌 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta 内核），导入一份 `clash.yml` 订阅即可使用**完整多协议栈** —— vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard 等。TUN 由 mihomo 自己接管，App 只提供 VPN 文件描述符和一个 `protect()` 回调，因此路由、DNS 劫持、rule-providers 全部在内核里运行。

| 项 | 值 |
| --- | --- |
| 应用名 | **ProTun** |
| 包名 / 源码包 | `com.tunvpn` |
| 内嵌内核 | `libmihomo-android v0.3.3`（mihomo `v1.19.30`） |
| 支持 ABI | `arm64-v8a`、`armeabi-v7a` |

---

## 功能要点

- **多协议**：mihomo 支持的全部节点类型；转发 TCP 连接与 UDP 数据包。
- **两种上游，二选一**：远程 `clash.yml` 订阅（多份合并成一个节点池），或手动 SOCKS5 / 粘贴式节点（启用即忽略订阅）。
- **测速**：内核真实转发延迟，三态（可用 / 不可用 / 未测速）；大池用分组快测 + TCP 负向预筛加速，单轮互斥、进度跨页面存活、卡住自动收轮；阈值可在 设置 → 延迟测试配置 调整。
- **切换立即生效**：选节点、切国家、启用服务器、编辑启用中的服务器都会自动重建配置；首页与通知栏显示**内核真正在用的节点**。
- **备份与迁移**：订阅备份 / 服务器备份（可恢复 / 删除）、WebDAV 远程备份、base64 / clash.yml / 备份文本导入、「复制全部地址」「复制全部节点」。
- **App 接管路由**：「规则」页生成 mihomo 的 `rules:` 段 —— 域名后缀 / 精确域名 / 关键字 / GeoSite / GeoIP / IP / IP 段 / 进程名 / 进程路径 / 端口 / 网络类型 → 代理或直连；分流策略三选一（规则 / 全局代理 / 全局直连）+ 未命中默认。订阅自带的 `rules` / `rule-providers` 不生效。
- **DNS**：远程 DNS（fake-ip）或指定内网 DNS（redir-host + `respect-rules`）；同一组 DNS 同时下发给内核与 VPN 网卡。
- IPv4 / IPv6 双栈，全局 / 分应用模式；「UDP relay over TCP」适配只转发 TCP 的上游。
- 流量统计（实时 / 本次 / 累计 / 代理专属 / 分应用）、活跃连接、最近请求。
- **局域网共享**：开启「允许局域网连接」后，同网段设备可把本机当 HTTP / SOCKS5 代理（默认端口 7890，无鉴权，默认关闭）。
- 底部导航、快捷设置磁贴、开机自启、5 套主题。

---

## 文档

完整文档在 [`docs/`](docs/README.md)：

| 文档 | 面向 |
| --- | --- |
| [文档索引](docs/README.md) | 全部文档的入口与项目结构 |
| [用户手册](docs/用户手册.md) | 使用者：上手、订阅与测速、备份、服务器、规则、设置、故障速查 |
| [开发与架构](docs/开发与架构.md) | 开发者：组件与进程、内核动作桥、启动流程、配置生成、两层路由 |
| [订阅与测速](docs/订阅与测速.md) | 开发者：订阅合并与备份、节点选择生效链路、测速全链路与判定口径 |
| [运行与统计](docs/运行与统计.md) | 开发者：统计与通知、连接 / 最近请求、性能约定、持久化 |
| [内核接口参考](docs/内核接口参考.md) | 开发者：mihomo external-controller API、本 App 实际开放的端点、坑位 |

---

## 如何构建

```bash
git clone --recursive https://github.com/xieyuhua/sockstun
cd sockstun
gradle assembleDebug
```

> **注意 —— 内嵌的 mihomo 内核。** 代理引擎是预编译的
> [`libmihomo-android`](https://github.com/oviron/libmihomo-android) AAR
> （mihomo v1.19.30，在 `app/build.gradle` 中固定在 `v0.3.3`）。首次构建
> （或 `clean` 之后）会从 GitHub Releases 下载，因此**构建时需要联网**。
> 该 AAR 自带 `arm64-v8a` 与 `armeabi-v7a` 两个原生库，`app/build.gradle`
> 里的 `abiFilters` 与之一致。内嵌内核为 GPL-3.0 许可（Go 静态链接代码），
> 分发时请遵守相应许可。

打包任务（`assemble` / `bundle` / `build` / `install`）会自动把 `versionName += 0.01`、`versionCode += 1` 写回 `app/version.properties`。

### Docker 构建

```
docker pull  mingc/android-build-box

docker run --rm \
  -v "$PWD":/project \
  -v gradle-cache:/root/.gradle \
  -v "$HOME/android-ndk":/opt/android-sdk/ndk \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  mingc/android-build-box \
  bash -lc 'cp -a /project /work && cd /work \
            && rm -rf app/build build .gradle && rm -f local.properties \
            && ./gradlew assembleDebug --warning-mode all --no-daemon'
```

> 上面三条清理不是可选项，而是把「宿主机上的构建残留」和「宿主机专属的 SDK 路径」挡在容器外：
> - `app/build` / `build` / `.gradle` 里可能是**另一个操作系统**生成的增量状态。容器经 bind mount 读到它们后，AGP 在 `javaPreCompileDebug` 读写 `annotation_processor_list`、`tmp/*.uniqueId*` 时会报 `java.io.IOException: Input/output error`（跨系统挂载上做原子改名不可靠）。
> - `local.properties` 里是宿主机 SDK 路径（如 `D:\Android\Sdk`），且**优先级高于** `ANDROID_SDK_ROOT`，容器里会报 `sdk.dir ... Directory does not exist`。
> - `cp -a /project /work` 让编译发生在容器自己的文件系统；Gradle home 用命名卷 `gradle-cache` 而非宿主机目录，避免同类问题并明显更快。
>
> 若必须原地构建，至少先清残留并让 SDK 生效：
>
> ```bash
> rm -rf app/build build .gradle && rm -f local.properties   # 或 echo 'sdk.dir=/opt/android-sdk' > local.properties
> ./gradlew assembleDebug --no-daemon
> ```
>
> “SDK XML version 4 / only understands up to 3” 只是 cmdline-tools 比 AGP 更新带来的**警告**，可以忽略。

---

## 自建 SOCKS5 上游

不使用订阅时，可以把某台 SOCKS5 作为手动上游。可用的参考实现与配置见 [用户手册 · 附录 A](docs/用户手册.md#附录-a自建-socks5-上游服务器)。

---

## 许可

内嵌的 mihomo 内核为 **GPL-3.0**（Go 静态链接），分发时请遵守相应许可。
