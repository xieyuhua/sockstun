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
			String cfg;
			try {
				cfg = MihomoConfig.buildTestCoreConfig(prefs, home);
			} catch (Throwable e) {
				Log.w(TAG, "build test config failed: " + e);
				return false;
			}
			/* Direct evidence for "is the generated config carrying the nodes?":
			   the number of entries in its own `proxies:` section. */
			if (cfg == null)
			  return true;
			boolean same = cfg.equals(lastConfig);
			int nodeCount = countProxies(cfg);
			TProxyService.log("测试内核配置：" + nodeCount + " 个代理条目 · "
				+ cfg.length() + " 字节 · 路径 " + new File(home, "config.yaml").getAbsolutePath()
				+ (same ? "（与上次相同，跳过重新应用）" : ""));
			if (same)
			  return true;
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
			   seconds, and a FIXED 8s window meant a big pool was declared ready
			   while the core was still loading - every probe then failed and the
			   whole selection came back 不可用 (single-country worked because its
			   config is tiny and loads in time). A timeout is a FAILURE, never a
			   silent success. */
			synchronized (lock) {
				long waitMs = Math.min(60000L, 8000L + nodeCount * 30L);
				long end = System.currentTimeMillis() + waitMs;
				while (!done[0] && System.currentTimeMillis() < end)
				  lock.wait(Math.max(1, end - System.currentTimeMillis()));
				if (!done[0]) {
					TProxyService.log("测试内核配置加载超时（等待 " + waitMs + "ms · "
						+ nodeCount + " 个节点）→ 本次不测速，等它加载完再试");
					applied = false;
					lastConfig = "";
					return false;
				}
			}
			if (err[0] != null) {
				Log.w(TAG, "quickSetup: " + err[0]);
				TProxyService.log("测试内核 quickSetup 失败：" + err[0]);
				/* This config was NOT applied, so the core is either empty or
				   still running the PREVIOUS pool. Either way it must not be
				   treated as ready: probing it would aim every node at a stale
				   node set and report nonsense. */
				applied = false;
				lastConfig = "";
				return false;
			}
			lastConfig = cfg;
			applied = true;
			TProxyService.log("测试内核就绪（无 TUN 实例，节点用于隔离测速）");
			return true;
		} catch (Throwable e) {
			Log.w(TAG, "ensureReady failed: " + e);
			TProxyService.log("测试内核加载失败：" + e);
			return false;
		}
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
					int found = detectShape(proxyName, url, timeoutMs);
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
		Probe p = probe(shape, proxyName, url, timeoutMs);
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
	private static int detectShape(String proxyName, String url, int timeoutMs) {
		for (int i = 0; i < DELAY_SHAPES.length; i++) {
			Probe p = probe(i, proxyName, url, timeoutMs);
			if (!p.rejected)
			  return i;
			TProxyService.log("delay: 内核不接受参数形状#" + i + "（" + DELAY_SHAPES[i][0]
				+ "/" + DELAY_SHAPES[i][1] + "/timeout=" + DELAY_SHAPES[i][2]
				+ "）→ 试下一种 · 内核回 " + truncate(p.raw));
		}
		return -1;
	}

	/* One attempt with shape #shape. */
	private static Probe probe(int shape, String proxyName, String url, int timeoutMs) {
		Probe r = new Probe();
		try {
			String params = delayData(shape, proxyName, url, timeoutMs);
			/* The probe may run for `timeoutMs` inside the core, so wait beyond
			   that - the default 6s bridge wait would abort a 12s rescue probe
			   and throw the answer away. */
			String raw = TProxyService.apiActionRaw("testDelay", params, timeoutMs + 5000L);
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
