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
	private static String lastConfig = "";

	private CoreTestHost() { }

	/* Forget the applied config so the next ensureReady() rebuilds and
	   re-applies it (called when the relevant switches change). */
	static synchronized void reset() {
		lastConfig = "";
	}

	static synchronized boolean isReady() {
		return loaded;
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
				return false;
			}
			lastConfig = cfg;
			return true;
		} catch (Throwable e) {
			Log.w(TAG, "ensureReady failed: " + e);
			return false;
		}
	}

	/* The core's ISOLATED per-proxy probe (the "testDelay" action - what
	   FlClash uses). Returns the latency (>=0) on success, -2 when the node
	   cannot complete the probe, or null when the bridge is unavailable. */
	static Long testDelay(String proxyName, String url, int timeoutMs) {
		try {
			JSONObject d = new JSONObject();
			d.put("proxy-name", proxyName);
			d.put("test-url", url);
			d.put("timeout", timeoutMs);
			String r = TProxyService.apiAction("testDelay", d.toString());
			if (r == null)
			  return null;
			int ms = -1;
			try { ms = Integer.parseInt(r.trim()); } catch (Throwable ignore) { }
			return ms > 0 ? Long.valueOf(ms) : Long.valueOf(-2L);
		} catch (Throwable e) {
			return null;
		}
	}
}
