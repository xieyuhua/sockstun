# 代理池测速服务器（proxy-pool）

一个用 Go 编写的订阅聚合 / 测速 / 订阅生成服务：

- 支持添加**任意多个订阅地址**（Clash YAML、Base64 分享链接、明文链接列表都能识别）
- 把多个订阅**合并成一个节点池**，自动去重、按地区归类命名
- 通过 **mihomo 内核真实测速**（HTTP 延迟 + 下载速度），只保留真正能连通的节点
- 输出一份标准的 **Clash / mihomo 配置文件**，可直接作为订阅地址填进客户端
- 支持**手动触发**与**定时自动生成**，附带可视化管理页面

## 快速开始

```bash
cd proxy-pool

# 1) 编译
go build -o bin/proxypool.exe .        # Windows
go build -o bin/proxypool .            # Linux / macOS

# 2) 运行（首次运行会自动生成 config.yaml）
./bin/proxypool.exe -c config.yaml

# 3) 打开管理页面
#    http://127.0.0.1:18080/
```

管理页面可以：

- 添加 / 停用 / 删除订阅，单独测试某个订阅能解析出多少节点
- 一键「立即测速生成」，实时查看阶段进度与日志
- 查看节点池的延迟、速度、地区与失败原因
- 对任意节点点「测试」，单独用内核探测它（含本机到该节点端口的 TCP 可达性）
- 在线修改测速阈值、输出路径、定时间隔等设置

生成好的配置文件同时通过下面三个地址对外提供（可直接填进 Clash / mihomo 作为订阅）：

```
http://<本机IP>:18080/clash.yaml
http://<本机IP>:18080/sub
http://<本机IP>:18080/api/clash
```

### 命令行参数

| 参数 | 说明 |
| --- | --- |
| `-c` | 配置文件路径，默认 `config.yaml` |
| `-listen` | 覆盖监听地址，例如 `0.0.0.0:18080` |
| `-token` | 覆盖访问令牌（仅本次运行生效） |
| `-once` | 执行一次「拉取 + 测速 + 生成」后退出，适合配合计划任务 |
| `-no-scheduler` | 禁用定时任务（仅本次运行生效） |

配合系统计划任务使用：

```bash
# 每 30 分钟更新一次，不开常驻服务
./proxypool -c config.yaml -once
```

## 工作流程

```
订阅A ┐
订阅B ├─► 拉取+解析 ─► 合并去重 ─► 地区识别+命名 ─► 内核测速 ─► 阈值筛选 ─► 生成 clash.yaml
订阅C ┘   (多格式)     (节点池)     (🇭🇰HK-001)    (延迟/速度)   (只留可用)   (含分组规则)
```

## 测速引擎

### core 模式（推荐）

自动拉起 mihomo 内核，对每个节点做：

1. **延迟探测**：通过内核 REST API `GET /proxies/{name}/delay` 真实请求 `delay_url`
2. **下载测速**：把节点切入代理组，从本地混合端口下载测速文件并计算 MB/s

只有真正能走通外网的节点才会被判定可用，因此「一定可以使用」是有保障的。
节点会按延迟分批交给多个内核实例并行测试。

内核缺失时会自动从 GitHub Release 下载到 `speedtest.core.path`。
如果直连 GitHub 失败，配置一个加速前缀即可：

```yaml
speedtest:
  core:
    auto_download: true
    gh_proxy: https://ghproxy.net/
```

也可以手动下载 mihomo 放到 `bin/mihomo.exe`（Windows）/ `bin/mihomo`（Linux、macOS）。

### tcp 模式（兜底）

只做 TCP 握手探测，无需内核，速度快，但**只能证明端口可达**，不能证明代理可用。
当 core 模式不可用（内核下载失败、启动失败）时会自动回落到该模式，并在日志中给出警告。

### 全部节点探测失败时的自检

如果一整轮探测下来**没有任何节点成功**，程序会自动做一次自检并把结论写进日志：

```
[WARN] 本轮没有任何节点探测成功，开始自检…
[INFO] 本机到节点端口的 TCP 连通性抽检：5/6 可达
[INFO] 探测失败原因统计：超时 ×440、握手或协议错误 ×8
[INFO] 探测地址可达性：http://www.gstatic.com/generate_204（本机直连不可达）
[INFO] 探测地址可达性：http://cp.cloudflare.com/generate_204（本机直连可达）
[INFO] 发现更合适的探测地址 http://cp.cloudflare.com/generate_204（抽样成功 2/6），换地址重新测速…
[INFO] 换用 http://cp.cloudflare.com/generate_204 后可用节点 137 个
```

