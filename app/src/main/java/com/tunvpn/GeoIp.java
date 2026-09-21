/*
 ============================================================================
 文件名  : GeoIp.java
 说明    : 把节点的服务器地址解析成国家码（ISO-3166 两位）。本地优先：先用内置的
           GeoLite2-Country.mmdb（纯 Java 的 MaxMind DB 读取器）离线解析，所以国家
           分组完全不需要联网；数据库缺失时才退化为尽力而为的在线查询。结果在内存
           里缓存，并通过 Preferences 跨启动持久化。
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeoIp {
	/* 无法解析国家的节点归到这个分组后缀。 */
	public static final String UNKNOWN = "OTHER";

	private static final String TAG = "GeoIp";
	private static final int TIMEOUT = 3000;
	/* 内置 MaxMind 国家库的文件名。把该文件放进 app/src/main/assets/ 即可启用离线解析。 */
	private static final String MMDB_NAME = "GeoLite2-Country.mmdb";
	/* 在线兜底接口，按顺序尝试。ipapi.co 返回纯文本 "US"；ip-api.com 返回 JSON
	   {countryCode:"US"}。 */
	private static final String[] ENDPOINTS = {
		"https://ipapi.co/%s/country/",
		"http://ip-api.com/json/%s?fields=countryCode"
	};

	/* 服务器地址 -> 已解析的国家码，进程生命周期内有效。失败的查询**不会**被当成国家
	   缓存（以后重试还能成功），但会记在 recentFailures 里一段时间：否则一个解析不了的
	   主机每轮都要重付一次 DNS 超时的代价（几百个节点的一轮就会因此爬行）。 */
	private static final Map<String, String> cache = new ConcurrentHashMap<String, String>();
	private static final Map<String, Long> recentFailures =
		new ConcurrentHashMap<String, Long>();
	private static final long FAIL_BACKOFF_MS = 10 * 60 * 1000L;

	/* 懒加载的本地 mmdb 读取器；null 表示不可用（且本次进程内不再重试，见 mmdbTried）。 */
	private static MMDB mmdb = null;
	private static boolean mmdbTried = false;
	private static final Object MMDB_LOCK = new Object();

	/* 取某个服务器地址的国家码。顺序：持久化的映射（离线）→ 内存缓存 → 本地数据库 →
	   仅在数据库不可用时才走在线查询。任何失败都返回 UNKNOWN（且**不**缓存该失败）。 */
	public static String countryOf(Preferences prefs, String server) {
		return countryOf(prefs, server, false);
	}

	/* refresh=true 表示从源头重新解析并覆盖已缓存的值，这样标错的节点（例如美国 IP 上
	   留着旧的 "RU"）可以通过重跑一次测速自我纠正，而不是永远错下去。内存缓存仍会被
	   查询，避免同一轮测速对同一台主机重复联网。 */
	public static String countryOf(Preferences prefs, String server, boolean refresh) {
		if (server == null || server.isEmpty())
		  return UNKNOWN;
		if (!refresh) {
			String cached = prefs.getServerCountry(server);
			if (cached != null && !cached.isEmpty())
			  return cached;
		}
		String mem = cache.get(server);
		if (mem != null)
		  return mem;
		/* 把最近的失败记一段时间。否则刚失败过的主机**每一轮**都要重试，而每次尝试都
		   可能耗满 DNS 超时（外加两次 HTTP 兜底）——几百个节点时，这就是一轮爬行的原因。 */
		Long failedAt = recentFailures.get(server);
		if (failedAt != null && System.currentTimeMillis() - failedAt < FAIL_BACKOFF_MS)
		  return UNKNOWN;
		String ip = resolve(server);
		String cc = ip != null ? lookupLocal(prefs, ip) : null;
		if (cc == null && ip != null)
		  cc = lookup(ip);
		if (cc == null || cc.isEmpty()) {
			recentFailures.put(server, System.currentTimeMillis());
			return UNKNOWN;
		}
		recentFailures.remove(server);
		prefs.setServerCountry(server, cc);
		cache.put(server, cc);
		return cc;
	}

	/* 从内置 GeoLite2 数据库取国家码；数据库缺失或该地址未被收录时返回 null。 */
	@SuppressWarnings("unchecked")
	private static String lookupLocal(Preferences prefs, String ip) {
		Context ctx = prefs.getContext();
		if (ctx == null)
		  return null;
		MMDB db = getReader(ctx);
		if (db == null)
		  return null;
		try {
			InetAddress addr = InetAddress.getByName(ip);
			Object rec = db.get(addr);
			if (rec instanceof Map) {
				Map<String, Object> m = (Map<String, Object>) rec;
				String cc = isoFrom(m.get("country"));
				if (cc == null)
				  cc = isoFrom(m.get("registered_country"));
				return cc;
			}
		} catch (Exception e) {
			Log.w(TAG, "mmdb lookup failed for " + ip + ": " + e);
		}
		return null;
	}

	private static String isoFrom(Object o) {
		if (o instanceof Map) {
			Object iso = ((Map<String, Object>) o).get("iso_code");
			if (iso instanceof String)
			  return ((String) iso).toUpperCase();
		}
		return null;
	}

	/* 只打开一次内置数据库。asset 不存在时返回 null（并记住，不再重试），此时调用方
	   退化为在线查询。 */
	private static MMDB getReader(Context ctx) {
		synchronized (MMDB_LOCK) {
			if (mmdbTried)
			  return mmdb;
			mmdbTried = true;
			try {
				InputStream in = ctx.getAssets().open(MMDB_NAME);
				mmdb = MMDB.open(MMDB.readAll(in));
			} catch (Exception e) {
				Log.w(TAG, "GeoLite2 (" + MMDB_NAME + ") not found in assets, " +
					"falling back to network lookup: " + e);
				mmdb = null;
			}
			return mmdb;
		}
	}

	/* 单次域名解析允许花费的最长时间。 */
	private static final int DNS_TIMEOUT_MS = 3000;
	/* DNS 跑在这个池里，这样超时就能被放弃：4 个线程，一个永不返回的任务只会占住它
	   自己那个线程（那时调用方早就放弃它了）。 */
	private static final java.util.concurrent.ExecutorService DNS_POOL =
		java.util.concurrent.Executors.newFixedThreadPool(4,
			new java.util.concurrent.ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "geoip-dns");
					t.setDaemon(true);
					return t;
				}
			});

	/* 域名 → IP。本来就是 IP 字面量则原样返回；失败返回 null。
	   InetAddress.getByName() **没有超时**：解析器坏掉或不可达时会阻塞几十秒，而一轮
	   测速每测一个节点就要解析一次主机 —— 于是所有测速线程都可能堵在这里，界面上的
	   进度条则冻在 0%。所以这里强制自己的截止时间。 */
	/* 形如 "1.2.3.4" 的字面量（只含数字和点）。手写判断替代 String.matches：
	   后者每次都走一遍正则编译/匹配，而下面两个判断在解析国别时会被**逐节点**调用。 */
	private static boolean isDottedQuad(String s) {
		if (s.isEmpty())
		  return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '.' && (c < '0' || c > '9'))
			  return false;
		}
		return true;
	}

	/* 恰好两个 ASCII 字母（国家码）。同上，避免每次编译正则。 */
	private static boolean isAlpha2(String s) {
		if (s.length() != 2)
		  return false;
		for (int i = 0; i < 2; i++) {
			char c = s.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')))
			  return false;
		}
		return true;
	}

	private static String resolve(String server) {
		if (isDottedQuad(server) || server.contains(":"))
		  return server;
		final String host = server;
		java.util.concurrent.Future<String> f =
			DNS_POOL.submit(new java.util.concurrent.Callable<String>() {
				@Override
				public String call() {
					try {
						return InetAddress.getByName(host).getHostAddress();
					} catch (Exception e) {
						return null;
					}
				}
			});
		try {
			return f.get(DNS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (Throwable e) {
			f.cancel(true);
			Log.w(TAG, "DNS 解析超时（" + DNS_TIMEOUT_MS + "ms），跳过国别判定：" + server);
			return null;
		}
	}

	/* 依次尝试各个在线接口，直到某一个返回两位国家码
	   （仅当本地数据库不可用时才走这条路）。 */
	private static String lookup(String ip) {
		for (String tmpl : ENDPOINTS) {
			String url = String.format(tmpl, ip);
			String body = httpGet(url);
			if (body == null)
			  continue;
			String cc = parse(body);
			if (cc != null && !cc.isEmpty())
			  return cc;
		}
		return null;
	}

	private static String parse(String body) {
		String s = body.trim();
		/* ip-api.com 返回的是 JSON，从中取出 countryCode。 */
		if (s.startsWith("{")) {
			int i = s.indexOf("\"countryCode\"");
			if (i < 0)
			  return "";
			int c = s.indexOf(':', i);
			if (c < 0)
			  return "";
			int q1 = s.indexOf('"', c);
			if (q1 < 0)
			  return "";
			int q2 = s.indexOf('"', q1 + 1);
			if (q2 < 0)
			  return "";
			return s.substring(q1 + 1, q2).toUpperCase();
		}
		/* ipapi.co 直接返回裸国家码，例如 "US\n"。 */
		if (isAlpha2(s))
		  return s.toUpperCase();
		return "";
	}

	private static String httpGet(String urlStr) {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(TIMEOUT);
			conn.setReadTimeout(TIMEOUT);
			conn.setRequestProperty("User-Agent", "tunVPN");
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
			  return null;
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null)
				  sb.append(line);
			}
			return sb.toString();
		} catch (Exception e) {
			Log.d(TAG, "lookup failed for " + urlStr + ": " + e);
			return null;
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}
}
