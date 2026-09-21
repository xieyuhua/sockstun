/*
 ============================================================================
 文件名  : Perferences.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2023 xyz
 说明    : 应用偏好设置（按配置档保存的全部开关与参数）
 ============================================================================
 */

package com.tunvpn;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONException;
import org.json.JSONObject;

public class Preferences
{
	public static final String PREFS_NAME = "SocksPrefs";
	/* 高频写入的东西（每秒统计、连接快照、最近请求）放在**独立**文件里。主文件
	   `SocksPrefs` 里还存着几百 KB 的订阅原文与节点 JSON，而 commit() 会重写**整份**
	   文件 —— 每秒把这么大的 XML 重写 + fsync 纯属浪费。key 名刻意保持不变。 */
	public static final String PREFS_STATS_NAME = "SocksStats";
	public static final String SUB_URL = "SubUrl";
	public static final String SUB_NODES = "SubNodes";
	public static final String SUB_RAW = "SubRaw";
	public static final String SUB_SELECTED = "SubSelected";
	/* 内核**当前真正选中**的节点（由 TProxyService 从运行中的配置里读出并写入）。
	   SubSelected 只是"愿望"，这个才是隧道运行期间 UI 必须显示的**事实**。 */
	public static final String ACTIVE_NODE = "ActiveNode";
	public static final String SOCKS_SERVERS = "SocksServers";
	public static final String SOCKS_ACTIVE = "SocksActive";
	public static final String SUBSCRIPTIONS = "Subscriptions";
	public static final String SUB_ACTIVE = "SubActive";
	/* 订阅页的视图状态，跨次启动保留（国家筛选、协议筛选）。 */
	public static final String SUB_COUNTRY_FILTER = "SubCountryFilter";
	public static final String SUB_PROTO_FILTER = "SubProtoFilter";
	public static final String DNS_IPV4 = "DnsIpv4";
	public static final String DNS_IPV6 = "DnsIpv6";
	public static final String IPV4 = "Ipv4";
	public static final String IPV6 = "Ipv6";
	public static final String GLOBAL = "Global";
	public static final String UDP_IN_TCP = "UdpInTcp";
	public static final String REMOTE_DNS = "RemoteDNS";
	public static final String APPS = "Apps";
	public static final String RULES = "Rules";
	public static final String RULES_DEFAULT_PROXY = "RulesDefaultProxy";
	/* 分流策略：rule 模式执行用户的规则（最后的 MATCH 按 RULES_DEFAULT_PROXY 决定走
	   代理还是直连）；两种 global 模式把一切按同一个方向送走并忽略规则列表。 */
	public static final String RULES_STRATEGY = "RulesStrategy";
	public static final String RULES_STRATEGY_RULES = "rules";
	public static final String RULES_STRATEGY_GLOBAL = "global";
	public static final String RULES_STRATEGY_DIRECT = "direct";
	public static final String ENABLE = "Enable";
	public static final String LAST_ERROR = "LastError";
	public static final String NAME = "Name";
	public static final String PROFILE_COUNT = "ProfileCount";
	public static final String SELECTED = "Selected";
	public static final String LOG_ENABLED = "LogEnabled";
	/* 打开后，隧道直接加载 files/config.yaml，而不是每次连接都按 App 的设置重新生成
	   （配置文件由用户在配置页手工编辑）。 */
	public static final String CUSTOM_CONFIG = "CustomConfig";
	public static final String STATS_TOTAL_TX = "StatsTotalTx";
	public static final String STATS_TOTAL_RX = "StatsTotalRx";
	public static final String STATS_SESSION_TX = "StatsSessionTx";
	public static final String STATS_SESSION_RX = "StatsSessionRx";
	public static final String STATS_RATE_TX = "StatsRateTx";
	public static final String STATS_RATE_RX = "StatsRateRx";
	/* 只统计"确实走了节点"的流量。内核报的是它处理过的一切（包含直连），
	   所以这几个计数是在它的连接列表之外单独累加的。 */
	public static final String PROXY_TOTAL_TX = "ProxyTotalTx";
	public static final String PROXY_TOTAL_RX = "ProxyTotalRx";
	public static final String PROXY_SESSION_TX = "ProxySessionTx";
	public static final String PROXY_SESSION_RX = "ProxySessionRx";
	public static final String PROXY_RATE_TX = "ProxyRateTx";
	public static final String PROXY_RATE_RX = "ProxyRateRx";
	public static final String STATS_APP_BASE = "StatsAppBase";
	public static final String STATS_APP_TOTAL = "StatsAppTotal";
	public static final String THEME = "Theme";
	/* 按订阅分别缓存（键 + 订阅 id），这样多份订阅可以各自抓取再合并成一个节点池。 */
	public static final String SUB_RAW_PREFIX = "SubRaw.";
	public static final String SUB_NODES_PREFIX = "SubNodes.";
	/* 自动选择：由内核的 url-test 组挑出最快的节点。 */
	public static final String AUTO_SELECT = "AutoSelect";
	public static final String AUTO_SELECT_INTERVAL = "AutoSelectInterval";
	public static final String AUTO_TEST_URL = "AutoTestUrl";
	/* WebDAV 远程备份（见 WebDav 与 SubscribeActivity 的"备份可用节点"）：
	   **启用且填了地址**时备份传到远端；否则备份落成本地订阅（原行为）。
	   地址/目录都按"用户可能带或不带尾斜杠"来归一化，所以这里原样存。 */
	public static final String WEBDAV_ENABLED = "WebdavEnabled";
	public static final String WEBDAV_URL = "WebdavUrl";
	public static final String WEBDAV_USER = "WebdavUser";
	public static final String WEBDAV_PASS = "WebdavPass";
	public static final String WEBDAV_DIR = "WebdavDir";
	/* 单次探测的超时秒数（走 clash-api 的 /delay 接口测一个节点）。内核量的是**真实
	   转发**的一次请求，所以超时调大能容下"慢但可用"的节点。 */
	public static final String PROXY_TEST_TIMEOUT = "ProxyTestTimeout";
	public static final int DEFAULT_PROXY_TEST_TIMEOUT = 5;
	public static final int MIN_PROXY_TEST_TIMEOUT = 1;
	public static final int MAX_PROXY_TEST_TIMEOUT = 30;
	/* **单个节点**测速的总时限（秒），与上面的"单次探测超时"是两回事。一个节点可能
	   要发好几次探测（配置的地址 + 内置兜底目标 + 救援探测）；没有这个上限时，一个
	   永远不回包的节点能吃掉约 36 秒，整轮就爬得像卡死。有了它，一轮的上界是
	   节点数 × 该值。**严格生效**：给内核的探测超时和本地等待都按剩余额度收紧。 */
	public static final String NODE_TEST_LIMIT = "NodeTestLimit";
	public static final int DEFAULT_NODE_TEST_LIMIT = 15;
	public static final int MIN_NODE_TEST_LIMIT = 5;
	public static final int MAX_NODE_TEST_LIMIT = 120;
	/* 一次探测**完全没有响应**（我们等超时了，内核一直没回包）时，对节点意味着什么？
	   开（默认）：判「不可用」—— 一个永远不回包的探测，正是用户口中的"死节点"，
	   也是"每节点时限"这句话本身的含义。关：只有内核**明确**报失败才判不可用，
	   其它情况一律「未测速」（保守模式，用于排查误杀好节点）。 */
	public static final String PROBE_TIMEOUT_AS_FAIL = "ProbeTimeoutAsFail";
	/* clash-api 的 bearer 令牌。只生成一次并持久化，这样"写进生成配置里的 secret"
	   和"每个客户端请求带的 secret"跨重启始终一致。 */
	public static final String SECRET = "ApiSecret";
	/* 仅自动模式用：顶层组默认指向哪个国家组。空 / "GLOBAL" 表示"哪儿最快"。
	   值是 ISO-3166 两位国家码，与 MihomoConfig 里构造的各国 url-test 组名对应。 */
	public static final String AUTO_SELECT_COUNTRY = "AutoSelectCountry";
	/* 持久化的 服务器 -> ISO 国家码 映射，由 GeoIp 解析填充，好让配置生成能**离线**
	   给节点分组（隧道启动时不依赖网络）。 */
	public static final String SERVER_COUNTRY_MAP = "ServerCountryMap";
	/* 内核监听的本地代理端口，以及是否把它暴露给局域网。 */
	public static final String PROXY_PORT = "ProxyPort";
	public static final String ALLOW_LAN = "AllowLan";
	/* 测速行为：在 App 进程里常驻一个**无 TUN** 的内核，这样即使 VPN 没连接也能量出
	   内核的**真实转发**延迟（测一个节点本来就不需要 TUN）。这个测速内核用的是与列表
	   **同一范围**的节点池：国家筛选就是范围，选「全部」测全部节点，选某个国家就测
	   该国的节点。 */
	public static final String PRELOAD_CORE = "PreloadCore";
	/* 订阅列表是否列出**所有**节点 —— 包括测速判为不可用（-2）和未测速（-1）的。
	   关（默认）时只列可用节点。 */
	public static final String SHOW_UNAVAILABLE = "ShowUnavailable";
	public static final int DEFAULT_PROXY_PORT = 7890;
	public static final int MIN_PROXY_PORT = 1024;
	public static final int MAX_PROXY_PORT = 65535;
	/* mihomo 自己对 url-test 的默认间隔。 */
	public static final int DEFAULT_AUTO_INTERVAL = 300;
	/* url-test 的健康检查地址：204 端点开销极小，而且多数网络不会劫持它。 */
	public static final String DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204";