自检包含三件事：

1. **TCP 连通性抽检**：绕开内核，直接检测本机到节点端口的连通性。
   若结果是 `0/N 可达`，说明本机根本连不上这些服务器（防火墙拦截内核进程、网络环境受限，或订阅已失效），
   与探测地址无关。
2. **探测地址可达性**：分别测试当前地址与候选地址（本机直连与抽样节点各测一次）。
3. **自动换址重试**：若发现某个候选地址能探测成功而当前地址不行，会立刻换地址重跑一轮，
   并把可用节点写进配置。可通过 `speedtest.delay_urls` 自定义候选地址。

失败原因的含义：

| 原因 | 含义 |
| --- | --- |
| `超时` | 该节点在 timeout 内没有完成一次探测请求（服务器被墙、节点已失效或线路拥塞） |
| `握手或协议错误` | 连接被拒绝、TLS/协议握手失败、探测目标返回了非 204 状态码 |
| `本地请求超时` | 本机与内核之间通信异常 |

为避免几百个节点的失败信息刷屏，逐条日志最多打印 20 条，其余只做统计汇总。

## 支持的订阅格式

| 类型 | 说明 |
| --- | --- |
| Clash / mihomo YAML | 直接读取其中的 `proxies` 段，完整保留协议参数 |
| Base64 分享链接 | 自动解码（标准 / URL-safe / 无填充）后逐行解析 |
| 明文分享链接 | `ss` `ssr` `vmess` `vless` `trojan` `hysteria` `hysteria2` `tuic` `socks5` |

解析时只做格式转换，不做协议改写；无法被 mihomo 支持的节点类型会在合并阶段被丢弃并计入统计。

## 节点命名

默认按「地区旗帜 + 地区代码 + 序号」重命名，例如 `🇭🇰HK-001`、`🇯🇵JP-002`，
便于在客户端里一眼分辨地区，也避免订阅里常见的广告名。

命名模板可自定义（`pool.name_format`）：

| 占位符 | 含义 | 示例 |
| --- | --- | --- |
| `{emoji}` | 地区旗帜 | 🇭🇰 |
| `{code}` | 地区代码 | HK |
| `{region}` | 地区中文 | 香港 |
| `{index}` / `{index:03d}` | 序号 | 1 / 001 |
| `{source}` | 来源订阅名 | 机场 A |
| `{name}` | 原始节点名 | 香港01-高速 |

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 可视化管理页面 |
| GET | `/clash.yaml`、`/sub`、`/api/clash` | 生成的 Clash 配置文件 |
| GET | `/api/status` | 运行状态、进度、上次生成统计、输出文件信息 |
| GET | `/api/nodes?all=1` | 节点池明细（`all=1` 时包含不可用节点） |
| GET | `/api/logs?since=N` | 增量拉取运行日志 |
| GET / PUT | `/api/config` | 读取 / 局部更新配置 |
| POST | `/api/reload` | 重新从磁盘加载配置文件 |
| GET / POST | `/api/subscriptions` | 列出 / 新增订阅 |
| PUT / DELETE | `/api/subscriptions/{id}` | 修改（启停、改名、换地址）/ 删除订阅 |
| POST | `/api/subscriptions/{id}/check` | 单独测试该订阅的可用性 |
| POST | `/api/run` | 立即执行一次生成；`?cache=1` 表示复用现有节点池只重新测速 |
| POST | `/api/probe` | 用内核单独探测某个节点：`{"name":"节点名"}`，返回各探测地址结果、本机直连结果、该节点端口的 TCP 可达性与内核日志 |

设置 `server.token` 后，所有接口（以及订阅地址）都需要带上 `?token=xxx` 或 `Authorization: Bearer xxx`。

## 配置说明

完整字段与注释见 [`config.example.yaml`](config.example.yaml)。几个关键项：

| 字段 | 作用 |
| --- | --- |
| `speedtest.max_delay` | 延迟上限（毫秒），超过即判定不可用 |
| `speedtest.download.min_speed_mb` | 下载速度下限（MB/s），低于即判定不可用 |
| `speedtest.download.top` | 只对延迟最优的前 N 个节点做下载测速，兼顾耗时 |
| `pool.max_nodes` | 最终写入配置的节点数上限 |
| `output.template` | 自定义 Clash 模板，代理组里用 `__NODES__` 代表全部节点 |
| `scheduler.interval` | 定时自动生成间隔 |
| `output.extra` | 原样追加到配置顶层的自定义字段 |

