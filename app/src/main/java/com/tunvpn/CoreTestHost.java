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
				cfg = MihomoConfig.buildTestCoreConfig(prefs, prefs.getTestAllNodes(), home);
			} catch (Throwable e) {
				Log.w(TAG, "build test config failed: " + e);
				return false;
			}
			if (cfg == null || cfg.equals(lastConfig))
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
			synchronized (lock) {
				long end = System.currentTimeMillis() + 8000;
				while (!done[0] && System.currentTimeMillis() < end)
				  lock.wait(Math.max(1, end - System.currentTimeMillis()));
			}
			if (err[0] != null) {
				Log.w(TAG, "quickSetup: " + err[0]);
				TProxyService.log("测试内核 quickSetup 失败：" + err[0]);
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
		try {
			JSONObject d = new JSONObject();
			d.put("proxy-name", proxyName);
			d.put("test-url", url);
			d.put("timeout", timeoutMs);
			/* The probe may run for `timeoutMs` inside the core, so wait
			   beyond that - the default 6s bridge wait would abort a 12s
			   rescue probe and throw the answer away. */
			String r = TProxyService.apiAction("testDelay", d.toString(), timeoutMs + 5000L);
			if (r == null)
			  return null;
			int ms;
			try {
				ms = Integer.parseInt(r.trim());
			} catch (Throwable e) {
				/* The core answered, but not with a number. That is a
				   malfunction (bad reply / node missing from the config), so
				   the node stays untested instead of being called broken. */
				TProxyService.log("delay: 内核动作返回非数字 \"" + truncate(r)
					+ "\" name=" + proxyName + " url=" + url);
				return null;
			}
			if (ms <= 0) {
				/* The core's own verdict: the probe could not complete. */
				TProxyService.log("delay: 内核判定失败(" + ms + ") name=" + proxyName
					+ " url=" + url);
				return Long.valueOf(-2L);
			}
			return Long.valueOf(ms);
		} catch (Throwable e) {
			TProxyService.log("delay: testDelay 异常 " + e + " name=" + proxyName);
			return null;
		}
	}

	private static String truncate(String s) {
		if (s == null)
		  return "null";
		s = s.replace('\n', ' ');
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
