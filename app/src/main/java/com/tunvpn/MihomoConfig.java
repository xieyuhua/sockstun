/*
 ============================================================================
 文件名  : MihomoConfig.java
 说明    : 按 App 自己的设置生成 mihomo（clash.meta）配置文件，并补上 Android 上必需的
           TUN / DNS / log 等段。

           分流由 App 自己掌握，而不是交给订阅：每份订阅**只贡献节点**，节点被合并成
           一个池，再由我们自己构造的组指向这个池（url-test = 自动挑最快，select = 严格
           使用用户选中的那个节点）。订阅自带的代理组与规则被**刻意忽略**（多份订阅本来
           也无法合并），代理还是直连由 App 的「路由规则」页决定。
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MihomoConfig {
	/* 我们自己构造的代理组名。App 的规则和"手动选节点"都通过这个名字解析。 */
	public static final String GROUP = "tunvpn";
	/* 覆盖全部节点的 url-test 组（自动模式下的"哪都快"选项）。 */
	public static final String GLOBAL_GROUP = GROUP + "-global";

	/* 某个 ISO-3166 两位国家码对应的"按国家" url-test 组名。 */
	public static String countryGroup(String cc) {
		return GROUP + "-" + cc;
	}
	/* mihomo 的 RESTful 控制接口端口。**绑回环 127.0.0.1**：在本构建上绑
	   0.0.0.0（所有接口）会**静默绑定失败**（症状是"9090 从没监听"，而绑 "*" 的
	   mixed-port 却正常），所以配置里一律写成 127.0.0.1。仍然设置 `secret` 鉴权。
	   启动时从 9090 扫到 9100、取第一个空闲端口：端口被别的进程占着时，mihomo 绑定
	   控制接口会**静默失败**（症状：隧道通了，但 /connections 连接被拒、所有计数器
	   恒为 0）。 */
	public static int API_PORT = 9090;

	/* 为 clash-api 找一个空闲的回环端口并存进 API_PORT，返回选中的端口。
	   整段范围都被占用时退回 9090，让 mihomo 报出真实的绑定错误，而不是我们瞎猜。 */
	public static int pickApiPort() {
		for (int p = 9090; p <= 9100; p++) {
			if (isPortFree(p)) {
				API_PORT = p;
				return p;
			}
		}
		API_PORT = 9090;
		return API_PORT;
	}

	/* 本机上 127.0.0.1:port 还没人监听时为 true。mihomo 把 external-controller
	   绑在 127.0.0.1，所以探测也必须用同一个地址：我们能在 127.0.0.1 上绑的端口，
	   mihomo 也能绑。这里就是按 mihomo 的方式绑一个一次性 socket 来试。 */
	private static boolean isPortFree(int port) {
		java.net.ServerSocket ss = null;
		try {
			ss = new java.net.ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(new java.net.InetSocketAddress("127.0.0.1", port));
			return true;
		} catch (Throwable e) {
			return false;
		} finally {
			if (ss != null) {
				try { ss.close(); } catch (Throwable ignore) { }
			}
		}
	}
	/* 自动重测间隔的上下限（超出即拒绝）。 */
	private static final int MIN_INTERVAL = 30;
	private static final int MAX_INTERVAL = 86400;

	/* 只编译一次：节点校验会在每次重建配置时对**每个**节点跑一遍
	   （几百个节点、每次启动要重建好几次）。 */
	private static final Pattern SHORT_ID =
		Pattern.compile("short-id\\s*:\\s*[\"']?([^\"'\\s,}\\]{]*)");
	/* mihomo 对被拒节点的报法是 "proxy <序号>: <原因>"。 */
	private static final Pattern PROXY_INDEX = Pattern.compile("proxy\\s+(\\d+)\\s*:");

	/* build() **实际写进配置**的节点数（已应用"手动服务器优先"、国家筛选、内核拒绝的
	   节点剔除与重名去重），供 describe() 的日志行使用。-1 = 还没构建过。
	   由生成配置的那两个函数（mergedConfig / manualSocksConfig）在**产出文本时**顺手
	   记下，而不是事后回头解析一遍，也不是让 describe() 去数订阅 —— 后者数出来的是
	   **打算用的**节点（被筛掉、被排除的也算在内），于是"筛选后一个都不剩"这种最该
	   看见的情况，日志里反而写着 600 个节点。 */
	private static volatile int lastBuiltNodes = -1;

	/* 把最终的 clash 配置写进 App 的 files 目录并返回该文件。
	   还没有配置任何上游时抛异常。 */
	public static File build(Context context, Preferences prefs) throws IOException {
		return build(context, prefs, null);
	}

	/* 同上，但会剔除内核已经拒绝过的节点名（见 TProxyService.rejectedNodes）：
	   否则只要有一个非法节点，mihomo 就会拒绝**整份**配置，隧道永远起不来。 */
	public static File build(Context context, Preferences prefs,
			java.util.Set<String> skip) throws IOException {
		/* 已启用的手动服务器优先；否则把所有订阅合并成一个节点池。 */
		SocksServer server = prefs.getActiveSocksServer();
		StringBuilder cfg = new StringBuilder(
			server != null ? manualSocksConfig(prefs, server) : mergedConfig(prefs, skip));
		if (!cfg.toString().endsWith("\n"))
		  cfg.append('\n');

		/* DNS：没有订阅可以继承 DNS 配置，所以自己搭一个 fake-ip 解析器。 */
		if (!sectionExists(cfg, "dns:"))
			cfg.append("dns:\n")
				.append("  enable: true\n")
				.append("  enhanced-mode: fake-ip\n")
				.append("  fake-ip-range: 198.18.0.1/16\n")
				/* 不配 DoH 兜底：1.1.1.1 在很多网络里都不可达，而一个不可达的兜底会
				   让每次解析都等到自己超时。用 fake-ip 时真实域名本来就会交给代理去解，
				   所以境外域名根本不需要本地解析器。 */
				.append("  nameserver:\n")
				.append("    - 223.5.5.5\n")
				.append("    - 119.29.29.29\n");

		/* 让 mihomo 自己的日志写进文件。clash-api 的启动/绑定行、以及任何 panic 都在
		   这里，而不在 App 的标准输出里 —— 少了它，"9090 从没监听"的原因就永远看不见
		   （我们以前只看到一个"静默地没监听"）。 */
		if (!sectionExists(cfg, "log:"))
			cfg.append("log:\n  level: info\n  report: false\n  file: \"")
				.append(new File(context.getCacheDir(), "mihomo.log").getAbsolutePath())
				.append("\"\n");

		/* TUN 由 Clash.startTUN 驱动（它负责提供 fd 与 protect 回调）。我们只把它打开，
		   路由交给 mihomo 自动接管。 */
		if (!sectionExists(cfg, "tun:"))
			cfg.append("tun:\n")
				.append("  enable: true\n")
				.append("  auto-route: true\n")
				.append("  auto-detect-interface: true\n")
				/* 不做 DNS 劫持的话，域名解析会绕过内核的 fake-ip 解析器，直接打到
				   系统解析器上。 */
				.append("  dns-hijack:\n")
				.append("    - any:53\n");

		/* API 用来区分"经代理"与"直连"的流量（首页计数器就是靠它）。绑在 127.0.0.1
		   （回环）并用 `secret` 鉴权 —— 见上面 API_PORT 的说明。 */
		/* 把 clash-api 绑在回环（127.0.0.1）。App 通过 protect() 过的 socket
		   （localApi）访问它，而且 127.0.0.1:7890 的探测已经证明 App 能访问回环 ——
		   所以控制接口也一样能访问。这也和 isPortFree() 一致（它正是探测 127.0.0.1
		   来决定端口的）。控制接口绑 0.0.0.0 在本机上是**静默监听失败**的（同样绑
		   0.0.0.0 的 mixed-port 却正常），这正是"9090 从没监听"的症状。鉴权继续靠
		   `secret` 打开。 */
		if (!sectionExists(cfg, "external-controller:"))
			cfg.append("external-controller: 127.0.0.1:").append(API_PORT).append('\n');
		/* clash-api 的 bearer 令牌：每个控制请求都必须带
		   Authorization: Bearer <secret>，否则 mihomo 回 401。 */
		if (!sectionExists(cfg, "secret:"))
			cfg.append("secret: \"").append(escapeYaml(prefs.getSecret())).append("\"\n");

		/* 一组合理的核心默认值，对齐 mihomo 自己的参考配置：rule 模式、统一延迟口径、
		   TCP 并发拨号、默认关闭 IPv6、在 /connections 里带进程名（首页"最近请求"
		   因此受益）、以及给本地 clash-api 一个宽松的 CORS 策略。每一项都有
		   "已存在就不写"的保护，所以手工改过的配置会保留自己的值，且都不与上面的
		   API/鉴权键冲突。 */
		if (!sectionExists(cfg, "mode:"))
			cfg.append("mode: rule\n");
		if (!sectionExists(cfg, "unified-delay:"))
			cfg.append("unified-delay: true\n");
		if (!sectionExists(cfg, "tcp-concurrent:"))
			cfg.append("tcp-concurrent: true\n");
		if (!sectionExists(cfg, "ipv6:"))
			cfg.append("ipv6: false\n");
		if (!sectionExists(cfg, "find-process-mode:"))
			cfg.append("find-process-mode: always\n");
		if (!sectionExists(cfg, "external-controller-cors:"))
			cfg.append("external-controller-cors:\n")
				.append("  allow-origins:\n")
				.append("    - \"*\"\n")
				.append("  allow-private-network: true\n");

		/* 首页会通过内核的本地 HTTP 端口去问回显服务"我的公网 IP 是多少"，而
		   「允许局域网」还会把这个端口暴露出去，所以这个端口完全由 App 说了算。
		   配置是从零拼的（订阅里的端口设置不会继承），所以这个值每次都写 ——
		   既不继承、也不省略。 */
		cfg.append("mixed-port: ").append(prefs.getProxyPort()).append('\n');
		/* 除非用户主动打开，否则 allow-lan 保持关闭：在共享网络上开一个开放代理，
		   等于让同网段任何人都能用（而且是你付费的）这条隧道。 */
		if (prefs.getAllowLan()) {
			cfg.append("allow-lan: true\n")
				.append("bind-address: \"*\"\n");
		}

		/* GEOIP/GEOSITE 规则需要 mihomo 的 geoip.dat / geosite.dat。文件缺失时 mihomo
		   会从 geo-download-url 去取，所以**只有**存在这类规则时才打开自动更新：
		   从不按地区分流的用户不必付这次启动下载，而需要的人能正常分流。用 jsdelivr
		   镜像是因为上游 GitHub 发布在很多受限网络里会被限速或拦掉。 */
		if (rulesNeedGeo(prefs) && !sectionExists(cfg, "geo-auto-update:")) {
			cfg.append("geo-auto-update: true\n")
				.append("geo-download-url: \"https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@latest\"\n");
		}

		/* 内核固定加载 <homeDir>/config.yaml，所以**文件名不能改** —— 叫别的名字会让
		   quickSetup 报 "stat config.yaml: no such file or directory"。 */
		/* 节点数已由上面那两个生成函数在产出文本时记下（见 lastBuiltNodes），
		   这里不再回头解析一遍整份配置。 */
		File out = new File(context.getFilesDir(), "config.yaml");
		try (FileOutputStream fos = new FileOutputStream(out, false)) {
			fos.write(cfg.toString().getBytes("UTF-8"));
		}
		return out;
	}

	/* 给 App 内的**测速内核**生成一份最小配置：无 TUN、无监听端口（见 CoreTestHost）。
	   它只需要节点列表，好让内核的隔离式延迟测试（"testDelay" 动作）在**没有 VPN**
	   的情况下也能跑：不配 `tun:` 就没有虚拟网卡，不配 `mixed-port` 就没有任何监听，
	   不配 `external-controller` 就没有 API 监听 —— 所以这个内核**永远**不会和
	   :native 进程里的隧道内核打架。写入 <homeDir>/config.yaml 并返回其内容
	   （用于变更检测）。 */
	public static String buildTestCoreConfig(Preferences prefs, File homeDir)
			throws IOException {
		return buildTestCoreConfig(prefs, homeDir, null);
	}

	/* 同上，但剔除内核已经拒绝过的节点名（见 CoreTestHost.badProxies）：
	   组里不能引用文件里已经不存在的节点，所以代理组也用**保留下来的那份**列表。 */
	public static String buildTestCoreConfig(Preferences prefs, File homeDir,
			java.util.Set<String> skip) throws IOException {
		/* 与列表/隧道**同一范围**的节点池：国家筛选就是范围本身 —— 选「全部」带全部
		   节点，选某个国家就只带该国的节点。 */
		String body = mergedConfig(prefs);

		/* **只保留** `proxies:` 段。延迟测试只需要节点，别的都不需要；而隧道那套代理组
		   在这里不只是没用，而是**有害**：mihomo 一加载配置就会跑 url-test 组的健康
		   检查，而且是**同时**拨测组内每一个成员 —— 所以带上完整的多国节点池
		   （每个国家一个 url-test 组，再加一个覆盖全部的全局组），内核一启动就会同时
		   发出几十个探测。我们自己的 testDelay 调用只能排在这波风暴后面，于是一个接一个
		   超时。这正是「全部」测出来一片"不可用"、而单选某个国家（只有几个成员）却正常
		   的原因。 */
		int cut = body.indexOf("\nproxy-groups:");
		String proxies = cut > 0 ? body.substring(0, cut + 1) : body;
		/* 重建这一段时**去掉**内核已拒绝的节点：正是这一点让 CoreTestHost 的重试可以
		   只剔掉一个坏节点，而不是丢掉全部 600 个。 */
		List<ClashParser.ProxyDef> kept = new ArrayList<ClashParser.ProxyDef>();
		StringBuilder sb = new StringBuilder("proxies:\n");
		for (ClashParser.ProxyDef p : ClashParser.extractProxies(proxies)) {
			if (skip != null && skip.contains(p.name))
			  continue;
			kept.add(p);
			sb.append(p.text).append('\n');
		}
		if (kept.isEmpty())
		  throw new IOException("no usable proxy after filtering rejected nodes");
		/* 一个 select 组列出**保留下来的**每个节点：配置因此是合法的（组只能引用在它
		   之前声明的节点，这里正是如此），而且后台不会跑任何周期性任务。 */
		sb.append("proxy-groups:\n");
		sb.append("  - name: \"").append(GROUP).append("\"\n");
		sb.append("    type: select\n");
		sb.append("    proxies:\n");
		for (ClashParser.ProxyDef p : kept)
		  sb.append("      - \"").append(escapeYaml(p.name)).append("\"\n");
		sb.append("rules:\n");
		sb.append("  - MATCH,").append(GROUP).append('\n');
		sb.append("mode: rule\n");
		sb.append("log-level: silent\n");
		sb.append("ipv6: false\n");
		if (!sectionExists(sb, "dns:"))
		  sb.append("dns:\n")
			.append("  enable: true\n")
			.append("  enhanced-mode: fake-ip\n")
			.append("  fake-ip-range: 198.18.0.1/16\n")
			.append("  nameserver:\n")
			.append("    - 223.5.5.5\n")
			.append("    - 119.29.29.29\n");
		File out = new File(homeDir, "config.yaml");
		try (FileOutputStream fos = new FileOutputStream(out, false)) {
			fos.write(sb.toString().getBytes("UTF-8"));
		}
		return sb.toString();
	}

	/* 便宜的先手过滤，专门拦内核会**直接拒绝**的错误。内核是整份文件一起校验的，
	   所以**一个**坏节点就会毒掉所有节点。
	   REALITY 的 short-id 必须是**长度不超过 16 的偶数个十六进制字符**；其它情况
	   （奇数长度、非十六进制、过长）都会让 cloud.flare 以 "invalid REALITY short ID"
	   拒掉该节点。 */
	private static boolean isRejectedByCore(String text) {
		if (text == null || text.isEmpty())
		  return false;
		/* **不锚定行首**：网上有一半订阅用 flow 风格
		   （`- {name: x, server: y, reality-opts: {short-id: zz}}`），键在花括号里，
		   锚定行首的正则会漏掉 —— 那个坏节点当初就是这样混进内核的。
		   只编译一次：每次重建都要对**每个**节点跑这行。 */
		Matcher m = SHORT_ID.matcher(text);
		if (!m.find())
		  return false;
		String id = m.group(1).trim();
		if (id.isEmpty())
		  return false;
		/* mihomo 会对 short-id 做 hex.DecodeString；只要不是"偶数个十六进制数字"，
		   它就会以 "invalid REALITY short ID" 拒掉该节点（连带**整份**配置）。 */
		if (id.length() > 16 || (id.length() % 2) != 0)
		  return true;
		return !id.matches("(?i)[0-9a-f]+");
	}

	/* mihomo 对被拒节点的报法是 "proxy <序号>: <原因>"，序号是它在生成的 `proxies:`
	   列表里的位置。这里把它映射回节点名，以便排除该节点后重试（见 CoreTestHost）。 */
	public static String badProxyNameFromError(String err, String cfg) {
		if (err == null || cfg == null)
		  return null;
		Matcher m = PROXY_INDEX.matcher(err);
		if (!m.find())
		  return null;
		int idx;
		try {
			idx = Integer.parseInt(m.group(1));
		} catch (Throwable e) {
			return null;
		}
		List<ClashParser.ProxyDef> list = ClashParser.extractProxies(cfg);
		if (idx < 0 || idx >= list.size())
		  return null;
		return list.get(idx).name;
	}

	/* 给日志用的一行摘要，说明配置里有什么：一个空的合并节点池是那种"一直隐形，
	   直到所有连接都超时"的问题。 */
	public static String describe(Preferences prefs) {
		/* 节点数取自 build() 实测写出去的那个数（见 lastBuiltNodes）：它才是**这份配置
		   里真有**的节点。以前这里重新解析每份订阅来数，既不准确（筛选掉、排除掉的
		   也算在内），又白解析一遍。还没构建过时显示 "?"，而不是编一个数字。 */
		int nodes = lastBuiltNodes;
		String nodeCount = nodes >= 0 ? String.valueOf(nodes) : "?";
		/* 最值得记进日志的就是**兜底目标**：如果它是 DIRECT，那么无论节点池多健康，
		   所有流量都在绕开代理走。 */
		return nodeCount + " node(s), " + prefs.getRules().size() + " rule(s), "
			+ strategyLabel(prefs) + ", "
			+ (prefs.getAutoSelect()
				? ("auto url-test " + prefs.getAutoSelectInterval() + "s" + autoCountryLabel(prefs))
				: "manual select");
	}

	/* 给日志用的一行分流策略说明。 */
	private static String strategyLabel(Preferences prefs) {
		String s = prefs.getRulesStrategy();
		if (Preferences.RULES_STRATEGY_GLOBAL.equals(s))
		  return "strategy=global-proxy";
		if (Preferences.RULES_STRATEGY_DIRECT.equals(s))
		  return "strategy=global-direct";
		return "strategy=rules(default="
			+ (prefs.getRulesDefaultProxy() ? GROUP : "DIRECT") + ")";
	}

	/* 给日志用的一行说明：自动模式是从**哪个池**里挑最快节点。
	   该池已由 mergedConfig 收窄到用户选中的国家。 */
	private static String autoCountryLabel(Preferences prefs) {
		String c = prefs.getSubCountryFilter();
		if (c == null || c.isEmpty())
		  return " (global)";
		return " (country=" + c + ")";
	}

	/* 把所有订阅合并成一个节点池，并让**一个**自建组指向它。这个池遵守订阅页的国家
	   筛选；而且隧道和那个无 TUN 的测速内核用的是**同一个方法** —— 这正是"测速范围 =
	   你看到的列表"不需要额外开关就能成立的原因。 */
	private static String mergedConfig(Preferences prefs) throws IOException {
		return mergedConfig(prefs, null);
	}

	/* 同上，但剔除内核已拒绝的节点名。因为 mihomo 是**一次性**校验整份配置的，
	   去掉这一个坏节点，就是"隧道能起来"和"什么都不工作"的区别。 */
	private static String mergedConfig(Preferences prefs, java.util.Set<String> skip)
			throws IOException {
		/* 用 Set 而不是 List：判重时 List.contains 是 O(n)，
		   600 个节点就是十几万次字符串比较，而这段在每次连接、每次测速前都要跑。 */
		java.util.Set<String> taken = new java.util.HashSet<String>();
		StringBuilder proxies = new StringBuilder();

		/* 订阅页的国家标签会把整条隧道收窄到**某一个国家**的节点，所以"自动最快"和
		   手动选节点都只会在该国范围内。筛选为空 = 所有国家。 */
		String cc = prefs.getSubCountryFilter();
		boolean ccSet = (cc != null && !cc.isEmpty());

		for (Subscription sub : prefs.getSubscriptions()) {
			/* 已停用的订阅会被保留，但不并入节点池。 */
			if (!sub.enabled)
			  continue;
			List<ClashParser.ProxyDef> list =
				ClashParser.extractProxies(prefs.getSubRaw(sub.id));
			for (ClashParser.ProxyDef p : list) {
				if (ccSet) {
					String pc = prefs.getServerCountry(p.server);
					boolean match = cc.equals(pc);
					/* "未知"这一档也涵盖"国别还没解析出来"的节点
					   （server->country 映射为空）。 */
					if (!match && GeoIp.UNKNOWN.equals(cc)
							&& (pc == null || pc.isEmpty()))
					  match = true;
					if (!match)
					  continue;
				}
				/* 只要有一个节点非法，mihomo 就会**拒绝整份配置**
				   （"proxy 645: invalid REALITY short ID"），这会让隧道和所有延迟测试
				   一起趴下 —— 也正是"「全部」测出一片不可用，而单选某个国家（池里恰好
				   没有那个坏节点）却正常"的原因。在这里就把坏条目剔掉，而不是把一份注定
				   失败的配置交给内核。 */
				if (isRejectedByCore(p.text)) {
					TProxyService.log("配置: 跳过内核无法解析的节点「" + p.name + "」");
					continue;
				}
				if (skip != null && skip.contains(p.name)) {
					TProxyService.log("配置: 跳过内核已拒绝的节点「" + p.name + "」");
					continue;
				}
				String name = uniqueName(taken, p.name);
				taken.add(name);
				proxies.append(name.equals(p.name) ? p.text : renameProxy(p.text, p.name, name))
					.append('\n');
			}
		}
		if (taken.isEmpty())
		  throw new IOException(ccSet
			  ? ("no upstream in country " + cc)
			  : "no upstream: add a subscription or enable a SOCKS5 server");

		/* 代理组要按 `proxies:` 段**实际包含**的内容来构造，而不是按我们"打算"用的
		   名字。去重改名或引号写法差异都可能留下一个根本没被写出去的名字，而只要组里
		   引用了一个不存在的成员，mihomo 就会以 "proxy group[0] ... not found"
		   中断**整份**配置的加载。把自己生成的输出**再解析回来**，两者就天然一致 ——
		   读不回来的节点直接被排除在组之外，而不是毒掉整份配置。 */
		String proxiesText = "proxies:\n" + proxies;
		List<ClashParser.ProxyDef> proxys = ClashParser.extractProxies(proxiesText);
		if (proxys.isEmpty())
		  throw new IOException("no usable proxy in the merged pool");

		/* 用块风格而不是 flow 映射：几百个节点挤在一行会有几十 KB，而那里一旦解析
		   失败，MATCH 就会**静默地**指向一个不存在的组 —— 症状是"已连接，但流量不走
		   代理"。一行一个节点就不会这么炸。 */
		StringBuilder sb = new StringBuilder(proxiesText);
		sb.append("proxy-groups:\n");
		if (prefs.getAutoSelect())
		  appendAutoGroups(sb, proxys, prefs);
		else {
			String sel = prefs.getSubSelected();
			sb.append("  - name: \"").append(GROUP).append("\"\n");
			sb.append("    type: select\n");
			sb.append("    proxies:\n");
			/* mihomo 默认选中 select 组的**第一个**成员，而 App 自己的切换是在之后
			   通过控制接口补做的 —— 那个步骤恰恰在 9090 不可达时会失败。把用户选中的
			   节点排在最前，这个选择就**自身成立**，而不会静默地退回"恰好排第一"的
			   那个节点。 */
			if (sel != null && !sel.isEmpty()) {
				for (ClashParser.ProxyDef p : proxys) {
					if (sel.equals(p.name)) {
						sb.append("      - \"").append(escapeYaml(p.name)).append("\"\n");
						break;
					}
				}
			}
			for (ClashParser.ProxyDef p : proxys) {
				if (sel != null && sel.equals(p.name))
				  continue;
				sb.append("      - \"").append(escapeYaml(p.name)).append("\"\n");
			}
		}

		sb.append("rules:\n");
		appendRules(sb, prefs);
		/* proxys 就是把写出去的 `proxies:` 段再解析回来的结果（见上），它的个数正是
		   这份配置**实际包含**的节点数 —— 顺手记下，省掉事后的一次重解析。 */
		lastBuiltNodes = proxys.size();
		return sb.toString();
	}

	/* 自动模式：每个国家一个 url-test 组（该国**内部**最快的节点），再加一个全局
	   url-test 组（哪儿最快），全部挂在一个顶层 select 组下面。顶层默认指向的子组是：
	     - "AUTO"     -> 实测延迟最低的那个国家
	     - 某个国家码 -> 该国的 url-test 组
	     - "GLOBAL"/空 -> 全局 url-test 组（哪儿最快）。 */
	private static void appendAutoGroups(StringBuilder sb,
			List<ClashParser.ProxyDef> proxys, Preferences prefs) {
		/* 国家码 -> 该国的节点名列表，保持首次出现的顺序。 */
		java.util.LinkedHashMap<String, List<String>> byCountry =
			new java.util.LinkedHashMap<String, List<String>>();
		List<String> global = new ArrayList<String>();
		for (ClashParser.ProxyDef p : proxys) {
			String cc = prefs.getServerCountry(p.server);
			if (cc == null || cc.isEmpty())
			  cc = GeoIp.UNKNOWN;
			List<String> list = byCountry.get(cc);
			if (list == null) {
				list = new ArrayList<String>();
				byCountry.put(cc, list);
			}
			list.add(p.name);
			global.add(p.name);
		}

		/* 国家池已经被订阅页的筛选收窄过了（MihomoConfig.mergedConfig），所以
		   "自动最快"就是该池内的全局 url-test 组。 */
		String defaultSub = GLOBAL_GROUP;

		/* mihomo 按**定义顺序**解析代理组成员：一个组只能引用在它**之前**声明过的
		   节点/组。所以各国的 url-test 组（以及全局组）必须先输出，指向它们的顶层
		   select 组放在最后。顺序写错会让 quickSetup 以
		   "proxy group[0] tunvpn: proxy '...' not found" 失败，并且**连带**把
		   external-controller 也拖垮 —— 症状是节点选择器打不开、连接列表为空、
		   代理计数恒为 0。 */
		for (java.util.Map.Entry<String, List<String>> e : byCountry.entrySet())
		  appendUrlTestGroup(sb, countryGroup(e.getKey()), e.getValue(), prefs);
		appendUrlTestGroup(sb, GLOBAL_GROUP, global, prefs);

		/* 顶层 select 组：mihomo 默认选中它的**第一个**成员，所以把用户选中的子组排在
		   最前，然后是其余国家组，再是全局组，最后留一个直连出口。 */
		sb.append("  - name: \"").append(GROUP).append("\"\n");
		sb.append("    type: select\n");
		sb.append("    proxies:\n");
		sb.append("      - \"").append(escapeYaml(defaultSub)).append("\"\n");
		for (String cc : byCountry.keySet()) {
			String g = countryGroup(cc);
			if (g.equals(defaultSub))
			  continue;
			sb.append("      - \"").append(escapeYaml(g)).append("\"\n");
		}
		if (!GLOBAL_GROUP.equals(defaultSub))
		  sb.append("      - \"").append(escapeYaml(GLOBAL_GROUP)).append("\"\n");
		sb.append("      - DIRECT\n");
	}

	/* 选了"自动最佳"时，返回"节点实测延迟最低"的那个国家的 ISO 码（延迟来自 App
	   之前的 TCP 测速，按订阅缓存）。没有任何可用延迟时返回 null，调用方会退回
	   全局组。UNKNOWN 节点会被跳过 —— 连国家都没认出来的，没法拿来优化。 */
	private static String bestCountryForAuto(Preferences prefs,
			java.util.LinkedHashMap<String, List<String>> byCountry,
			List<ClashParser.ProxyDef> proxys) {
		/* 代理名 -> 服务器，再由 服务器 -> 各缓存里见过的最好延迟。 */
		java.util.HashMap<String, String> nameToServer = new java.util.HashMap<String, String>();
		for (ClashParser.ProxyDef p : proxys)
		  nameToServer.put(p.name, p.server);

		java.util.HashMap<String, Long> serverLat = new java.util.HashMap<String, Long>();
		for (Subscription sub : prefs.getSubscriptions()) {
			if (!sub.enabled)
			  continue;
			for (ClashNode n : ClashNode.decode(prefs.getSubNodes(sub.id))) {
				if (n.latency >= 0) {
					Long prev = serverLat.get(n.server);
					if (prev == null || n.latency < prev)
					  serverLat.put(n.server, n.latency);
				}
			}
		}

		String best = null;
		long bestLat = Long.MAX_VALUE;
		for (java.util.Map.Entry<String, List<String>> e : byCountry.entrySet()) {
			String cc = e.getKey();
			if (GeoIp.UNKNOWN.equals(cc))
			  continue;
			long min = Long.MAX_VALUE;
			for (String name : e.getValue()) {
				String srv = nameToServer.get(name);
				Long lat = srv == null ? null : serverLat.get(srv);
				if (lat != null && lat < min)
				  min = lat;
			}
			if (min < bestLat) {
				bestLat = min;
				best = cc;
			}
		}
		return best;
	}

	/* 一个 url-test 组：mihomo 会拿测试地址量每个成员的延迟，走最低的那个，并按
	   `interval` 反复复查。 */
	private static void appendUrlTestGroup(StringBuilder sb, String name,
			List<String> members, Preferences prefs) {
		sb.append("  - name: \"").append(escapeYaml(name)).append("\"\n");
		sb.append("    type: url-test\n");
		sb.append("    url: \"").append(escapeYaml(prefs.getAutoTestUrl())).append("\"\n");
		sb.append("    interval: ").append(clampInterval(prefs.getAutoSelectInterval()))
			.append('\n')
			.append("    tolerance: 50\n");
		sb.append("    proxies:\n");
		for (String m : members)
		  sb.append("      - \"").append(escapeYaml(m)).append("\"\n");
	}

	/* 手动配置的服务器所需的最小上游。**有原始节点块就用它**（整块原样输出，协议交给
	   内嵌的 mihomo 处理，select 组指向块里的 name）；只有 SOCKS5 那种"仅地址+端口"
	   的条目才用表单拼。 */
	private static String manualSocksConfig(Preferences prefs, SocksServer s) throws IOException {
		/* 分支看的是**有没有原始节点块**，而不是协议名：协议名只是列表上显示的标签，
		   可能是空的、写法也可能与订阅里不同。以前按 `type == "socks5"` 判断，而
		   `SocksServer` 的构造函数会把空的 type 补成 "socks5" —— 于是从订阅里长按加入
		   的节点一旦没解析出 type，它的**原始块会被整块丢掉**，改发一个"地址端口都对、
		   协议却写成 socks5"的节点，那种节点永远连不上。 */
		String raw = s.raw == null ? "" : s.raw.trim();
		if (!raw.isEmpty()) {
			String nodeName = nodeNameFromRaw(raw);
			StringBuilder sb = new StringBuilder();
			String proxiesText = emitRawProxy(raw);
			sb.append(proxiesText);
			sb.append("proxy-groups:\n");
			sb.append("  - {name: \"").append(GROUP).append("\", type: select, proxies: [\"")
				.append(escapeYaml(nodeName)).append("\"]}\n");
			sb.append("rules:\n");
			appendRules(sb, prefs);
			/* 用户可能贴的就是**整段** `proxies:` 列表，所以节点数要数一下 —— 不过这里只
			   数这一小段节点文本（不含分组与规则），而非整份配置。 */
			lastBuiltNodes = ClashParser.extractProxies(proxiesText).size();
			return sb.toString();
		}

		/* 没有原始块：只有 SOCKS5 拼得出来，其它协议在这里就是"配置缺失"。 */
		if (!s.isSocks())
		  throw new IOException("节点配置为空，请填写 clash 格式的节点定义");
		String addr = s.addr == null ? "" : s.addr.trim();
		if (addr.isEmpty())
		  throw new IOException("SOCKS5 server address is empty");

		StringBuilder sb = new StringBuilder();
		sb.append("proxies:\n");
		sb.append("  - {name: \"socks5\", type: socks5, server: ").append(addr)
			.append(", port: ").append(s.port)
			.append(", udp: true");
		if (s.user != null && !s.user.isEmpty())
		  sb.append(", username: \"").append(s.user).append("\"");
		if (s.pass != null && !s.pass.isEmpty())
			sb.append(", password: \"").append(s.pass).append("\"");
		sb.append("}\n");
		sb.append("proxy-groups:\n");
		sb.append("  - {name: \"").append(GROUP).append("\", type: select, proxies: [\"socks5\"]}\n");
		sb.append("rules:\n");
		appendRules(sb, prefs);
		lastBuiltNodes = 1;   /* 这一段只产出一个节点（见 lastBuiltNodes） */
		return sb.toString();
	}

	/* 从原始 clash 节点块里抠出 "name:"，好让 select 组能引用它。
	   没有 name 时退回 "node"。 */
	private static String nodeNameFromRaw(String raw) {
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"name\\s*:\\s*[\"']?([^\"',\\n]+)");
		java.util.regex.Matcher m = p.matcher(raw);
		if (m.find()) {
			String n = m.group(1).trim().replace("\"", "").replace("'", "");
			if (!n.isEmpty())
			  return n;
		}
		return "node";
	}

	/* 把用户粘贴的 clash 节点块包装成合法的 "proxies:" 段。如果用户贴的就是整段
	   "proxies:" 列表，就原样保留；否则包装成单个节点块。已经以 "- " 开头的块按
	   列表条目保留（**不能**再补一个短横线，否则 mihomo 看到的是 "  - - {...}"）。

	   续行必须保留**相对缩进**：整块按第一行的内容列重新对齐，每个续行的相对深度
	   原样平移。以前是每行都 trim 后统一补 4 个空格，于是 reality-opts、ws-opts、
	   grpc-opts、headers 这些**嵌套映射**会被压平成同级兄弟键（public-key / path /
	   Host 变成节点的直接字段），内核要么直接报配置错误，要么静默丢掉这些参数 ——
	   表现就是"手动加进来的节点看着在，但怎么都连不上"。而这正是从订阅里
	   「长按 → 加入服务器列表」最常搬过来的那类节点。 */
	private static String emitRawProxy(String raw) {
		if (raw.startsWith("proxies:"))
		  return raw + "\n";
		String[] lines = raw.split("\\r?\\n");
		StringBuilder sb = new StringBuilder("proxies:\n");
		boolean first = true;
		int contentCol = 0;   /* 第一行**内容**所在的列，续行按它算相对缩进 */
		for (String ln : lines) {
			if (ln.trim().isEmpty())
			  continue;
			int indent = 0;
			while (indent < ln.length() && ln.charAt(indent) == ' ')
			  indent++;
			String t = ln.trim();
			if (first) {
				/* 已经是列表条目（"- " 开头）就只补到 "  - "，**不能**再加一个短横线。 */
				boolean dash = t.startsWith("- ");
				sb.append("  - ").append(dash ? t.substring(2).trim() : t).append('\n');
				contentCol = dash ? indent + 2 : indent;
				first = false;
				continue;
			}
			int rel = indent - contentCol;
			if (rel < 0)
			  rel = 0;
			sb.append("    ");           /* 续行落在内容列（第 4 列） */
			for (int i = 0; i < rel; i++)
			  sb.append(' ');
			sb.append(t).append('\n');
		}
		return sb.toString();
	}

	/* App 的路由规则，后面接兜底项。策略决定用户自己的规则**是否真的会执行**：
	   两种全局模式把一切都按同一个方向送走，直接跳过规则列表。 */
	private static void appendRules(StringBuilder sb, Preferences prefs) {
		String strategy = prefs.getRulesStrategy();
		if (Preferences.RULES_STRATEGY_GLOBAL.equals(strategy)) {
			/* 全部走代理；规则列表被忽略。 */
			sb.append("  - MATCH,").append(GROUP).append('\n');
			return;
		}
		if (Preferences.RULES_STRATEGY_DIRECT.equals(strategy)) {
			/* 全部直连；规则列表被忽略。 */
			sb.append("  - MATCH,DIRECT\n");
			return;
		}
		/* rule 模式：先用户规则，再兜底项（兜底走代理还是直连，由下面的开关决定）。 */
		for (Preferences.Rule r : prefs.getRules()) {
			String line = clashRule(r);
			if (line != null)
			  sb.append("  - ").append(line).append('\n');
		}
		sb.append("  - MATCH,")
			.append(prefs.getRulesDefaultProxy() ? GROUP : "DIRECT").append('\n');
	}

	/* 把一条 App 规则转成 clash 规则行，**尊重用户实际选的类型**（早期版本会从值里
	   反推类型、忽略用户的选择）。无法表达时返回 null。 */
	private static String clashRule(Preferences.Rule r) {
		String target = r.proxy ? GROUP : "DIRECT";
		String value = r.value == null ? "" : r.value.trim();
		if (value.isEmpty())
		  return null;
		switch (r.type) {
			case Preferences.Rule.TYPE_KEYWORD:
				return "DOMAIN-KEYWORD," + value + "," + target;
			case Preferences.Rule.TYPE_GEOIP:
				/* value 是 ISO-3166 两位国家码；GEOIP 依赖内核的 geoip 数据库，
				   mihomo 首次使用时会去下载。 */
				if (!value.matches("^[A-Za-z]{2}$"))
				  return null;
				return "GEOIP," + value.toUpperCase() + "," + target;
			case Preferences.Rule.TYPE_PROCESS:
				return "PROCESS-NAME," + value + "," + target;
			case Preferences.Rule.TYPE_CIDR:
				if (!value.contains("/"))
				  return null;
				/* IPv6 必须用自己的规则类型，否则 mihomo 会拒绝。 */
				return (value.contains(":") ? "IP-CIDR6," : "IP-CIDR,") + value + "," + target;
			case Preferences.Rule.TYPE_IP:
				if (isIpv4(value))
				  return "IP-CIDR," + value + "/32," + target;
				if (value.contains(":"))
				  return "IP-CIDR6," + value + "/128," + target;
				return null;
			case Preferences.Rule.TYPE_DOMAIN_FULL:
				return "DOMAIN," + value + "," + target;
			case Preferences.Rule.TYPE_GEOSITE:
				return "GEOSITE," + value + "," + target;
			case Preferences.Rule.TYPE_PROCESS_PATH:
				return "PROCESS-PATH," + value + "," + target;
			case Preferences.Rule.TYPE_DST_PORT:
				return "DST-PORT," + value + "," + target;
			case Preferences.Rule.TYPE_SRC_PORT:
				return "SRC-PORT," + value + "," + target;
			case Preferences.Rule.TYPE_NETWORK:
				return "NETWORK," + value.toLowerCase() + "," + target;
			case Preferences.Rule.TYPE_DOMAIN:
			default:
				return "DOMAIN-SUFFIX," + value + "," + target;
		}
	}

	/* 规则列表里用到 GEOIP 或 GEOSITE 匹配器时为 true —— 它们需要内核本地的
	   国家/域名数据库（见 build() 里的 geo-auto-update 段）。 */
	private static boolean rulesNeedGeo(Preferences prefs) {
		for (Preferences.Rule r : prefs.getRules()) {
			if (r.type == Preferences.Rule.TYPE_GEOIP
					|| r.type == Preferences.Rule.TYPE_GEOSITE)
			  return true;
		}
		return false;
	}

	/* 带上 clash-api 的 bearer 令牌：配置里一旦设了 `secret:`，mihomo 就要求带上它，
	   否则会返回 401 而且响应体是空的。所有控制接口（/proxies、/delay、/rules、
	   /connections、/configs、/version）都需要这一步。 */
	public static void applyAuth(HttpURLConnection conn, Preferences prefs) {
		String s = prefs.getSecret();
		if (s != null && !s.isEmpty())
		  conn.setRequestProperty("Authorization", "Bearer " + s);
	}

	private static boolean isIpv4(String s) {
		String[] parts = s.split("\\.");
		if (parts.length != 4)
		  return false;
		for (String p : parts) {
			try {
				int v = Integer.parseInt(p);
				if (v < 0 || v > 255)
				  return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

	/* 合并后的节点池必须能按名字寻址，而两份订阅完全可能带同一个名字，
	   所以这里给它去重。用 Set 判重（见 mergedConfig 的说明）。 */
	private static String uniqueName(java.util.Set<String> taken, String name) {
		if (!taken.contains(name))
		  return name;
		for (int i = 2; ; i++) {
			String candidate = name + " (" + i + ")";
			if (!taken.contains(candidate))
			  return candidate;
		}
	}

	/* 改写一个节点块里的 name: 值 —— **只动这个键**，所以服务器地址里恰好含有
	   同样文本的情况不会被误伤。 */
	private static String renameProxy(String text, String oldName, String newName) {
		try {
			Pattern p = Pattern.compile("(name\\s*:\\s*)[\"']?" + Pattern.quote(oldName)
				+ "[\"']?(?![\\w\\-])");
			Matcher m = p.matcher(text);
			if (m.find())
			  return m.replaceFirst("$1\"" + Matcher.quoteReplacement(newName) + "\"");
		} catch (Exception e) {
		}
		return text;
	}

	private static String escapeYaml(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/* 间隔太小会不停折腾所有节点；太大则等于永远不再重测。 */
	private static int clampInterval(int seconds) {
		if (seconds < MIN_INTERVAL)
		  return MIN_INTERVAL;
		if (seconds > MAX_INTERVAL)
		  return MAX_INTERVAL;
		return seconds;
	}

	/* "key:" 作为**顶层** YAML 键（第 0 列）出现时为 true。 */
	private static boolean sectionExists(StringBuilder sb, String key) {
		String s = sb.toString();
		int idx = 0;
		while ((idx = s.indexOf(key, idx)) >= 0) {
			int lineStart = s.lastIndexOf('\n', idx) + 1;
			if (idx - lineStart == 0)
			  return true;
			idx += key.length();
		}
		return false;
	}
}