	public static final int MAX_PROFILES = 13;

	/* 一条路由规则："值 -> 代理或直连"，并带**显式类型**，好让配置生成器输出的正是
	   用户选中的那条 clash 规则。 */
	public static class Rule {
		public static final int TYPE_DOMAIN = 0;   /* DOMAIN-SUFFIX */
		public static final int TYPE_IP = 1;       /* 单个 IP -> IP-CIDR /32 或 /128 */
		public static final int TYPE_CIDR = 2;     /* IP-CIDR / IP-CIDR6 */
		public static final int TYPE_KEYWORD = 3;  /* DOMAIN-KEYWORD */
		public static final int TYPE_GEOIP = 4;    /* GEOIP,<country> */
		public static final int TYPE_PROCESS = 5;  /* PROCESS-NAME */
		/* 后加的：这些数字**必须保持稳定**，因为它们是持久化下来的值；而且
		   strings.xml 里 rule_types 数组的顺序必须与之对应（下拉框的位置就是类型）。 */
		public static final int TYPE_DOMAIN_FULL = 6;   /* DOMAIN（精确匹配） */
		public static final int TYPE_GEOSITE = 7;       /* GEOSITE,<name> */
		public static final int TYPE_PROCESS_PATH = 8;  /* PROCESS-PATH */
		public static final int TYPE_DST_PORT = 9;      /* DST-PORT */
		public static final int TYPE_SRC_PORT = 10;     /* SRC-PORT */
		public static final int TYPE_NETWORK = 11;      /* NETWORK,tcp|udp */

		public int type;
		public String value;
		public boolean proxy;

		public Rule(int type, String value, boolean proxy) {
			this.type = type;
			this.value = value;
			this.proxy = proxy;
		}

		public String encode() {
			return type + "|" + value + "|" + (proxy ? "1" : "0");
		}

