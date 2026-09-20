# ProTun

一个轻量、简洁的 Android VPN 代理客户端。内嵌 [mihomo](https://github.com/MetaCubeX/mihomo)（Clash.Meta 内核），导入一份 `clash.yml` 订阅即可使用**完整多协议栈** —— vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard 等。TUN 由 mihomo 自己接管，App 只提供 VPN 文件描述符和一个 `protect()` 回调，因此路由、DNS 劫持、rule-providers 全部在内核里运行。

- 应用名：**ProTun**（应用图标下的名字）
- 包名 / 源码包：`com.tunvpn`


## 功能特性

* **多协议**：mihomo 支持的全部节点类型 —— vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard 等。
* 转发 TCP 连接与 UDP 数据包。
* **两种上游，二选一**：远程 `clash.yml` 订阅，或手动 SOCKS5 服务器。
* **Clash 订阅**：导入远程 `clash.yml`（明文或 base64），多个订阅合并为**一个节点池**，经内核做延迟测速，按国家 / 协议筛选、按延迟排序（只列出可用节点）。
* **不重连切换节点**：选中节点立即作用于正在运行的隧道。
* 首页与常驻通知显示**内核真正在用的节点**（实时速率始终可见）。
* IPv4 / IPv6 双栈，全局 / 分应用模式。
* 流量统计（实时速率、本次会话、累计用量、分应用用量）。
* **App 接管路由**：「规则」页生成 mihomo 的 `rules:` 段 —— 域名后缀 / 域名关键字 / GeoIP 国家 / IP / 进程名，各可指定走代理或直连；内含路由策略（规则模式 / 全局代理 / 全局直连）与一键「国内直连」预设。订阅自带的 `rules` / `rule-providers` 不生效，只合并节点。
* 底部导航栏、多配置档（最多 13 套）、快捷设置磁贴、开机自启。

## 文档

* [文档索引](docs/README.md)
* [用户手册](docs/用户手册.md)
* [开发与架构](docs/开发与架构.md)
* [内核接口参考（mihomo external-controller API）](docs/内核接口参考.md)

## 如何构建

Fork 本项目后新建 release，或手动构建：

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

Docker 构建：

```
docker pull  mingc/android-build-box

docker run --rm \
  -v "$PWD":/project \
  -v "$HOME/.gradle-cache":/root/.gradle \
  -v "$HOME/android-ndk":/opt/android-sdk/ndk \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  mingc/android-build-box \
  bash -lc 'cd /project && ./gradlew assembleDebug --warning-mode all --no-daemon'
```

## SOCKS5 服务器（手动上游）

不使用订阅时（或在「服务器」页打开"把该 SOCKS5 作为上游"），App 会生成一份最小 mihomo 配置，只把这个服务器作为上游节点。

### TCP 承载 UDP 转发

```bash
git clone --recursive https://github.com/heiher/hev-socks5-server
cd hev-socks5-server
make

hev-socks5-server conf.yml
```

```yaml
main:
  workers: 4
  port: 1080
  listen-address: '::'

misc:
  limit-nofile: 65535
```

### UDP 承载 UDP 转发

任何实现了 RFC1928 中 CONNECT 与 UDP-ASSOCIATE 方法的 socks5 服务器都可以。
