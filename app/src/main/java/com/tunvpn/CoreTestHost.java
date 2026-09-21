/*
 ============================================================================
 文件名  : CoreTestHost.java
 说明    : 在 **App 进程内**再跑一个**无 TUN** 的 mihomo 内核，这样即使 VPN 没连接，
           测速也能用内核的**真实转发延迟**。测一个节点本来就不需要 TUN（内核自己直连
           节点服务器即可），FlClash 就是这么测的。这个测速内核刻意不配 tun、不配
           mixed-port、不配 external-controller，所以永远不会和 :native 进程里的隧道
           内核打架。
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;
import android.util.Log;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;

import org.json.JSONObject;

import java.io.File;

class CoreTestHost {
	private static final String TAG = "CoreTestHost";

	/* 由类锁保护（ensureReady 是 synchronized）。 */
	private static boolean loaded = false;
	/* 配置**真正应用**到测速内核之后才置 true。isReady() 只能在这之后报 ready：
	   否则"库已加载但 quickSetup 失败"会让所有探测都发给一个根本没有节点的内核。 */
	private static boolean applied = false;
	private static String lastConfig = "";

	private CoreTestHost() { }

	/* 忘掉已应用的配置，让下一次 ensureReady() 重新生成并重新应用
	   （相关开关变化时调用）。 */
	static synchronized void reset() {
		lastConfig = "";
		applied = false;
	}

	static synchronized boolean isReady() {
		return applied;
	}

	/* 加载测速内核并应用当前节点集。内核可以服务 testDelay 时返回 true。
	   可以反复调用：配置每次都会重新生成，但只有**真的变了**才重新应用。 */
	static synchronized boolean ensureReady(Context context, Preferences prefs) {
		try {
			Context app = context.getApplicationContext();
			if (!loaded) {
				Clash.INSTANCE.load(app.getApplicationInfo().nativeLibraryDir);
				loaded = true;
			}
			File home = new File(app.getFilesDir(), "coretest");
			if (!home.exists())
			  home.mkdirs();
			/* 应用配置；如果内核明确拒绝**某一个**节点，就把那个节点剔掉重试。
			   mihomo 是**整份文件**一起校验的（"proxy 645: invalid REALITY short
			   ID"），所以 600 个节点的订阅里有 1 个坏节点，就会导致**所有**节点都测不了
			   —— 而只含某个国家的小节点池碰巧能过。 */
			for (int attempt = 0; attempt <= MAX_DROP_RETRIES; attempt++) {
				String cfg;
				try {
					cfg = MihomoConfig.buildTestCoreConfig(prefs, home, badProxies);
				} catch (Throwable e) {
					Log.w(TAG, "build test config failed: " + e);
					TProxyService.log("测试内核配置生成失败：" + e);
					return false;
				}
				/* "生成的配置到底带没带上节点"的直接证据：它自己 `proxies:` 段的规模。 */
				if (cfg == null)
				  return true;
				boolean same = cfg.equals(lastConfig);
				int nodeCount = countProxies(cfg);
				TProxyService.log("测试内核配置：" + nodeCount + " 个代理条目 · "
					+ cfg.length() + " 字节 · 路径 "
					+ new File(home, "config.yaml").getAbsolutePath()
					+ (same ? "（与上次相同，跳过重新应用）" : "")
					+ (badProxies.isEmpty() ? ""
						: (" · 已排除 " + badProxies.size() + " 个内核拒绝的节点")));
				if (same) {
					applied = true;
					return true;
				}
				Apply res = applyConfig(home, nodeCount);
				if (res.timeout) {
					/* 还在解析配置：**不算**就绪。此时探测等于把所有节点都发给一个
					   只加载了一半的内核。 */
					applied = false;
					lastConfig = "";
					return false;
				}
				if (res.err == null) {
					lastConfig = cfg;
					applied = true;
					TProxyService.log("测试内核就绪（无 TUN 实例，节点用于隔离测速）");
					return true;
				}
				String bad = MihomoConfig.badProxyNameFromError(res.err, cfg);
				if (bad != null && !bad.isEmpty() && badProxies.add(bad)) {
					TProxyService.log("测试内核: 内核拒绝节点「" + bad + "」（" + res.err
						+ "）→ 已从测速配置排除并重试（第 " + (attempt + 1) + " 次）");
					continue;
				}
				Log.w(TAG, "quickSetup: " + res.err);
				TProxyService.log("测试内核 quickSetup 失败：" + res.err);
				applied = false;
				lastConfig = "";
				return false;
			}
			TProxyService.log("测试内核: 连续排除 " + MAX_DROP_RETRIES
				+ " 个节点仍无法加载，放弃本轮（详见上面每行）");
			applied = false;
			lastConfig = "";
			return false;
		} catch (Throwable e) {
			Log.w(TAG, "ensureReady failed: " + e);
			TProxyService.log("测试内核加载失败：" + e);
			return false;
		}
	}

	/* 内核已经拒绝过这个节点（见 badProxies）时为 true：它永远测不了，调用方应直接
	   报"未测速"，而不是在它身上浪费探测。 */
	static boolean isRejected(String name) {
		return name != null && badProxies.contains(name);
	}

	/* 一次 quickSetup 尝试的结果。 */
	private static final class Apply {
		String err;        /* 内核返回的错误文本；成功时为 null */
		boolean timeout;   /* 在按规模放大的期限内没有收到回调 */
	}

	/* 对 buildTestCoreConfig 刚写出的文件做一次 quickSetup 尝试。 */
	private static Apply applyConfig(File home, int nodeCount) {
		Apply res = new Apply();
		String initParams = "{\"home-dir\":\"" + home.getAbsolutePath() + "\"}";
		String setupParams = "{\"selected-map\":{},\"profile\":\""
			+ new File(home, "config.yaml").getAbsolutePath() + "\"}";
		final Object lock = new Object();
		final boolean[] done = { false };
		final String[] err = { null };
		Clash.INSTANCE.quickSetup(initParams, setupParams, new InvokeInterface() {
			@Override
			public void onResult(String result) {
				if (result != null && !result.isEmpty())
				  err[0] = result;
				synchronized (lock) { done[0] = true; lock.notifyAll(); }
			}
		});
		/* 等待时间随配置规模变化：解析几百个节点要好几秒，而原来**固定 8 秒**的窗口
		   会把一个还在加载的内核判成"就绪" —— 于是每个探测都失败，整个选择结果全变成
		   不可用。超时就是**失败**，绝不当作"悄悄成功"。 */
		synchronized (lock) {
			long waitMs = Math.min(60000L, 8000L + nodeCount * 30L);
			long end = System.currentTimeMillis() + waitMs;
			while (!done[0]) {
				long left = end - System.currentTimeMillis();
				if (left <= 0)
				  break;
				try {
					lock.wait(left);
				} catch (InterruptedException ie) {
					/* 保留标志、停止等待：调用方既然中断我们，就是想撤了，这时配置
					   就是"没准备好"。 */
					Thread.currentThread().interrupt();
					break;
				}
			}
			if (!done[0]) {
				TProxyService.log("测试内核配置加载超时（等待 " + waitMs + "ms · " + nodeCount
					+ " 个节点）→ 本次不测速，等它加载完再试");
				res.timeout = true;
				return res;
			}
		}
		res.err = err[0];
		return res;
	}

	/* "testDelay" 动作可能接受的**参数形状**。这个库没有任何文档，所以按顺序试，
	   并把被接受的那种记下来（之后探测只花一次调用，而不是每个节点都试一遍）。
	   以下是内核自己的报错文本告诉我们的：
	     * `data` 必须是**字符串**形式的参数 JSON，不能是内联对象 ——
	       否则返回：{"data":"invalid data type","code":-1}
	     * 其中的 `timeout` 必须是**数字**：传字符串会得到
	       "json: cannot unmarshal string into Go struct field
	       TestDelayParams.timeout of type int64"。
	   所以"数字 timeout"的形状排在第一位。 */
	private static final String[][] DELAY_SHAPES = {
		{ "proxy-name", "test-url", "n" },   /* the shape the core asked for */
		{ "proxy-name", "test-url", "s" },
		{ "proxy-name", "url", "n" },        /* shorter url key */
		{ "name", "url", "n" },              /* shortest keys */
		{ "name", "url", "s" },
	};
	private static volatile int delayShape = -1;
	/* 串行化这个"只决定一次"的过程（见 testDelay）。 */
	private static final Object SHAPE_LOCK = new Object();
	/* 逐个探测的成功日志，在**单节点**测速时有用；但一轮上千个节点时纯粹是 I/O 噪音
	   （每写一行都要开关一次日志文件）。 */
	private static volatile boolean verbose = true;

	static void setVerbose(boolean v) {
		verbose = v;
	}

	/* 内核拒绝过的节点名（mihomo 一次校验整份文件，所以以前 1 个坏节点就让**所有**
	   节点都测不了）。进程生命周期内保留：内核解析不了的节点，一直都不能用。 */
	private static final java.util.Set<String> badProxies =
		java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	/* 一次 ensureReady() 里最多剔掉多少个被拒节点。每次重试都要重新生成 + 重新应用
	   配置，所以这个数字刻意很小 —— 剩下的坏节点留给后续调用再剔。 */
	private static final int MAX_DROP_RETRIES = 8;

	/* 第 shape 种形状下 testDelay 动作的参数体。与 ClashApiServer 共用
	   （它响应 REST 的 /delay 路由时用的是同一套参数）。 */
	static String delayData(int shape, String proxyName, String url, int timeoutMs) {
		try {
			String[] s = DELAY_SHAPES[shape];
			JSONObject d = new JSONObject();
			d.put(s[0], proxyName);
			d.put(s[1], url);
			d.put("timeout", "s".equals(s[2]) ? String.valueOf(timeoutMs) : timeoutMs);
			return d.toString();
		} catch (Throwable e) {
			return null;
		}
	}

	/* 用**已记住**的形状构造参数体（还没有任何一种被接受时用形状 0）。 */
	static String delayData(String proxyName, String url, int timeoutMs) {
		return delayData(delayShape < 0 ? 0 : delayShape, proxyName, url, timeoutMs);
	}

	/* 一次探测尝试。 */
	private static final class Probe {
		long measured = -1;   /* 内核测出延迟时为 >=0 */
		boolean answered;     /* 内核给出了延迟判定 */
		boolean rejected;     /* 内核拒绝了**这组参数**（形状不对） */
		String raw;
	}

	/* 内核的**隔离式**逐节点探测（"testDelay" 动作，FlClash 用的就是这个）。
	   成功返回延迟（>=0）；内核自己报"该节点无法完成探测"时返回 -2；完全没有判定
	   （桥不可用 / 没有回包 / 回包看不懂）时返回 null —— 后者由调用方报成「未测速」，
	   而不是「不可用」。把这两种情况搞混，正是当初好节点被刷成"不可用"的原因。 */
	static Long testDelay(String proxyName, String url, int timeoutMs) {
		return testDelay(proxyName, url, timeoutMs, 0);
	}

	/* 同上，但显式限定"最多等回包多久"。调用方的**每节点测速上限**
	   （设置 → 每节点测速上限）可能比探测自身的超时更**紧**；等超过它就是让那个设置
	   说谎（调用方放弃之后，内核还占着那唯一的桥槽位），所以这个上界一路传到桥的等待
	   时间上。传 0 = 用默认值（探测超时 + 5s）。 */
	static Long testDelay(String proxyName, String url, int timeoutMs, long waitBound) {
		final long waitMs = waitBound > 0
			? Math.max(500L, Math.min(timeoutMs + 5000L, waitBound))
			: timeoutMs + 5000L;
		/* 本进程里没有内核，意味着要走 HTTP 去够 :native 里的隧道内核，所以这里直接
		   回"无判定"、不做桥调用 —— 否则每个探测都会记一条"动作被跳过"。 */
		if (!isReady())
		  return null;
		/* "哪种形状被接受"是一个**共享的一次性闩锁**。必须在工作线程铺开**之前**由单线程
		   定下来：多个探测并发抢它，可能在形状 #0 其实仍被拒的时候就把它锁死（或反之），
		   于是所有节点一起失败 —— 也就是"单个测速能用、测速全部全废"那个现象。 */
		if (delayShape < 0) {
			synchronized (SHAPE_LOCK) {
				if (delayShape < 0) {
					int found = detectShape(proxyName, url, timeoutMs, waitMs);
					if (found < 0) {
						TProxyService.log("delay: 所有参数形状都被内核拒绝，测速无法进行");
						return null;
					}
					delayShape = found;
					TProxyService.log("delay: 采用参数形状#" + found + "（"
						+ DELAY_SHAPES[found][0] + "/" + DELAY_SHAPES[found][1]
						+ "/timeout=" + DELAY_SHAPES[found][2] + "）");
				}
			}
		}
		int shape = delayShape;
		if (shape < 0)
		  return null;
		Probe p = probe(shape, proxyName, url, timeoutMs, waitMs);
		if (p.rejected) {
			/* 已记住的形状不再被接受（内核被换成了别的构建版本？）：忘掉它，让下一次
			   调用重新探测，而不是让本轮剩下的所有节点全部失败。 */
			synchronized (SHAPE_LOCK) {
				delayShape = -1;
			}
			TProxyService.log("delay: 形状#" + shape + " 失效，下次调用重新探测");
			return null;
		}
		if (p.measured >= 0)
		  return Long.valueOf(p.measured);
		if (p.answered)
		  return Long.valueOf(-2L);
		return null;
	}

	/* 逐个形状试，直到内核不再抱怨参数。**只在单线程上跑**。
	   这里的"被接受"指的是内核没有拒绝这个参数体，**不是**它给出了延迟：如果在这里
	   等一个真实答案，每种形状都要耗掉一整次探测（5 ×（超时+5s），超时调大时超过
	   一分钟），而且这段卡顿发生在**任何一个节点被测之前**，用户看到的只是进度条
	   冻在 0%。 */
	private static int detectShape(String proxyName, String url, int timeoutMs,
			long waitMs) {
		for (int i = 0; i < DELAY_SHAPES.length; i++) {
			Probe p = probe(i, proxyName, url, timeoutMs, waitMs);
			if (!p.rejected)
			  return i;
			TProxyService.log("delay: 内核不接受参数形状#" + i + "（" + DELAY_SHAPES[i][0]
				+ "/" + DELAY_SHAPES[i][1] + "/timeout=" + DELAY_SHAPES[i][2]
				+ "）→ 试下一种 · 内核回 " + truncate(p.raw));
		}
		return -1;
	}

	/* 用第 shape 种形状做一次尝试。 */
	private static Probe probe(int shape, String proxyName, String url, int timeoutMs,
			long waitMs) {
		Probe r = new Probe();
		try {
			String params = delayData(shape, proxyName, url, timeoutMs);
			/* 探测可能在内核里跑满 `timeoutMs`，所以要等得比它久 —— 但绝不越过调用方
			   给这个节点的额度。 */
			String raw = TProxyService.apiActionRaw("testDelay", params, waitMs);
			r.raw = raw;
			if (raw == null) {
				TProxyService.log("delay: 动作无返回 name=" + proxyName + " url=" + url
					+ " shape#" + shape);
				return r;
			}
			int code;
			Object data;
			try {
				JSONObject o = new JSONObject(raw);
				code = o.optInt("code", -1);
				data = o.opt("data");
			} catch (Throwable e) {
				TProxyService.log("delay: 动作返回无法解析 " + truncate(raw));
				return r;
			}
			if (code != 0) {
				String msg = String.valueOf(data);
				/* 这些是内核在**拒绝参数**（容器不对 / 字段类型不对 / 字段名不对），
				   **不是**对节点的判定 —— 所以调用方应该换下一种形状，而不是把这个节点
				   写死成坏节点。内核自己说得很直白：容器不对是 "invalid data type"，
				   字段类型不对是 "json: cannot unmarshal ..."。 */
				r.rejected = msg.contains("invalid data type")
					|| msg.contains("cannot unmarshal")
					|| msg.contains("unmarshal")
					|| msg.startsWith("json:")
					|| msg.contains("invalid params");
				TProxyService.log("delay: 动作失败 code=" + code + " data=" + truncate(msg)
					+ " name=" + proxyName + " url=" + url + " shape#" + shape
					+ (r.rejected ? "（参数被拒，换形状）" : ""));
				return r;
			}
			String s = (data == null || data == JSONObject.NULL) ? "" : data.toString().trim();
			int ms;
			try {
				ms = Integer.parseInt(s);
			} catch (Throwable e) {
				/* 有回包，但不是数字：这是**故障**（回包异常），所以节点保持"未测速"，
				   而不是被判成坏节点。 */
				TProxyService.log("delay: 动作返回非数字 \"" + truncate(s) + "\" name="
					+ proxyName + " url=" + url);
				return r;
			}
			r.answered = true;
			if (ms <= 0) {
				/* 这是**内核自己**的判定：探测无法完成。 */
				TProxyService.log("delay: 内核判定失败(" + ms + ") name=" + proxyName
					+ " url=" + url);
				return r;
			}
			/* 直接用 mihomo 自己给的数字：不加偏移、不做估算。 */
			r.measured = ms;
			if (verbose)
			  TProxyService.log("delay: 可用 name=" + proxyName + " " + ms + "ms shape#"
				+ shape + " @" + url);
			return r;
		} catch (Throwable e) {
			TProxyService.log("delay: testDelay 异常 " + e + " name=" + proxyName);
			return r;
		}
	}

	/* 配置自己 `proxies:` 段里的条目数，也就是生成的配置**真正声明**了多少个节点。
	   数到下一个顶层 key 就停，所以规则 / 代理组永远不会被算进来。 */
	static int countProxies(String cfg) {
		if (cfg == null)
		  return 0;
		int n = 0;
		boolean inProxies = false;
		for (String line : cfg.split("\n", -1)) {
			if (line.startsWith("proxies:")) {
				inProxies = true;
				continue;
			}
			if (!inProxies)
			  continue;
			/* 没有缩进且非空的行 = 下一个顶层段开始了。 */
			if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t"))
			  break;
			if (line.trim().startsWith("- "))
			  n++;
		}
		return n;
	}

	private static String truncate(String s) {
		if (s == null)
		  return "null";
		s = s.replace('\n', ' ');
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