		public static Rule decode(String text) {
			String[] f = text.split("\\|");
			if (f.length != 3 || f[1].isEmpty())
			  return null;
			try {
				return new Rule(Integer.parseInt(f[0]), f[1], "1".equals(f[2]));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	private SharedPreferences prefs;
	/* 高频统计所在的文件（见 PREFS_STATS_NAME）。跨进程读取同样靠 MODE_MULTI_PROCESS。 */
	private SharedPreferences statsPrefs;
	private Context mContext;

	public Preferences(Context context) {
		mContext = context.getApplicationContext();
		prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
		statsPrefs = context.getSharedPreferences(PREFS_STATS_NAME, Context.MODE_MULTI_PROCESS);
		migrate();
		migrateSubCache();
		migrateStats();
	}

	/* Application 上下文，给需要 assets / 系统服务的辅助类用
	   （例如 GeoIp 要读打包进来的 GeoLite2 数据库）。 */
	public Context getContext() {
		return mContext;
	}

	/* 旧版本只用一个缓存保存**唯一**那份订阅。这里把它挪到当前启用的订阅名下，
	   让升级后的合并列表还显示用户升级前的那些节点。 */
	private void migrateSubCache() {
		String activeId = getActiveSubId();
		if (activeId == null || activeId.isEmpty())
		  return;
		String legacyNodes = prefs.getString(key(SUB_NODES), "");
		String legacyRaw = prefs.getString(key(SUB_RAW), "");
		if (legacyNodes.isEmpty() && legacyRaw.isEmpty())
		  return;
		if (!prefs.getString(key(SUB_NODES_PREFIX + activeId), "").isEmpty())
		  return; /* 已经在用"按订阅分别缓存"的格式了 */
		SharedPreferences.Editor editor = prefs.edit();
		if (!legacyRaw.isEmpty())
		  editor.putString(key(SUB_RAW_PREFIX + activeId), legacyRaw);
		if (!legacyNodes.isEmpty())
		  editor.putString(key(SUB_NODES_PREFIX + activeId), legacyNodes);
		editor.remove(key(SUB_NODES));
		editor.remove(key(SUB_RAW));
		editor.commit();
	}

	/* 一次性迁移：早期版本把统计也写在主文件里。只有"新文件还没有统计、而旧文件有"时
	   才搬（避免覆盖新数据）。key 名不变，所以本质上只是换个文件，并把主文件里的旧键
	   删掉 —— 这样它不会再背着每秒都在变的值，"只迁一次"也自然成立。 */
	private void migrateStats() {
		if (statsPrefs.contains(STATS_TOTAL_TX) || !prefs.contains(STATS_TOTAL_TX))
		  return;
		SharedPreferences.Editor e = statsPrefs.edit();
		e.putLong(STATS_TOTAL_TX, prefs.getLong(STATS_TOTAL_TX, 0));
		e.putLong(STATS_TOTAL_RX, prefs.getLong(STATS_TOTAL_RX, 0));
		e.putLong(STATS_SESSION_TX, prefs.getLong(STATS_SESSION_TX, 0));
		e.putLong(STATS_SESSION_RX, prefs.getLong(STATS_SESSION_RX, 0));
		e.putLong(STATS_RATE_TX, prefs.getLong(STATS_RATE_TX, 0));
		e.putLong(STATS_RATE_RX, prefs.getLong(STATS_RATE_RX, 0));
		e.putLong(PROXY_TOTAL_TX, prefs.getLong(PROXY_TOTAL_TX, 0));
		e.putLong(PROXY_TOTAL_RX, prefs.getLong(PROXY_TOTAL_RX, 0));
		e.putLong(PROXY_SESSION_TX, prefs.getLong(PROXY_SESSION_TX, 0));
		e.putLong(PROXY_SESSION_RX, prefs.getLong(PROXY_SESSION_RX, 0));
		e.putLong(PROXY_RATE_TX, prefs.getLong(PROXY_RATE_TX, 0));
		e.putLong(PROXY_RATE_RX, prefs.getLong(PROXY_RATE_RX, 0));
		e.putString(STATS_APP_BASE, prefs.getString(STATS_APP_BASE, ""));
		e.putString(STATS_APP_TOTAL, prefs.getString(STATS_APP_TOTAL, ""));
		/* 连接快照与最近请求是"当前状态"而不是累计量，重建即可，所以不搬。 */
		e.commit();
		SharedPreferences.Editor old = prefs.edit();
		old.remove(STATS_TOTAL_TX);
		old.remove(STATS_TOTAL_RX);
		old.remove(STATS_SESSION_TX);
		old.remove(STATS_SESSION_RX);
		old.remove(STATS_RATE_TX);
		old.remove(STATS_RATE_RX);
		old.remove(PROXY_TOTAL_TX);
		old.remove(PROXY_TOTAL_RX);
		old.remove(PROXY_SESSION_TX);
		old.remove(PROXY_SESSION_RX);
		old.remove(PROXY_RATE_TX);
		old.remove(PROXY_RATE_RX);
		old.remove(STATS_APP_BASE);
		old.remove(STATS_APP_TOTAL);
		old.commit();
	}

	/* 按配置档分域的主键：所有"每配置档"的设置都存在 "P<序号>." 下面。 */
	private static String key(int profile, String name) {
		return "P" + profile + "." + name;
	}

	private String key(String name) {
		return key(getSelected(), name);
	}

	/* 一次性迁移：把早期的扁平键搬进配置档 0。 */
	private void migrate() {
		if (prefs.contains(PROFILE_COUNT))
		  return;

		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(0, NAME), "Default");
		if (prefs.contains(DNS_IPV4))
		  editor.putString(key(0, DNS_IPV4), prefs.getString(DNS_IPV4, ""));
		if (prefs.contains(DNS_IPV6))
		  editor.putString(key(0, DNS_IPV6), prefs.getString(DNS_IPV6, ""));
		if (prefs.contains(IPV4))
		  editor.putBoolean(key(0, IPV4), prefs.getBoolean(IPV4, true));
		if (prefs.contains(IPV6))
		  editor.putBoolean(key(0, IPV6), prefs.getBoolean(IPV6, true));
		if (prefs.contains(GLOBAL))
		  editor.putBoolean(key(0, GLOBAL), prefs.getBoolean(GLOBAL, false));
		if (prefs.contains(UDP_IN_TCP))
		  editor.putBoolean(key(0, UDP_IN_TCP), prefs.getBoolean(UDP_IN_TCP, false));
		if (prefs.contains(REMOTE_DNS))
		  editor.putBoolean(key(0, REMOTE_DNS), prefs.getBoolean(REMOTE_DNS, true));
		if (prefs.contains(APPS))
		  editor.putStringSet(key(0, APPS), new HashSet<String>(prefs.getStringSet(APPS, new HashSet<String>())));
		editor.remove(DNS_IPV4);
		editor.remove(DNS_IPV6);
		editor.remove(IPV4);
		editor.remove(IPV6);
		editor.remove(GLOBAL);
		editor.remove(UDP_IN_TCP);
		editor.remove(REMOTE_DNS);
		editor.remove(APPS);
		editor.putInt(PROFILE_COUNT, 1);
		editor.putInt(SELECTED, 0);
		editor.commit();
	}

	/* 把配置档 src 的所有"每配置档"键复制到 dst。
	   读取看到的始终是**已提交**的状态，所以同一个 editor 里按升序移动配置档是安全的。 */
	private void copyProfile(SharedPreferences.Editor editor, int src, int dst) {
		editor.putString(key(dst, NAME), prefs.getString(key(src, NAME), "Default"));
		editor.putString(key(dst, DNS_IPV4), prefs.getString(key(src, DNS_IPV4), "8.8.8.8"));
		editor.putString(key(dst, DNS_IPV6), prefs.getString(key(src, DNS_IPV6), "2001:4860:4860::8888"));
		editor.putBoolean(key(dst, IPV4), prefs.getBoolean(key(src, IPV4), true));
		editor.putBoolean(key(dst, IPV6), prefs.getBoolean(key(src, IPV6), true));
		editor.putBoolean(key(dst, GLOBAL), prefs.getBoolean(key(src, GLOBAL), false));
		editor.putBoolean(key(dst, UDP_IN_TCP), prefs.getBoolean(key(src, UDP_IN_TCP), false));
		editor.putBoolean(key(dst, REMOTE_DNS), prefs.getBoolean(key(src, REMOTE_DNS), true));
		editor.putStringSet(key(dst, APPS), new HashSet<String>(prefs.getStringSet(key(src, APPS), new HashSet<String>())));
		editor.putString(key(dst, RULES), prefs.getString(key(src, RULES), ""));
		editor.putBoolean(key(dst, RULES_DEFAULT_PROXY), prefs.getBoolean(key(src, RULES_DEFAULT_PROXY), true));
		editor.putString(key(dst, SUB_URL), prefs.getString(key(src, SUB_URL), ""));
		editor.putString(key(dst, SUB_NODES), prefs.getString(key(src, SUB_NODES), ""));
		editor.putString(key(dst, SUB_RAW), prefs.getString(key(src, SUB_RAW), ""));
		editor.putString(key(dst, SUB_SELECTED), prefs.getString(key(src, SUB_SELECTED), ""));
		editor.putString(key(dst, SOCKS_SERVERS), prefs.getString(key(src, SOCKS_SERVERS), ""));
		editor.putString(key(dst, SOCKS_ACTIVE), prefs.getString(key(src, SOCKS_ACTIVE), ""));
		editor.putString(key(dst, SUBSCRIPTIONS), prefs.getString(key(src, SUBSCRIPTIONS), ""));
		editor.putString(key(dst, SUB_ACTIVE), prefs.getString(key(src, SUB_ACTIVE), ""));
		editor.putString(key(dst, SUB_COUNTRY_FILTER), prefs.getString(key(src, SUB_COUNTRY_FILTER), ""));
		editor.putString(key(dst, SUB_PROTO_FILTER), prefs.getString(key(src, SUB_PROTO_FILTER), ""));
	}

	private void removeProfile(SharedPreferences.Editor editor, int profile) {
		editor.remove(key(profile, NAME));
		editor.remove(key(profile, DNS_IPV4));
		editor.remove(key(profile, DNS_IPV6));
		editor.remove(key(profile, IPV4));
		editor.remove(key(profile, IPV6));
		editor.remove(key(profile, GLOBAL));
		editor.remove(key(profile, UDP_IN_TCP));
		editor.remove(key(profile, REMOTE_DNS));
		editor.remove(key(profile, APPS));
		editor.remove(key(profile, RULES));
		editor.remove(key(profile, RULES_DEFAULT_PROXY));
		editor.remove(key(profile, SUB_URL));
		editor.remove(key(profile, SUB_NODES));
		editor.remove(key(profile, SUB_RAW));
		editor.remove(key(profile, SUB_SELECTED));
		editor.remove(key(profile, ACTIVE_NODE));
		editor.remove(key(profile, SOCKS_SERVERS));
		editor.remove(key(profile, SOCKS_ACTIVE));
		editor.remove(key(profile, SUBSCRIPTIONS));
		editor.remove(key(profile, SUB_ACTIVE));
		editor.remove(key(profile, SUB_COUNTRY_FILTER));
		editor.remove(key(profile, SUB_PROTO_FILTER));
	}

	public int getProfileCount() {
		return prefs.getInt(PROFILE_COUNT, 1);
	}

	public int getSelected() {
		return prefs.getInt(SELECTED, 0);
	}

	public void setSelected(int index) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(SELECTED, index);
		editor.commit();
	}

