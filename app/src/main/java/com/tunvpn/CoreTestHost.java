/*
 ============================================================================
 Name        : CoreTestHost.java
 Description : Runs a SECOND, TUN-less mihomo core inside the APP process so
               the latency test can use the core's REAL forwarding delay even
               when the VPN tunnel is not connected. A core never needs a TUN
               to test a proxy - it just dials the node server itself - which
               is exactly how FlClash measures latency. The test core is
               configured with no tun, no mixed-port and no external-controller,
               so it can never clash with the tunnel core in the :native process.
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

	/* Guarded by the class monitor (ensureReady is synchronized). */
	private static boolean loaded = false;
	/* True once a config was actually applied to the test core. isReady() must
	   only report ready then: "library loaded but quickSetup failed" would
	   otherwise send every delay probe at a core that has no proxies at all. */
	private static boolean applied = false;
	private static String lastConfig = "";

	private CoreTestHost() { }

	/* Forget the applied config so the next ensureReady() rebuilds and
	   re-applies it (called when the relevant switches change). */
	static synchronized void reset() {
		lastConfig = "";
		applied = false;
	}

	static synchronized boolean isReady() {
		return applied;
	}

	/* Load the test core and apply the current node set. Returns true when the
	   core is ready to serve testDelay. Cheap to call repeatedly: the config is
	   regenerated, but only re-applied when it actually changed. */
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
			/* Apply - and if the core rejects a SPECIFIC proxy, drop that node
			   and try again. mihomo validates the whole file at once ("proxy
			   645: invalid REALITY short ID"), so one broken node in a
			   600-node subscription otherwise made every single node untestable,
			   while a small country pool happened to work. */
			for (int attempt = 0; attempt <= MAX_DROP_RETRIES; attempt++) {
				String cfg;
				try {
					cfg = MihomoConfig.buildTestCoreConfig(prefs, home, badProxies);
				} catch (Throwable e) {
					Log.w(TAG, "build test config failed: " + e);
					TProxyService.log("测试内核配置生成失败：" + e);
					return false;
				}
				/* Direct evidence for "is the generated config carrying the
				   nodes?": the size of its own `proxies:` section. */
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
					/* Still parsing: NOT ready. Probing now would aim every node
					   at a half-loaded core. */
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

	/* True when the core has already refused this node (badProxies): it can
	   never be tested, so callers should report it untested instead of burning
	   probes on it. */
	static boolean isRejected(String name) {
		return name != null && badProxies.contains(name);
	}

	/* Outcome of one quickSetup attempt. */
	private static final class Apply {
		String err;        /* the core's error text, or null on success */
		boolean timeout;   /* no callback within the scaled deadline */
	}

	/* One quickSetup attempt against the file buildTestCoreConfig just wrote. */
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
		/* The wait scales with the config: parsing a few hundred nodes takes
		   seconds, and a FIXED 8s window once declared a big pool "ready" while
		   the core was still loading - every probe then failed and the whole
		   selection came back 不可用. A timeout is a FAILURE, never a silent
		   success. */
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
					/* Keep the flag and stop waiting: a caller that interrupts us
					   wants out, and the config is then simply not ready. */
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

	/* Parameter shapes the "testDelay" action may expect. The library documents
	   none of them, so these are tried in order and the accepted one is
	   remembered (the probing then costs one call, not one per node).
	   What the core has told us so far, from its own error text:
	     * `data` must be the params JSON as a STRING, not an inline object -
	       otherwise: {"data":"invalid data type","code":-1}
	     * inside it, `timeout` must be a NUMBER: sending a string gives
	       "json: cannot unmarshal string into Go struct field
	       TestDelayParams.timeout of type int64".
	   Hence the numeric-timeout shape comes first. */
	private static final String[][] DELAY_SHAPES = {
		{ "proxy-name", "test-url", "n" },   /* the shape the core asked for */
		{ "proxy-name", "test-url", "s" },
		{ "proxy-name", "url", "n" },        /* shorter url key */
		{ "name", "url", "n" },              /* shortest keys */
		{ "name", "url", "s" },
	};
	private static volatile int delayShape = -1;
	/* Serialises that one-shot decision (see testDelay). */
	private static final Object SHAPE_LOCK = new Object();
	/* Per-probe success lines help when testing ONE node and are pure I/O noise
	   for a thousand-node pass (each line opens and closes the log file). */
	private static volatile boolean verbose = true;

	static void setVerbose(boolean v) {
		verbose = v;
	}

	/* Node names the core refused (mihomo validates the whole file at once, so
	   one bad proxy used to make every node untestable). Kept for the life of
	   the process: a node the core cannot parse stays unusable. */
	private static final java.util.Set<String> badProxies =
		java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	/* How many rejected nodes to peel off in one ensureReady() call before
	   giving up. Each retry regenerates + re-applies the config, so this is
	   deliberately small - the remaining bad nodes are dropped on later calls. */
	private static final int MAX_DROP_RETRIES = 8;

	/* Body for the testDelay action in shape #shape.index. Shared with
	   ClashApiServer, which answers the REST /delay route the same way. */
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

	/* Body using the remembered shape (shape 0 until one is accepted). */
	static String delayData(String proxyName, String url, int timeoutMs) {
		return delayData(delayShape < 0 ? 0 : delayShape, proxyName, url, timeoutMs);
	}

	/* One probe attempt. */
	private static final class Probe {
		long measured = -1;   /* >=0 when the core measured a latency */
		boolean answered;     /* the core produced a delay verdict */
		boolean rejected;     /* the core refused THESE PARAMS (wrong shape) */
		String raw;
	}

	/* The core's ISOLATED per-proxy probe (the "testDelay" action - what
	   FlClash uses). Returns the latency (>=0) on success, -2 when the core
	   itself reports that the node could not complete the probe, or null when
	   there is no verdict at all (bridge unavailable, no answer, or a reply we
	   cannot interpret) - which the caller reports as "未测速", NOT as
	   unavailable. Getting that distinction wrong is what made working nodes
	   flip to 不可用. */
	static Long testDelay(String proxyName, String url, int timeoutMs) {
		return testDelay(proxyName, url, timeoutMs, 0);
	}

	/* Same, with an explicit bound on how long we may wait for the reply. The
	   caller's per-node time limit (设置 → 每节点测速上限) can be TIGHTER than
	   the probe's own timeout; waiting past it would make that limit a lie (the
	   core would still hold the single bridge slot after the caller gave up),
	   so the bound is threaded all the way down to the bridge wait. 0 = the
	   default (probe timeout + 5s). */
	static Long testDelay(String proxyName, String url, int timeoutMs, long waitBound) {
		final long waitMs = waitBound > 0
			? Math.max(500L, Math.min(timeoutMs + 5000L, waitBound))
			: timeoutMs + 5000L;
		/* No core in THIS process means the tunnel core (in :native) must be
		   reached over HTTP instead, so answer "no verdict" without a bridge
		   call - otherwise every probe would log a skipped action. */
		if (!isReady())
		  return null;
		/* The accepted shape is a shared one-shot latch. Settle it on ONE thread
		   before the pool fans out: 16 concurrent probes racing it could latch
		   shape #0 while it was still being rejected (or vice versa) and then
		   fail every node at once - the "单个测速能用、测速全部全废" symptom. */
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
			/* The remembered shape stopped being accepted (the core reloaded
			   with another build?): forget it so the next call re-detects,
			   instead of failing every remaining node this pass. */
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

	/* Try each shape until the core stops complaining about the parameters.
	   Runs on one thread only.
	   "Accepted" means the core did not refuse the payload - NOT that it
	   produced a delay: waiting for a real answer here costs one full probe per
	   shape (5 x (timeout+5s) = over a minute with a long timeout) and that
	   stall happens BEFORE any node is tested, so the user just sees a frozen
	   0% progress bar. */
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

	/* One attempt with shape #shape. */
	private static Probe probe(int shape, String proxyName, String url, int timeoutMs,
			long waitMs) {
		Probe r = new Probe();
		try {
			String params = delayData(shape, proxyName, url, timeoutMs);
			/* The probe may run for `timeoutMs` inside the core, so wait beyond
			   that - but never past the caller's per-node budget. */
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
				/* These are the core REFUSING THE PARAMETERS (wrong container,
				   wrong field type, wrong field name), NOT a verdict about the
				   node - so the caller tries the next shape instead of writing
				   the node off. The core spells it out: "invalid data type" for
				   a bad container and "json: cannot unmarshal ..." for a field
				   of the wrong type. */
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
				/* Answered, but not with a number: a malfunction (bad reply),
				   so the node stays untested rather than being called broken. */
				TProxyService.log("delay: 动作返回非数字 \"" + truncate(s) + "\" name="
					+ proxyName + " url=" + url);
				return r;
			}
			r.answered = true;
			if (ms <= 0) {
				/* The core's own verdict: the probe could not complete. */
				TProxyService.log("delay: 内核判定失败(" + ms + ") name=" + proxyName
					+ " url=" + url);
				return r;
			}
			/* mihomo's own number, used as-is: no offset, no estimate. */
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

	/* Number of list entries inside the config's own `proxies:` section, i.e.
	   how many nodes the generated profile actually declares. Counting stops at
	   the next top-level key so rules / groups are never included. */
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
			/* A non-indented, non-empty line is the next top-level section. */
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