自定义模板示例（保留你熟悉的规则集与分组结构，只需在分组里写 `__NODES__`）：

```yaml
proxies: []
proxy-groups:
  - name: 🚀 节点选择
    type: select
    proxies: __NODES__
  - name: ♻️ 自动选择
    type: url-test
    url: http://www.gstatic.com/generate_204
    interval: 300
    proxies: __NODES__
rules:
  - GEOIP,CN,DIRECT
  - MATCH,🚀 节点选择
```

## 目录结构

```
proxy-pool/
├── main.go                     # 程序入口（服务模式 / -once 模式）
├── config.example.yaml         # 带注释的配置示例
├── internal/
│   ├── config/                 # 配置定义、默认值、并发安全读写
│   ├── subscription/           # 订阅拉取（含流量信息解析）
│   ├── parser/                 # Clash YAML 与各类分享链接解析
│   ├── pool/                   # 节点池：过滤、去重、地区识别、命名
│   ├── speedtest/              # 测速引擎（mihomo 内核 / TCP 兜底、内核自动下载）
│   ├── generator/              # Clash 配置生成（内置结构 / 自定义模板）
│   ├── runner/                 # 流程编排
│   ├── scheduler/              # 定时任务
│   ├── server/                 # HTTP 接口 + 内嵌管理页面
│   └── logx/                   # 带缓冲的日志
└── bin/                        # 编译产物与 mihomo 内核
```

## 常见问题

**没有可用节点？**
先看日志里自检结论（见上一节）。三种典型情况：

- `本机到节点端口的 TCP 连通性抽检：0/N 可达` → 本机连不上这些服务器：
  订阅已失效，或防火墙/安全软件拦截了 mihomo 子进程的出站连接（把内核加入白名单后再试），
  也可能当前网络环境无法直连这些节点。
- 端口可达但探测地址全部不可达 → 换探测地址，或把可用的地址固定到 `speedtest.delay_url`。
- 端口可达、探测地址也可达，但节点依旧全部失败 → 节点本身已失效，检查订阅是否过期。

**想快速判断是节点的问题还是内核的问题？**
把 `speedtest.mode` 临时改成 `tcp` 跑一次：如果 TCP 模式下大量节点可达而 core 模式 0 可用，
说明本机网络到节点是通的，问题出在代理握手或探测目标上。

**怀疑某个"明明能用"的节点被判成不可用？**
在节点列表里点该行的「测试」，会得到一份单节点报告：

```
节点 🇺🇸US-388（vmess de5.edge-9d7c.com:443）
本机到节点端口 TCP 不可达：dial tcp 1.2.3.4:443: i/o timeout
❌ http://www.gstatic.com/generate_204 → Timeout
本机直连可达 http://www.gstatic.com/generate_204 → 58 ms
```

对照方式：

| 报告内容 | 结论 |
| --- | --- |
| `本机到节点端口 TCP 不可达` | 本机连不上这台服务器（节点已下线、被墙，或防火墙拦了内核进程），与测速参数无关 |
| TCP 可达，但所有探测地址都失败 | 节点端口在线但代理握手/认证不通，通常是订阅参数失效或节点已过载 |
| TCP 可达，部分探测地址成功 | 节点可用，换用成功的那个地址即可（把它固定到 `speedtest.delay_url`） |
| 本机直连全部不可达 | 本机到外网的直连受限，只影响判断基准，不影响通过节点的探测 |

**客户端提示缺少 GeoIP / MMDB 数据？**
生成的配置启用了 `dns.fallback`（基于 GeoIP 的 DNS 回退，抗污染），容器/离线环境下客户端需要先下载
`country.mmdb`。不需要该特性时，在 `output.extra` 里覆盖 `dns` 段即可。

**订阅解析出 0 个节点？**
用管理页面上的「测试」按钮看具体报错；确认订阅地址是否需要特定 User-Agent，或是否已过期。

**测速很慢？**
降低 `speedtest.download.top`（只测最快的若干节点）、缩短 `download.duration`、提高
`speedtest.concurrency`（并行内核实例），或把 `max_delay` 调小以尽早淘汰慢节点。

## 测试

```bash
go test ./...
```

包含解析器单元测试、完整流程集成测试（本地模拟订阅 + 真实生成配置）以及 HTTP 接口回归测试。