	public String getProfileName() {
		return prefs.getString(key(NAME), "Default");
	}

	public void setProfileName(String name) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(NAME), name);
		editor.commit();
	}

	/* 以当前配置档为模板新建一个并切过去。 */
	public boolean addProfile(String name) {
		int count = getProfileCount();
		if (count >= MAX_PROFILES)
		  return false;

		SharedPreferences.Editor editor = prefs.edit();
		copyProfile(editor, getSelected(), count);
		editor.putString(key(count, NAME), name);
		editor.putInt(PROFILE_COUNT, count + 1);
		editor.putInt(SELECTED, count);
		editor.commit();
		return true;
	}

	/* 删除当前配置档，并把后面的配置档依次前移。 */
	public boolean deleteProfile() {
		int count = getProfileCount();
		if (count <= 1)
		  return false;

		int selected = getSelected();
		SharedPreferences.Editor editor = prefs.edit();
		for (int i = selected + 1; i < count; i++)
		  copyProfile(editor, i, i - 1);
		removeProfile(editor, count - 1);
		editor.putInt(PROFILE_COUNT, count - 1);
		if (selected >= count - 1)
		  editor.putInt(SELECTED, count - 2);
		editor.commit();
		return true;
	}

	/* 已保存的 clash.yml 订阅，以及当前启用的是哪一个。
	   启用中的那个才是订阅页会去抓取的。 */
	public List<Subscription> getSubscriptions() {
		return Subscription.decode(prefs.getString(key(SUBSCRIPTIONS), ""));
	}

	public void setSubscriptions(List<Subscription> list) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUBSCRIPTIONS), Subscription.encode(list));
		/* 已经不存在"默认订阅"了：每份订阅都会被抓取并合并。这里仍把第一个记为
		   活动 id，好让少数"要那份订阅"的调用方继续能用。 */
		String active = getActiveSubId();
		boolean present = false;
		for (Subscription s : list) {
			if (s != null && s.id != null && s.id.equals(active)) {
				present = true;
				break;
			}
		}
		if (!present)
		  editor.putString(key(SUB_ACTIVE),
			(list.isEmpty() || list.get(0).id == null) ? "" : list.get(0).id);
		editor.commit();
	}

	public String getActiveSubId() {
		return prefs.getString(key(SUB_ACTIVE), "");
	}

	public void setActiveSubId(String id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_ACTIVE), id == null ? "" : id);
		editor.commit();
	}

	public Subscription getActiveSubscription() {
		String id = getActiveSubId();
		if (id == null || id.isEmpty())
		  return null;
		for (Subscription s : getSubscriptions()) {
			if (id.equals(s.id))
			  return s;
		}
		return null;
	}

	/* 远程 clash.yml 订阅地址（按配置档保存）。 */
	public String getSubUrl() {
		return prefs.getString(key(SUB_URL), "");
	}

	public void setSubUrl(String url) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_URL), url);
		editor.commit();
	}

	public String getSubSelected() {
		return prefs.getString(key(SUB_SELECTED), "");
	}

	public void setSubSelected(String name) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_SELECTED), name);
		editor.commit();
	}

	/* 运行中的内核**真正在用**的节点（未知 / 隧道已断开时为空）。 */
	public String getActiveNode() {
		String s = prefs.getString(key(ACTIVE_NODE), "");
		return s == null ? "" : s;
	}

	public void setActiveNode(String name) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(ACTIVE_NODE), name == null ? "" : name);
		editor.commit();
	}

	public boolean hasSubscription() {
		/* 只有**启用中**的订阅才算可用上游：停用的订阅会被保留但从不合并，
		   所以不能让"已配置"成立。 */
		Subscription active = getActiveSubscription();
		if (active != null && active.enabled) {
			String raw = getSubRaw(active.id);
			if (raw != null && !raw.trim().isEmpty())
			  return true;
		}
		for (Subscription s : getSubscriptions()) {
			if (!s.enabled)
			  continue;
			String r = getSubRaw(s.id);
			if (r != null && !r.trim().isEmpty())
			  return true;
		}
		return false;
	}

	/* 手动配置的 SOCKS5 服务器，以及当前启用的是哪一个。
	   启用了服务器就由它取代订阅。 */
	public List<SocksServer> getSocksServers() {
		return SocksServer.decode(prefs.getString(key(SOCKS_SERVERS), ""));
	}

	public void setSocksServers(List<SocksServer> list) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_SERVERS), SocksServer.encode(list));
		editor.commit();
	}

	public String getActiveSocksId() {
		return prefs.getString(key(SOCKS_ACTIVE), "");
	}

	public void setActiveSocksId(String id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_ACTIVE), id == null ? "" : id);
		editor.commit();
	}

	/* 启用中的服务器；由订阅负责时返回 null。 */
	public SocksServer getActiveSocksServer() {
		String id = getActiveSocksId();
		if (id == null || id.isEmpty())
		  return null;
		for (SocksServer s : getSocksServers()) {
			if (id.equals(s.id))
			  return s;
		}
		return null;
	}

	/* 隧道在用的节点。为空表示"交给订阅自己决定"。隧道**已连接**时这里报的是内核
	   **真正选中**的节点（由 TProxyService 从运行中的配置读出后记录）；单纯的选择只是
	   "愿望"，拿它显示会让首页和通知栏宣称一个流量根本没走的节点（例如切换国家之后 ——
	   那需要重建配置才生效）。 */
	public String getCurrentNode() {
		SocksServer s = getActiveSocksServer();
		if (s != null)
		  return manualUpstreamLabel(s);
		String actual = getActiveNode();
		if (getEnable() && actual != null && !actual.isEmpty())
		  return actual;
		String sel = getSubSelected();
		if (sel != null && !sel.isEmpty())
		  return sel;
		return "";
	}

	/* **手动上游**在首页 / 通知栏 / 连接信息 里的显示名。协议必须取这台服务器自己的
	   type：以前这里无条件拼 "socks5://"，于是从订阅里长按加入的 vmess / vless /
	   hysteria2 节点在界面上统统显示成 "socks5://host:port" —— 看着像协议被换掉了，
	   其实配置里发的就是它自己的协议（见 SocksServer.type 与 MihomoConfig.manualSocksConfig）。
	   有名字就显示"协议 · 名字"（两者都不丢，与订阅页"协议标签 + 节点名"的读法一致）；
	   粘贴式节点常常没有可用的地址端口，那就只显示协议名。 */
	private String manualUpstreamLabel(SocksServer s) {
		String type = (s.type == null || s.type.isEmpty()) ? "socks5" : s.type;
		String name = s.name == null ? "" : s.name.trim();
		if (!name.isEmpty())
		  return type + " · " + name;
		String addr = s.addr == null ? "" : s.addr.trim();
		if (addr.isEmpty())
		  return type;
		return type + "://" + addr + ":" + s.port;
	}

	/* 当前启用订阅的原始 clash.yml 文本（如果机场是用 base64 下发的，这里已经是
	   解码后的内容）。 */
	public String getSubRaw() {
		Subscription sub = getActiveSubscription();
		if (sub != null)
		  return getSubRaw(sub.id);
		return prefs.getString(key(SUB_RAW), "");
	}

	/* 按订阅分别缓存：每份订阅保存自己抓下来的 YAML 与节点列表，
	   这样多份订阅可以各自拉取再合并成一个池。 */
	public String getSubRaw(String id) {
		if (id == null || id.isEmpty())
		  return "";
		return prefs.getString(key(SUB_RAW_PREFIX + id), "");
	}

	public void setSubRaw(String id, String yaml) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_RAW_PREFIX + id), yaml == null ? "" : yaml);
		editor.commit();
	}

	public String getSubNodes(String id) {
		if (id == null || id.isEmpty())
		  return "";
		return prefs.getString(key(SUB_NODES_PREFIX + id), "");
	}

	public void setSubNodes(String id, String json) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_NODES_PREFIX + id), json == null ? "" : json);
		editor.commit();
	}

	/* 一次写入**多份**订阅的节点缓存。一轮测速每 15 个节点就增量保存一次，而每次
	   commit() 都会把整份主文件重写一遍（里面还有几百 KB 的订阅原文）—— 逐份订阅
	   commit 等于成倍写盘，所以这里合并成一次。 */
	public void setSubNodesAll(Map<String, String> nodesById) {
		if (nodesById == null || nodesById.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		for (Map.Entry<String, String> e : nodesById.entrySet()) {
			if (e.getKey() == null || e.getKey().isEmpty())
			  continue;
			editor.putString(key(SUB_NODES_PREFIX + e.getKey()),
				e.getValue() == null ? "" : e.getValue());
		}
		editor.commit();
	}

	public void clearSubCache(String id) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.remove(key(SUB_RAW_PREFIX + id));
		editor.remove(key(SUB_NODES_PREFIX + id));
		editor.commit();
	}

	/* 自动选择：由内核的 url-test 组自己挑最快节点，每 `interval` 秒重测一次。
	   关闭表示"严格使用用户点的那个节点"。 */
	public boolean getAutoSelect() {
		return prefs.getBoolean(key(AUTO_SELECT), true);
	}

	public void setAutoSelect(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(AUTO_SELECT), enable);
		editor.commit();
	}

	public int getAutoSelectInterval() {
		return prefs.getInt(key(AUTO_SELECT_INTERVAL), DEFAULT_AUTO_INTERVAL);
	}

	public void setAutoSelectInterval(int seconds) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(AUTO_SELECT_INTERVAL), seconds);
		editor.commit();
	}

	/* url-test 的健康检查地址。为空表示"用默认值"。 */
	public String getAutoTestUrl() {
		String url = prefs.getString(key(AUTO_TEST_URL), "");
		if (url == null || url.trim().isEmpty())
		  return DEFAULT_TEST_URL;
		return url.trim();
	}

	/* ===== WebDAV 远程备份 ===== */

	public boolean getWebdavEnabled() {
		return prefs.getBoolean(key(WEBDAV_ENABLED), false);
	}

	public void setWebdavEnabled(boolean enabled) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(WEBDAV_ENABLED), enabled);
		editor.commit();
	}

	public String getWebdavUrl() {
		return trim(prefs.getString(key(WEBDAV_URL), ""));
	}

	public void setWebdavUrl(String url) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(WEBDAV_URL), url == null ? "" : url.trim());
		editor.commit();
	}

	public String getWebdavUser() {
		return trim(prefs.getString(key(WEBDAV_USER), ""));
	}

	public void setWebdavUser(String user) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(WEBDAV_USER), user == null ? "" : user.trim());
		editor.commit();
	}

	public String getWebdavPass() {
		return trim(prefs.getString(key(WEBDAV_PASS), ""));
	}

	public void setWebdavPass(String pass) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(WEBDAV_PASS), pass == null ? "" : pass);
		editor.commit();
	}

	public String getWebdavDir() {
		return trim(prefs.getString(key(WEBDAV_DIR), ""));
	}

	public void setWebdavDir(String dir) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(WEBDAV_DIR), dir == null ? "" : dir.trim());
		editor.commit();
	}

	/* "启用**且**填了地址"才算配好：只打开开关却没有地址，属于没配好 —— 这时备份
	   仍然落成本地订阅，而不是变成一次注定失败的请求。 */
	public boolean webdavReady() {
		return getWebdavEnabled() && !getWebdavUrl().isEmpty();
	}

	private static String trim(String s) {
		return s == null ? "" : s.trim();
	}

	public void setAutoTestUrl(String url) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(AUTO_TEST_URL), url == null ? "" : url.trim());
		editor.commit();
	}

	/* 手动测速时**单次探测**的超时秒数，限定在 1~30。 */
	public int getProxyTestTimeout() {
		int t = prefs.getInt(key(PROXY_TEST_TIMEOUT), DEFAULT_PROXY_TEST_TIMEOUT);
		return Math.max(MIN_PROXY_TEST_TIMEOUT, Math.min(MAX_PROXY_TEST_TIMEOUT, t));
	}

	public void setProxyTestTimeout(int seconds) {
		int t = Math.max(MIN_PROXY_TEST_TIMEOUT, Math.min(MAX_PROXY_TEST_TIMEOUT, seconds));
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(PROXY_TEST_TIMEOUT), t);
		editor.commit();
	}

	/* 探测完全没有响应时，是否判该节点"不可用"。 */
	public boolean getProbeTimeoutAsFail() {
		return prefs.getBoolean(key(PROBE_TIMEOUT_AS_FAIL), true);
	}

	public void setProbeTimeoutAsFail(boolean v) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(PROBE_TIMEOUT_AS_FAIL), v);
		editor.commit();
	}

	/* **单个节点**测速总共可以花多久（秒），限定在 5~120。 */
	public int getNodeTestLimit() {
		int t = prefs.getInt(key(NODE_TEST_LIMIT), DEFAULT_NODE_TEST_LIMIT);
		return Math.max(MIN_NODE_TEST_LIMIT, Math.min(MAX_NODE_TEST_LIMIT, t));
	}

	public void setNodeTestLimit(int seconds) {
		int t = Math.max(MIN_NODE_TEST_LIMIT, Math.min(MAX_NODE_TEST_LIMIT, seconds));
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(NODE_TEST_LIMIT), t);
		editor.commit();
	}

	/* clash-api 的 bearer 令牌。只生成一次并持久化，然后既注入到内核配置里，
	   又由每个控制请求携带（Authorization: Bearer）。配置里一旦设了 `secret:`，
	   mihomo 就会用 401 拒绝未鉴权的调用，所以客户端和配置必须一致 ——
	   这个唯一的来源让两者跨重启始终对齐。 */
	public String getSecret() {
		String s = prefs.getString(key(SECRET), null);
		if (s == null || s.isEmpty()) {
			s = randomSecret();
			SharedPreferences.Editor editor = prefs.edit();
			editor.putString(key(SECRET), s);
			editor.commit();
		}
		return s;
	}

	/* 持久化一个指定的 secret（用于采用用户在自定义配置里设的那个），
	   这样客户端用的令牌和内核用的就是同一个。 */
	public void setSecret(String s) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SECRET), s == null ? "" : s.trim());
		editor.commit();
	}

	/* 重新生成 clash-api 的 bearer 令牌。运行中的内核要等隧道**重新启动**才会知道新的
	   secret，所以调用方必须重新应用一次。 */
	public void resetSecret() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SECRET), randomSecret());
		editor.commit();
	}

	/* 32 个 [A-Za-z0-9] 字符：在 YAML 里无需转义就安全，而且猜不出来。 */
	private static String randomSecret() {
		final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		java.security.SecureRandom rnd = new java.security.SecureRandom();
		StringBuilder sb = new StringBuilder(32);
		for (int i = 0; i < 32; i++)
		  sb.append(chars.charAt(rnd.nextInt(chars.length())));
		return sb.toString();
	}

	/* 自动模式：顶层组默认指向哪个国家的 url-test 组。
	   "" 或 "GLOBAL" => 哪儿最快就用哪儿。 */
	public String getAutoSelectCountry() {
		return prefs.getString(key(AUTO_SELECT_COUNTRY), "");
	}

	public void setAutoSelectCountry(String cc) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(AUTO_SELECT_COUNTRY), cc == null ? "" : cc);
		editor.commit();
	}

	public String getSubCountryFilter() {
		return prefs.getString(key(SUB_COUNTRY_FILTER), "");
	}

	public void setSubCountryFilter(String cc) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_COUNTRY_FILTER), cc == null ? "" : cc);
		editor.commit();
	}

	public String getSubProtoFilter() {
		return prefs.getString(key(SUB_PROTO_FILTER), "");
	}

	public void setSubProtoFilter(String type) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_PROTO_FILTER), type == null ? "" : type);
		editor.commit();
	}

	/* 某个节点服务器所属的国家，由 GeoIp 事先解析。"" 表示还不清楚
	   （配置生成时按"其它"处理）。 */
	public String getServerCountry(String server) {
		synchronized (COUNTRY_MAP_LOCK) {
			JSONObject map = loadCountryMap();
			return map.optString(server == null ? "" : server, "");
		}
	}

	/* 把一次解析结果合并进映射表。写入是**合并式（coalesced）**的：GeoIp 会逐个节点
	   解析整份订阅，而每解析一个节点就重写（并 fsync）整个 preferences 文件是 O(n²)
	   的 IO。映射在内存里始终是完整的 —— flushCountryMap() 在一轮结束时把它落盘。 */
	public void setServerCountry(String server, String cc) {
		if (server == null || server.isEmpty() || cc == null || cc.isEmpty())
		  return;
		synchronized (COUNTRY_MAP_LOCK) {
			JSONObject map = loadCountryMap();
			try {
				if (cc.equals(map.optString(server, "")))
				  return;               /* 没有任何变化 */
				map.put(server, cc);
			} catch (JSONException e) {
				return;
			}
			countryMapDirty = true;
			long now = System.currentTimeMillis();
			if (now - countryMapLastWrite < COUNTRY_MAP_WRITE_MS)
			  return;
			writeCountryMap(map);
		}
	}

	/* 把上面"合并写入"还挂着的改动落盘。一轮延迟测试结束时调用，
	   这样下次生成配置时国家分组是完整的。 */
	public void flushCountryMap() {
		synchronized (COUNTRY_MAP_LOCK) {
			if (countryMapDirty && countryMapCache != null)
			  writeCountryMap(countryMapCache);
		}
	}

	/* 调用方必须持有 COUNTRY_MAP_LOCK。 */
	private void writeCountryMap(JSONObject map) {
		String s = map.toString();
		/* 让缓存与我们刚写出的内容保持一致，否则下一次 loadCountryMap() 会把自己
		   这次的写入当成"外部改动"而重新解析一遍。 */
		countryMapRaw = s;
		countryMapCache = map;
		countryMapDirty = false;
		countryMapLastWrite = System.currentTimeMillis();
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SERVER_COUNTRY_MAP), s);
		editor.commit();
	}

	/* SERVER_COUNTRY_MAP 的**已解析副本**。每次生成配置时都要对每个节点读一次
	   （几百个节点、每次启动要生成好几次），每次都重新解析 JSON 就是 O(n²)。这里改为
	   每次都重新读原始字符串（SharedPreferences 从内存返回它）并**先比较**：
	   另一个进程的写入仍能被发现，而内容没变时一次都不重新解析。 */
	private String countryMapRaw = null;
	private JSONObject countryMapCache = null;
	private boolean countryMapDirty = false;
	private long countryMapLastWrite = 0;
	private static final Object COUNTRY_MAP_LOCK = new Object();
	private static final long COUNTRY_MAP_WRITE_MS = 2000;

	private JSONObject loadCountryMap() {
		String s = prefs.getString(key(SERVER_COUNTRY_MAP), "");
		if (s == null)
		  s = "";
		if (countryMapCache != null && s.equals(countryMapRaw))
		  return countryMapCache;
		JSONObject o;
		if (s.isEmpty()) {
			o = new JSONObject();
		} else {
			try {
				o = new JSONObject(s);
			} catch (JSONException e) {
				o = new JSONObject();
			}
		}
		countryMapRaw = s;
		countryMapCache = o;
		return o;
	}

	/* 内核的本地 HTTP/SOCKS 端口。超出范围表示"从未设置过"。 */
	public int getProxyPort() {
		int port = prefs.getInt(key(PROXY_PORT), DEFAULT_PROXY_PORT);
		if (port < MIN_PROXY_PORT || port > MAX_PROXY_PORT)
		  return DEFAULT_PROXY_PORT;
		return port;
	}

	public void setProxyPort(int port) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(PROXY_PORT), port);
		editor.commit();
	}

	/* 把端口暴露给整个局域网（mihomo 的 allow-lan）。默认关闭：
	   在共享 Wi-Fi 上开一个开放代理，等于同网段谁都能用你的隧道。 */
	public boolean getAllowLan() {
		return prefs.getBoolean(key(ALLOW_LAN), false);
	}

	public void setAllowLan(boolean allow) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(ALLOW_LAN), allow);
		editor.commit();
	}

	/* VPN 没连接时也用内核的**真实转发延迟**测速（在 App 进程里加载一个无 TUN 的内核）。 */
	public boolean getPreloadCore() {
		return prefs.getBoolean(key(PRELOAD_CORE), true);
	}

	public void setPreloadCore(boolean v) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(PRELOAD_CORE), v);
		editor.commit();
	}



	/* 订阅页是否也列出「不可用 / 未测速」的节点。 */
	public boolean getShowUnavailable() {
		return prefs.getBoolean(key(SHOW_UNAVAILABLE), false);
	}

	public void setShowUnavailable(boolean v) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(SHOW_UNAVAILABLE), v);
		editor.commit();
	}

	public String getDnsIpv4() {
		return prefs.getString(key(DNS_IPV4), "8.8.8.8");
	}

	public void setDnsIpv4(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(DNS_IPV4), addr);
		editor.commit();
	}

	public String getDnsIpv6() {
		return prefs.getString(key(DNS_IPV6), "2001:4860:4860::8888");
	}

	public void setDnsIpv6(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(DNS_IPV6), addr);
		editor.commit();
	}

	public boolean getUdpInTcp() {
		return prefs.getBoolean(key(UDP_IN_TCP), false);
	}

	public void setUdpInTcp(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(UDP_IN_TCP), enable);
		editor.commit();
	}

	public boolean getRemoteDns() {
		return prefs.getBoolean(key(REMOTE_DNS), true);
	}

	public void setRemoteDns(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(REMOTE_DNS), enable);
		editor.commit();
	}

	public boolean getIpv4() {
		return prefs.getBoolean(key(IPV4), true);
	}

	public void setIpv4(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(IPV4), enable);
		editor.commit();
	}

	public boolean getIpv6() {
		return prefs.getBoolean(key(IPV6), true);
	}

	public void setIpv6(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(IPV6), enable);
		editor.commit();
	}

	public boolean getGlobal() {
		return prefs.getBoolean(key(GLOBAL), false);
	}

	public void setGlobal(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(GLOBAL), enable);
		editor.commit();
	}

	public Set<String> getApps() {
		return prefs.getStringSet(key(APPS), new HashSet<String>());
	}

	public void setApps(Set<String> apps) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putStringSet(key(APPS), apps);
		editor.commit();
	}

	/* 路由规则，按添加顺序（先匹配到的先生效）。 */
	public List<Rule> getRules() {
		List<Rule> rules = new ArrayList<Rule>();
		String value = prefs.getString(key(RULES), "");
		if (value.isEmpty())
		  return rules;
		for (String part : value.split(";")) {
			Rule rule = Rule.decode(part);
			if (rule != null)
			  rules.add(rule);
		}
		return rules;
	}

	public void setRules(List<Rule> rules) {
		StringBuilder sb = new StringBuilder();
		for (Rule rule : rules) {
			if (sb.length() > 0)
			  sb.append(';');
			sb.append(rule.encode());
		}
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(RULES), sb.toString());
		editor.commit();
	}

	/* 没有匹配任何规则的流量怎么走。 */
	public boolean getRulesDefaultProxy() {
		return prefs.getBoolean(key(RULES_DEFAULT_PROXY), true);
	}

	public void setRulesDefaultProxy(boolean proxy) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(RULES_DEFAULT_PROXY), proxy);
		editor.commit();
	}

	public String getRulesStrategy() {
		return prefs.getString(key(RULES_STRATEGY), RULES_STRATEGY_RULES);
	}

	public void setRulesStrategy(String s) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(RULES_STRATEGY),
			s == null ? RULES_STRATEGY_RULES : s);
		editor.commit();
	}

	public boolean getEnable() {
		return prefs.getBoolean(ENABLE, false);
	}

	/* 上一次启动失败的原因。TProxyService 会把它和 Enable=false 一起写入，
	   这样 UI 就能区分"从没启动过"和"启动后又挂了"，而不是一直显示"已连接"。 */
	public String getLastError() {
		return prefs.getString(LAST_ERROR, "");
	}

	public void setLastError(String error) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(LAST_ERROR, error == null ? "" : error);
		editor.commit();
	}

	public void clearLastError() {
		setLastError("");
	}

	public void setEnable(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(ENABLE, enable);
		editor.commit();
	}

	public boolean getLogEnabled() {
		return prefs.getBoolean(LOG_ENABLED, true);
	}

	public void setLogEnabled(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(LOG_ENABLED, enable);
		editor.commit();
	}

	/* 直接使用磁盘上的 config.yaml，而不是每次连接都按当前设置重新生成。 */
	public boolean getCustomConfig() {
		return prefs.getBoolean(CUSTOM_CONFIG, false);
	}

	public void setCustomConfig(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(CUSTOM_CONFIG, enable);
		editor.commit();
	}

	/* 累计所有会话的流量（全局量，不按配置档分）。写入用 commit()，因为写入方跑在
	   :native 进程里，而 MODE_MULTI_PROCESS 只在 commit 时才会重新读取。
	   以下统计类读写全部走 `statsPrefs`（独立的 SocksStats 文件），原因见 PREFS_STATS_NAME。 */
	public long getTotalTx() {
		return statsPrefs.getLong(STATS_TOTAL_TX, 0);
	}

	public long getTotalRx() {
		return statsPrefs.getLong(STATS_TOTAL_RX, 0);
	}

	public void setTotalTraffic(long tx, long rx) {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putLong(STATS_TOTAL_TX, tx);
		editor.putLong(STATS_TOTAL_RX, rx);
		editor.commit();
	}

	public void resetTotalTraffic() {
		setTotalTraffic(0, 0);
	}

	/* :native 服务那边知道的一切，一次 commit 全部写出。 */
	public long getSessionTx() {
		return statsPrefs.getLong(STATS_SESSION_TX, 0);
	}

	public long getSessionRx() {
		return statsPrefs.getLong(STATS_SESSION_RX, 0);
	}

	public long getRateTx() {
		return statsPrefs.getLong(STATS_RATE_TX, 0);
	}

	public long getRateRx() {
		return statsPrefs.getLong(STATS_RATE_RX, 0);
	}

	public long getProxyTotalTx() {
		return statsPrefs.getLong(PROXY_TOTAL_TX, 0);
	}

	public long getProxyTotalRx() {
		return statsPrefs.getLong(PROXY_TOTAL_RX, 0);
	}

	public long getProxySessionTx() {
		return statsPrefs.getLong(PROXY_SESSION_TX, 0);
	}

	public long getProxySessionRx() {
		return statsPrefs.getLong(PROXY_SESSION_RX, 0);
	}

	public long getProxyRateTx() {
		return statsPrefs.getLong(PROXY_RATE_TX, 0);
	}

	public long getProxyRateRx() {
		return statsPrefs.getLong(PROXY_RATE_RX, 0);
	}

	public void setProxyStats(long totalTx, long totalRx, long sessionTx, long sessionRx,
			long rateTx, long rateRx) {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putLong(PROXY_TOTAL_TX, totalTx);
		editor.putLong(PROXY_TOTAL_RX, totalRx);
		editor.putLong(PROXY_SESSION_TX, sessionTx);
		editor.putLong(PROXY_SESSION_RX, sessionRx);
		editor.putLong(PROXY_RATE_TX, rateTx);
		editor.putLong(PROXY_RATE_RX, rateRx);
		editor.commit();
	}

	public void resetProxyStats() {
		setProxyStats(0, 0, 0, 0, 0, 0);
	}

	public void setStats(long totalTx, long totalRx, long sessionTx, long sessionRx,
			long rateTx, long rateRx) {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putLong(STATS_TOTAL_TX, totalTx);
		editor.putLong(STATS_TOTAL_RX, totalRx);
		editor.putLong(STATS_SESSION_TX, sessionTx);
		editor.putLong(STATS_SESSION_RX, sessionRx);
		editor.putLong(STATS_RATE_TX, rateTx);
		editor.putLong(STATS_RATE_RX, rateRx);
		editor.commit();
	}

	/* 两套计数**在同一个 editor/commit** 里写入。采样器每秒把它们一起发布；
	   分两次 commit 意味着每拍要写两遍完整的 preferences，而且中间还存在一个窗口：
	   UI 读到了隧道总量却读不到代理量（而它们是并排显示的）。 */
	public void setAllStats(long totalTx, long totalRx, long sessionTx, long sessionRx,
			long rateTx, long rateRx,
			long pTotalTx, long pTotalRx, long pSessionTx, long pSessionRx,
			long pRateTx, long pRateRx) {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putLong(STATS_TOTAL_TX, totalTx);
		editor.putLong(STATS_TOTAL_RX, totalRx);
		editor.putLong(STATS_SESSION_TX, sessionTx);
		editor.putLong(STATS_SESSION_RX, sessionRx);
		editor.putLong(STATS_RATE_TX, rateTx);
		editor.putLong(STATS_RATE_RX, rateRx);
		editor.putLong(PROXY_TOTAL_TX, pTotalTx);
		editor.putLong(PROXY_TOTAL_RX, pTotalRx);
		editor.putLong(PROXY_SESSION_TX, pSessionTx);
		editor.putLong(PROXY_SESSION_RX, pSessionRx);
		editor.putLong(PROXY_RATE_TX, pRateTx);
		editor.putLong(PROXY_RATE_RX, pRateRx);
		editor.commit();
	}

	/* 最近请求：TProxyService 在每条连接关闭时记录下来的紧凑对象 JSON 数组，
	   让 UI 事后还能看到"什么流量去了哪里"。保持 JSON 形式（而不是压成
	   "k:v;..." 字符串）可以保留字段名，读起来也简单。 */
	private static final String RECENT_REQUESTS = "recentRequests";
	public void setRecentRequests(String json) {
		statsPrefs.edit().putString(RECENT_REQUESTS, json).apply();
	}
	public String getRecentRequests() {
		return statsPrefs.getString(RECENT_REQUESTS, "");
	}

	/* 实时的 /connections 快照，由 TProxyService（:native 进程）发布，好让"连接"页面
	   能读到它 —— 那个页面跑在**主进程**，既够不到进程内的动作桥，也拿不到服务实例。
	   内容是紧凑对象 {t,r,p,u,d,s} 的 JSON 数组。这里用 commit() 而不是 apply()，
	   因为 MODE_MULTI_PROCESS 只在**已提交**的写入上才会跨进程重新读取。 */
	private static final String CONN_SNAPSHOT = "connSnapshot";
	public void setConnSnapshot(String json) {
		statsPrefs.edit().putString(CONN_SNAPSHOT, json).commit();
	}
	public String getConnSnapshot() {
		return statsPrefs.getString(CONN_SNAPSHOT, "");
	}

	/* 按应用的快照："包名:上行:下行;包名:上行:下行;..."。 */
	public String getAppBase() {
		return statsPrefs.getString(STATS_APP_BASE, "");
	}

	public String getAppTotal() {
		return statsPrefs.getString(STATS_APP_TOTAL, "");
	}

	public void setAppStats(String base, String total) {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putString(STATS_APP_BASE, base);
		editor.putString(STATS_APP_TOTAL, total);
		editor.commit();
	}

	public void resetStats() {
		SharedPreferences.Editor editor = statsPrefs.edit();
		editor.putLong(STATS_TOTAL_TX, 0);
		editor.putLong(STATS_TOTAL_RX, 0);
		editor.putLong(STATS_SESSION_TX, 0);
		editor.putLong(STATS_SESSION_RX, 0);
		editor.putLong(STATS_RATE_TX, 0);
		editor.putLong(STATS_RATE_RX, 0);
		editor.putString(STATS_APP_BASE, "");
		editor.putString(STATS_APP_TOTAL, "");
		editor.commit();
	}

	public static Map<String, long[]> parseAppStats(String value) {
		Map<String, long[]> map = new HashMap<String, long[]>();
		if (value == null || value.isEmpty())
		  return map;
		for (String part : value.split(";")) {
			String[] f = part.split(":");
			if (f.length != 3)
			  continue;
			try {
				map.put(f[0], new long[] { Long.parseLong(f[1]), Long.parseLong(f[2]) });
			} catch (NumberFormatException e) {
			}
		}
		return map;
	}

	public static String formatAppStats(Map<String, long[]> map) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, long[]> e : map.entrySet()) {
			if (sb.length() > 0)
			  sb.append(';');
			long[] v = e.getValue();
			sb.append(e.getKey()).append(':').append(v[0]).append(':').append(v[1]);
		}
		return sb.toString();
	}

	/* 走隧道的是哪些包：用户选中的应用；没设白名单时则是所有带 INTERNET 权限的应用。
	   空白名单的含义是"除了本 App 之外的一切"，这正是 VpnService 的行为，
	   所以这里也必须展开成同样的集合。 */
	public Set<String> getRoutedApps(Context context) {
		Set<String> apps = new HashSet<String>();
		if (!getGlobal()) {
			apps.addAll(getApps());
			if (!apps.isEmpty())
			  return apps;
		}

		PackageManager pm = context.getPackageManager();
		for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
			if (info.packageName.equals(context.getPackageName()))
			  continue;
			if (info.requestedPermissions == null)
			  continue;
			if (!Arrays.asList(info.requestedPermissions).contains(android.Manifest.permission.INTERNET))
			  continue;
			apps.add(info.packageName);
		}
		return apps;
	}

	public void registerOnChange(SharedPreferences.OnSharedPreferenceChangeListener listener) {
		prefs.registerOnSharedPreferenceChangeListener(listener);
	}

	public void unregisterOnChange(SharedPreferences.OnSharedPreferenceChangeListener listener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener);
	}

	/* 用哪套配色（见 ThemeManager.*）。 */
	public static int getTheme(Context context) {
		SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
		return sp.getInt(THEME, ThemeManager.NEON);
	}

	public int getTheme() {
		return prefs.getInt(THEME, ThemeManager.NEON);
	}

	public void setTheme(int id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(THEME, id);
		editor.commit();
	}

	/* VPN 网卡的 MTU。以前是 8500（巨型帧的数值），远高于物理链路实际能承载的
	   1500。小包（TCP 握手、DNS）还是能过去，所以隧道看起来"已连接"，而一切真正的
	   数据传输都被静默丢弃。 */
	public int getTunnelMtu() {
		return 1500;
	}
}
