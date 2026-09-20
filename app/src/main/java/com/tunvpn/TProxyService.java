/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service (mihomo / clash.meta core)
 ============================================================================
 */

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;

import androidx.core.app.NotificationCompat;

import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.Method;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.widget.Toast;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.TunInterface;
import io.github.oviron.libmihomo.InvokeInterface;

public class TProxyService extends VpnService {
	public static final String ACTION_CONNECT = "tunvpn.CONNECT";
	public static final String ACTION_DISCONNECT = "tunvpn.DISCONNECT";
	public static final String ACTION_RECONNECT = "tunvpn.RECONNECT";
	/* Switch the selected node while the tunnel keeps running. */
	public static final String ACTION_SELECT = "tunvpn.SELECT";

	/* Traffic statistics */
	private static final int NOTIFY_ID = 1;
	private static final String NOTIFY_CHANNEL = "socks5";
	private static final long STATS_INTERVAL = 1000;

	private Handler statsHandler = null;
	private Runnable statsTask = null;
	/* The polling loop must run off the main thread: accumulateProxy() does a
	   synchronous httpGet() to the core's control API, and the main thread
	   throws NetworkOnMainThreadException on any network call. That exception
	   was being swallowed by httpGet's catch, leaving body null and the proxied
	   traffic counters stuck at 0 forever - exactly the "stats always 0" report. */
	private HandlerThread statsThread = null;
	private Preferences statsPrefs = null;
	/* Loopback HTTP control API hosted by the app (libmihomo never binds its
	   own external-controller). Lives for the lifetime of the tunnel. */
	private ClashApiServer clashApiServer = null;
	private Preferences prefs = null;
	private long lastTx, lastRx, lastTime;
	private long sessionTx, sessionRx;
	private long sessionBaseTx, sessionBaseRx; /* core cumulative at session start */
	private long baseTx, baseRx, totalTx, totalRx;
	private long txRate, rxRate;
	private boolean trafficPrimed = false; /* first valid sample primes baselines; avoids a 9G/s spike */
	private String lastNotifyText = null;
	private int trafficSamples = 0;

	/* --- proxied-traffic accounting ---------------------------------------
	   The core's own counters cover everything it handles, direct traffic
	   included. To report what actually went through a node we poll its
	   connection list and accumulate the per-connection counters of the ones
	   whose chain is not DIRECT. A connection that opens and finishes between
	   two polls is missed, so this is a close approximation, not an exact
	   figure. */
	private final Map<String, long[]> connSeen = new HashMap<String, long[]>();
	/* Recent-requests recorder: when a connection disappears from /connections
	   we log it here so the UI can show "what went where" historically. connInfo
	   tracks each live connection's metadata + last counters; recentRequests is
	   the bounded history serialized to Preferences. */
	private final Map<String, ConnInfo> connInfo = new HashMap<String, ConnInfo>();
	private final List<RecentRequest> recentRequests = new ArrayList<RecentRequest>();
	private long lastRecentFlush = 0;
	private static final int MAX_RECENT_REQUESTS = 200;

	/* One live connection we are tracking for history. */
	private static class ConnInfo {
		long startMs;
		String target;
		String rule;
		String chain;
		String process;
		long up;
		long down;
	}
	/* One closed connection, ready to be shown. */
	private static class RecentRequest {
		long startMs;
		long endMs;
		String target;
		String rule;
		String chain;
		String process;
		long up;
		long down;
	}
	/* Consecutive failed /connections polls, so the "stats are stuck at 0"
	   case is reported instead of being invisible. */
	private int connFailStreak = 0;
	/* Last exception seen while probing the control API, so a failed probe says
	   whether nothing is listening (refused) or it is up but not answering. */
	private volatile String lastControllerError = null;
	/* Set true once isControllerUp() actually reaches the clash-api. Until then
	   the /connections poll is skipped silently so the warmup window (core
	   brings TUN up before binding the API) does not spam "未就绪" - the
	   verifyController thread still reports a genuine "never ready" after 90s. */
	private volatile boolean controllerReady = false;
	private volatile String controllerHost = "127.0.0.1";
	private volatile String proxyTestStatus = "";
	private static final String PROXY_TEST_URL = "http://www.gstatic.com/generate_204";
	/* 延迟测试候选地址：节点只要能通任意一个，就给出干净的“可用”。Clash 自己的
	   /delay 语义是“代理回了任何 HTTP 响应即节点管线通”，502 只说明该目标被节点
	   出口拦截，不算代理死。 */
	private static final String[] PROXY_TEST_URLS = {
		PROXY_TEST_URL,
		"http://www.google.com/generate_204",
		"http://www.msftconnecttest.com/connecttest.txt",
	};
	private volatile String lastTestUrl = "";
	private volatile String lastTestError = "";
	private long proxyBaseTx, proxyBaseRx;
	private long proxySessionTx, proxySessionRx;
	private long lastProxyTx, lastProxyRx;
	private long proxyRateTx, proxyRateRx;
	private long lastProxyTime;
	private boolean proxyPrimed = false; /* first valid proxied sample primes baselines */

	/* Append a line to the log file directly (bypassing the fd-1/2
	   redirection), so startup diagnostics are always captured even if the
	   native library fails to load or redirect. The actual file handling lives
	   in TestLog, which classes without a Service handle also write through. */
	private void appendLog(String s) {
		TestLog.append(s);
	}

	/* Log from a static context (e.g. ClashApiServer, which has no Service
	   reference) into the same file. Safe when nothing is bound yet: lines are
	   still mirrored to logcat. */
	static void log(String s) {
		TestLog.append(s);
	}

	/* Redirect process stdout/stderr (fd 1/2) to a file so we can capture
	   the native tunnel's printf/fprintf logs on Android (where they would
	   otherwise be discarded).

	   The signatures of android.system.Os differ across SDK/device builds
	   (open() may return int or FileDescriptor; dup2()/close() may take int
	   or FileDescriptor), so we resolve the real methods via reflection and
	   adapt the argument types at runtime. This compiles and runs regardless
	   of which variant is present. */
	private void redirectStdioToLog(File log) {
		try {
			int flags = OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_APPEND;
			Method open = Os.class.getMethod("open", String.class, int.class, int.class);
			Object fdObj = open.invoke(null, log.getAbsolutePath(), flags, 0644);

			Method dup2 = null;
			for (Method m : Os.class.getMethods()) {
				if (m.getName().equals("dup2") && m.getParameterTypes().length == 2) {
					dup2 = m;
					break;
				}
			}
			Class<?> p0 = dup2.getParameterTypes()[0];
			if (p0 == int.class) {
				int logFd = (Integer) fdObj;
				dup2.invoke(null, logFd, 1);
				dup2.invoke(null, logFd, 2);
			} else {
				FileDescriptor logFd = (FileDescriptor) fdObj;
				dup2.invoke(null, logFd, FileDescriptor.out);
				dup2.invoke(null, logFd, FileDescriptor.err);
			}

			Method close = null;
			for (Method m : Os.class.getMethods()) {
				if (m.getName().equals("close") && m.getParameterTypes().length == 1) {
					close = m;
					break;
				}
			}
			close.invoke(null, fdObj);
		} catch (Exception e) {
		}
	}

	/* Profile handed to the core, kept so that a rejected proxy can be dropped
	   and the profile re-applied (see retryWithoutRejectedProxy). */
	private volatile String coreInitParams = null;
	private volatile String coreSetupParams = null;
	private volatile File coreConfigFile = null;
	private volatile boolean coreCustomConfig = false;
	/* Node names the core has refused. Excluded from every later rebuild, and
	   remembered for the life of the process. */
	private final java.util.Set<String> rejectedNodes =
		java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	private volatile int configRetry = 0;
	/* True while a retry is rebuilding the profile, so the synchronous
	   "config is broken" check does not race it. */
	private volatile boolean configRetryRunning = false;
	private static final int MAX_CONFIG_RETRIES = 8;

	private ParcelFileDescriptor tunFd = null;
	/* Non-empty when the core reported a problem while loading the generated
	   config. The control API never binds in that case, so the error is kept
	   around to be logged (and surfaced) instead of the failure being mute. */
	private volatile String quickSetupError = null;
	/* Set once a startup abort has been handled, so the asynchronous
	   quickSetup callback and the synchronous check cannot both fire it. */
	private volatile boolean startupAborted = false;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		sInstance = this;
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			stopService();
			return START_NOT_STICKY;
		}
		/* A live change to a setting read only at establish() time (per-app
		   scope, global mode): stop the old fd without destroying the service
		   and rebuild, as one action - avoids the DISCONNECT-then-CONNECT race
		   where the new tunnel gets torn down by the pending stopSelf(). */
		if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
			rebuildTunnel();
			return START_STICKY;
		}
		/* mihomo can change a selector while running, so a newly picked node
		   takes effect immediately instead of waiting for a restart. */
		if (intent != null && ACTION_SELECT.equals(intent.getAction())) {
			if (tunFd != null)
			  applySelectedNode(new Preferences(this));
			return START_STICKY;
		}
		startService();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		/* The system can tear the service down without a disconnect command,
		   so make sure the stats poller does not outlive it. stopStats() is
		   idempotent, so this is harmless after a normal stop. */
		stopStats();
		stopEmbeddedApi();
		sInstance = null;
		super.onDestroy();
	}

	@Override
	public void onRevoke() {
		sInstance = null;
		stopService();
		super.onRevoke();
	}

	public void startService() {
		if (tunFd != null)
		  return;

		prefs = new Preferences(this);

		/* Logging. Bind the shared log file first, then start a fresh file for
		   this session; every later append (including the ones from
		   ClashApiServer and the Activities) lands in the same file the
		   日志 page reads. */
		TestLog.init(this);
		File tproxy_log = new File(getCacheDir(), "tproxy.log");
		if (tproxy_log.exists())
		  tproxy_log.delete();
		appendLog("=== tunVPN start (pid " + android.os.Process.myPid() +
			" logging=" + prefs.getLogEnabled() + ") ===");
		if (prefs.getLogEnabled()) {
			redirectStdioToLog(tproxy_log);
		}

		/* Load the embedded mihomo core (libclash.so + libmihomo-jni.so). */
		try {
			Clash.INSTANCE.load(getApplicationInfo().nativeLibraryDir);
			appendLog("mihomo core loaded OK (bridge ABI " + Clash.INSTANCE.bridgeABI() + ")");
		} catch (Throwable e) {
			failStartup("内核加载失败：" + e);
			return;
		}

		/* Pick a free loopback port for the clash-api BEFORE writing config: a
		   port already held by another process (stale tunnel, another proxy, an
		   ADB forward, an emulator) makes mihomo silently fail to bind the
		   control API, which reads as "connected but /connections refuses and
		   every counter stays 0". */
		int apiPort = MihomoConfig.pickApiPort();
		appendLog("config: clash-api port = " + apiPort);

		/* Config: either the file the user edited by hand (custom mode) or a
		   fresh one generated from the current settings. */
		File configFile = new File(getFilesDir(), "config.yaml");
		try {
			if (prefs.getCustomConfig() && configFile.exists()) {
				appendLog("config: 使用自定义 config.yaml（已关闭自动生成）");
				ensureControlApi(configFile, prefs);
			} else {
				/* Nodes the core already refused must not come back on a
				   reconnect, or the profile would fail again every time. */
				configFile = MihomoConfig.build(this, prefs, rejectedNodes);
				appendLog("config: " + configFile.getAbsolutePath());
				appendLog("routing: " + MihomoConfig.describe(prefs)
					+ (rejectedNodes.isEmpty() ? ""
						: ("（已排除内核拒绝的节点 " + rejectedNodes.size() + " 个）")));
			}
			/* One line, not the whole file: these keys decide whether the node
			   picker and the traffic counters can reach the core at all. */
			appendLog("config: " + configKeySummary(configFile));
		} catch (Throwable e) {
			failStartup("生成配置失败：" + e.getMessage());
			return;
		}

		/* VPN interface. mihomo owns all routing, so we send every packet into
		   the tunnel and let its rule engine decide proxy vs direct. */
		boolean ipv4 = prefs.getIpv4();
		boolean ipv6 = prefs.getIpv6();
		/* Keep the interface address outside the fake-ip pool (198.18.0.0/16),
		   otherwise a domain can be handed an address that is already the
		   tunnel's own. */
		String tunAddr = "172.19.0.1";
		String tunAddr6 = "fc00::1";
		int tunPrefix = 30;

		VpnService.Builder builder = new VpnService.Builder();
		builder.setBlocking(false);
		builder.setMtu(prefs.getTunnelMtu());
		if (ipv4) {
			builder.addAddress(tunAddr, tunPrefix);
			builder.addDnsServer("223.5.5.5");
			builder.addRoute("0.0.0.0", 0);
		}
		if (ipv6) {
			builder.addAddress(tunAddr6, 64);
			builder.addDnsServer("2400:3200::1");
			builder.addRoute("::", 0);
		}

		/* The controller app must never have its own traffic routed into the
		   tunnel it creates. It reaches mihomo's loopback listeners - the
		   external-controller API on 127.0.0.1:9090 (node selection, the
		   home-screen connection counters) and the mixed-port used by the
		   home-screen "is it proxied?" probe - and if those sockets get
		   captured by the VPN they never reach the local listeners. The
		   symptom is exactly what was reported: the selected apps proxy fine
		   (their traffic is the tunnel's job) yet the UI shows no traffic stats
		   and reports "代理不通". Always exclude ourselves, in both global and
		   per-app scope. */
		/* VpnService forbids mixing addAllowedApplication and
		   addDisallowedApplication on one builder, so the two scopes use
		   different calls:
		   - global: disallow only ourselves, every other app goes through;
		   - per-app: allow exactly the selected apps. We are simply NOT in
		     that list, so our own traffic (external-controller, mixed-port)
		     naturally bypasses the tunnel - no explicit disallow is needed,
		     and adding one would throw IllegalArgumentException and drop the
		     whole app scope. */
		if (prefs.getGlobal()) {
			/* Default: all other apps through the tunnel; keep our own
			   sockets out of it. */
			try {
				builder.addDisallowedApplication(getApplicationContext().getPackageName());
			} catch (NameNotFoundException e) {
			}
		} else {
			for (String appName : prefs.getApps()) {
				try {
					builder.addAllowedApplication(appName);
				} catch (NameNotFoundException e) {
				}
			}
		}
		builder.setSession("tunVPN/mihomo");
		/* Worth logging: if ipv4/ipv6 are both off there is no route into the
		   tunnel at all, and if the scope is "N app(s)" only those apps are
		   captured - both look exactly like "connected but not proxied". */
		appendLog("vpn: ipv4=" + ipv4 + " ipv6=" + ipv6 + " mtu=" + prefs.getTunnelMtu()
			+ " scope=" + (prefs.getGlobal() ? "all apps" : prefs.getApps().size() + " app(s)")
			+ " excludeSelf=" + prefs.getGlobal());
		/* Per-app mode with no apps selected captures nothing: the tunnel comes
		   up "connected" but proxies zero traffic. Spell it out in the log. */
		if (!prefs.getGlobal() && prefs.getApps().isEmpty())
		  appendLog("WARN: 部分应用模式未选择任何应用，将没有任何流量进入隧道（等于不代理）。"
			+ "请到「规则 → 应用」勾选程序，或开启「全局模式」。");
		tunFd = builder.establish();
		if (tunFd == null) {
			failStartup("建立 VPN 接口失败（未授权或被其他 VPN 占用）");
			return;
		}

		/* Initialise mihomo. It loads <homeDir>/config.yaml, and the picked
		   node can already be applied here via selected-map.
		   InitParams uses "home-dir"; keep "homeDir" too so an older core
		   still understands it (unknown fields are ignored). */
		String homeDir = getFilesDir().getAbsolutePath();
		/* Where the clash-api comes from: on this SDK build (libmihomo-android
		   v0.3.3) the InitParams struct only carries HomeDir/Version - any
		   external-controller/secret we put in initParams is IGNORED, so the
		   API MUST be supplied by the profile (config.yaml), which we already
		   write at MihomoConfig.build(). Empirically the mixed-port (also from
		   the profile) comes up, but some builds skip the external-controller
		   listener on quickSetup; when that happens kickClashApi() re-applies
		   external-controller via the UpdateConfig action to start it. Keep
		   home-dir/homeDir (and the ignored external-controller/secret) for
		   core compat with builds that do read them. */
		String secret = prefs.getSecret();
		String initParams = "{\"home-dir\":\"" + homeDir + "\"," +
			"\"homeDir\":\"" + homeDir + "\"," +
			"\"external-controller\":\"127.0.0.1:" + apiPort + "\"," +
			"\"secret\":\"" + secret + "\"}";
		/* selected-map 故意留空：它会把选中的节点名经 JNI 桥传进内核，而桥把
		   Java 字符串按 "modified UTF-8" 交给内核，会破坏补充平面字符（节点名里
		   的国旗 emoji），内核随即报 "proxy ... not found"，甚至整份配置加载失败
		   （external-controller 因此没监听，REST 全部连不上）。
		   节点改在下面用 REST API 选择，全程标准 UTF-8，名字能精确匹配。 */
		String setupParams = "{\"selected-map\":{}," +
			"\"profile\":\"" + configFile.getAbsolutePath() + "\"}";
		/* Forward mihomo's log/event stream into our log file BEFORE quickSetup,
		   so the core's own message about why the control API (external-
		   controller) failed to bind - e.g. "failed to start clash api:
		   listen tcp 127.0.0.1:9090: bind: address already in use" - lands in
		   tproxy.log instead of being silently dropped. The listener used to be
		   attached only AFTER quickSetup, by which point the bind had already
		   happened and the error with it. */
		try {
			Clash.INSTANCE.setEventListener(new InvokeInterface() {
				@Override
				public void onResult(String result) {
					if (result != null && !result.isEmpty())
					  appendLog("mihomo: " + result);
				}
			});
		} catch (Throwable e) {
		}

		/* Kept for the drop-and-retry path (see retryWithoutRejectedProxy). */
		coreInitParams = initParams;
		coreSetupParams = setupParams;
		coreConfigFile = configFile;
		coreCustomConfig = prefs.getCustomConfig();
		configRetry = 0;
		try {
			quickSetupCore();
		} catch (Throwable e) {
			failStartup("启动内核失败：" + e.getMessage());
			return;
		}
		/* quickSetup may run its callback on the calling thread; when it did and
		   the profile is broken, the retry path has already handled it (or
		   scheduled the abort), so startTUN is never reached with a dead config.
		   A retry that is still running on another thread is left to finish. */
		if (quickSetupError != null && looksLikeError(quickSetupError)
				&& !configRetryRunning) {
			startupAborted = true;
			failStartup("内核配置错误：" + configErrorReason(quickSetupError));
			return;
		}

		/* Bring the TUN up on the VPN fd we just established. The TunInterface
		   forwards socket protection to VpnService.protect() so the core's
		   outbound traffic never loops back into the VPN. */
		String address = (ipv4 ? tunAddr + "/" + tunPrefix : "") +
			(ipv6 ? (ipv4 ? "," : "") + tunAddr6 + "/64" : "");
		String dns = "223.5.5.5,119.29.29.29";
		final TunInterface tunInterface = new TunInterface() {
			@Override
			public void protect(int fd) {
				TProxyService.this.protect(fd);
			}
			@Override
			public String resolverProcess(int protocol, String source, String target, int uid) {
				return "";
			}
		};

		/* gvisor first: it keeps the whole stack in userspace and is what the
		   other Android clients ship by default. The system stack leans on
		   tun features that are not dependable on every Android kernel, so
		   fall back to it instead of failing the whole tunnel. */
		String[] stacks = { "gvisor", "system" };
		String started = null;
		Throwable lastError = null;
		for (String candidate : stacks) {
			try {
				Clash.INSTANCE.startTUN(tunFd.getFd(), tunInterface, "tunvpn",
					candidate, address, dns, prefs.getTunnelMtu());
				started = candidate;
				break;
			} catch (Throwable e) {
				lastError = e;
				appendLog("startTUN with stack=" + candidate + " failed: " + e);
			}
		}
		if (started == null) {
			failStartup("启动 TUN 失败：" + lastError);
			return;
		}
		appendLog("Clash.startTUN OK (fd=" + tunFd.getFd() + ", stack=" + started
			+ ", addr=" + address + ", mtu=" + prefs.getTunnelMtu() + ")");

		/* Best-effort: apply the node the user picked in the subscription. */
		applySelectedNode(prefs);
		/* Independent of the selection: confirm the control API really answers,
		   and record why it does not when it does not. Without this the core's
		   config error only exists in its own log stream, which is easy to
		   miss - the tunnel looks connected while 9090 is dead. */
		verifyController();
		/* Proactively nudge the clash-api. quickSetup brings the mixed-port up
		   but on some builds skips the external-controller listener, so the
		   RESTful API (selectors, /connections, node switching) stays dead
		   even though the tunnel proxies fine. kickClashApi() re-applies
		   external-controller via the UpdateConfig action to (re)start it. */
		new Thread(() -> {
			try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
			kickClashApi();
		}).start();
		/* FlClash-style real reachability: actually push a request through the
		   selected node and measure latency, rather than only checking the API
		   answers (which can be green while no traffic flows). */
		testProxyConnectivity(prefs);

		prefs.clearLastError();
		prefs.setEnable(true);
		QSTileService.requestUpdate(this);

		initNotificationChannel(NOTIFY_CHANNEL);
		createNotification();
		startStats(prefs);
		/* Expose the control API on loopback (the core does not bind it). */
		startEmbeddedApi();
	}

	/* Every startup failure funnels through here. Two things matter beyond
	   stopping the service:
	     - Enable must go back to false. MainActivity flips it to true before
	       asking us to start, so without this the UI would happily keep showing
	       "connected" for a tunnel that never came up.
	     - The reason is persisted so the UI can say *why* it failed instead of
	       silently returning to "disconnected". */
	private void failStartup(String reason) {
		appendLog("FATAL: " + reason);
		Preferences p = new Preferences(this);
		p.setLastError(reason);
		p.setEnable(false);
		QSTileService.requestUpdate(this);

		/* A config error is reported asynchronously - i.e. after the VPN fd has
		   been established - so tear that down too, otherwise the system keeps
		   a dead VPN up. */
		if (tunFd != null) {
			try {
				Clash.INSTANCE.stopTun();
			} catch (Throwable e) {
			}
			try {
				tunFd.close();
			} catch (IOException e) {
			}
			tunFd = null;
		}

		Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
		stopForeground(true);
		stopSelf();
	}

	/* Whether the core's quickSetup result describes an error. The bridge returns
	   an empty result on success, but stay defensive so a status string is never
	   mistaken for a fatal config error. */
	private static boolean looksLikeError(String s) {
		if (s == null || s.isEmpty())
		  return false;
		String t = s.toLowerCase();
		return t.contains("error") || t.contains("not found") || t.contains("fail")
			|| t.contains("invalid") || t.contains("yaml") || t.contains("unsupported")
			|| t.contains("cannot") || t.contains("no such") || t.contains("unknown")
			|| t.contains("proxy") || t.contains("group");
	}

	/* Hand the current profile to the core. Factored out because a rejected
	   proxy makes us rewrite the profile and call it again. */
	private void quickSetupCore() {
		final String init = coreInitParams;
		final String setup = coreSetupParams;
		if (init == null || setup == null)
		  return;
		Clash.INSTANCE.quickSetup(init, setup, new InvokeInterface() {
			@Override
			public void onResult(String result) {
				if (result == null || result.isEmpty()) {
					appendLog("mihomo quickSetup OK");
					quickSetupError = null;
					configRetryRunning = false;
					return;
				}
				/* A non-empty result is the core reporting a problem with the
				   config it was handed (unknown proxy / group, bad rule, ...).
				   Nothing about the session can work then - no group, no rules,
				   no control API - so keep the text, and try to recover if the
				   core named a specific proxy. */
				quickSetupError = result;
				appendLog("mihomo quickSetup: " + result);
				if (looksLikeError(result))
				  retryWithoutRejectedProxy(result);
			}
		});
	}

	/* mihomo validates the whole profile in ONE pass: a single invalid proxy
	   ("proxy 645: invalid REALITY short ID") makes it refuse the entire file -
	   the tunnel never comes up, and so does every latency test. Before giving
	   up, drop exactly that node, rewrite the profile and hand it back.
	   Bounded, and only for generated profiles: a hand-edited custom config is
	   never touched. */
	private void retryWithoutRejectedProxy(final String err) {
		if (coreCustomConfig || configRetry >= MAX_CONFIG_RETRIES) {
			abortOnConfigError(err);
			return;
		}
		configRetryRunning = true;
		String bad = MihomoConfig.badProxyNameFromError(err, readTextFile(coreConfigFile));
		if (bad == null || bad.isEmpty() || !rejectedNodes.add(bad)) {
			configRetryRunning = false;
			abortOnConfigError(err);
			return;
		}
		configRetry++;
		appendLog("config: 内核拒绝节点「" + bad + "」（" + err
			+ "）→ 已排除该节点，重新生成配置并重试（第 " + configRetry + " 次）");
		try {
			MihomoConfig.build(this, prefs, rejectedNodes);
		} catch (Throwable e) {
			appendLog("config: 重新生成配置失败：" + e);
			configRetryRunning = false;
			abortOnConfigError(err);
			return;
		}
		quickSetupCore();
	}

	/* Whole-file read for the profile we just wrote (small, UTF-8). */
	private static String readTextFile(File f) {
		if (f == null || !f.exists())
		  return null;
		try {
			java.io.FileInputStream in = new java.io.FileInputStream(f);
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
			  bos.write(buf, 0, n);
			in.close();
			return new String(bos.toByteArray(), "UTF-8");
		} catch (Throwable e) {
			return null;
		}
	}

	/* Stop the service with the core's own message. Runs on the main thread
	   because the quickSetup callback may arrive on a background thread while
	   failStartup shows a Toast, and is guarded so it fires at most once. */
	private void abortOnConfigError(final String reason) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (startupAborted)
				  return;
				startupAborted = true;
				failStartup("内核配置错误：" + configErrorReason(reason));
			}
		});
	}

	/* Turn the core's config error into something actionable. A broken
	   hand-edited config is the common case: it references a proxy/group that
	   does not exist, so stop using it - otherwise every reconnect fails the
	   same way - and let the next connect regenerate a working file. */
	private String configErrorReason(String reason) {
		String msg = reason;
		Preferences p = new Preferences(this);
		if (p.getCustomConfig()) {
			p.setCustomConfig(false);
			msg += "\n自定义配置有误，已关闭「使用自定义配置」，下次连接会按当前设置自动重新生成。";
		}
		return msg;
	}

	/* The node picker, the proxy-traffic counters and the "is it proxied" check
	   all talk to the core over the loopback control API, so the loaded config
	   must expose it. A hand-edited custom config often does not - which is
	   exactly why the tunnel can carry traffic while the home-screen counters
	   stay at zero. Add the missing keys (and say so) rather than silently
	   reporting 0. */
	private void ensureControlApi(File configFile, Preferences prefs) {
		try {
			byte[] buf = new byte[(int) configFile.length()];
			java.io.FileInputStream in = new java.io.FileInputStream(configFile);
			int n = in.read(buf);
			in.close();
			String text = new String(buf, 0, n, "UTF-8");

			String ec = topLevelLine(text, "external-controller:");
			/* The listener must be loopback: on this build mihomo silently
			   fails to bind 0.0.0.0 ("9090 never listens" - the browser cannot
			   reach it either), while 127.0.0.1 binds fine. Align any
			   non-loopback / wrong-port line to 127.0.0.1:<port>. */
			boolean needEc = (ec == null)
				|| !ec.contains("127.0.0.1:" + MihomoConfig.API_PORT);
			boolean needMp = !hasTopLevelKey(text, "mixed-port:")
				&& !hasTopLevelKey(text, "port:");
			/* Adopt a hand-set secret from the custom config so the app's control
			   requests authenticate against it; otherwise inject the app's own
			   generated token. Either way the config and the client agree on the
			   same bearer token. */
			String existingSecret = topLevelLine(text, "secret:");
			String secretVal = null;
			if (existingSecret != null) {
				String v = existingSecret.trim();
				/* Strip the "secret:" key prefix first, then any surrounding
				   quotes, so "secret: \"\"" yields null (empty) instead of the
				   corrupted value "secret: \"", which would otherwise persist
				   into Preferences and break every later auth check. */
				if (v.startsWith("secret:")) v = v.substring("secret:".length());
				v = v.trim();
				if (v.startsWith("\"")) v = v.substring(1);
				if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
				v = v.trim();
				if (!v.isEmpty()) secretVal = v;
			}
			boolean needSecret = (secretVal == null);
			if (secretVal != null)
			  prefs.setSecret(secretVal);
			if (!needEc && !needMp && !needSecret)
				return;

			/* Rebuild the file once: rewrite the external-controller line to the
			   chosen loopback port (or append it), align the secret (or append it),
			   then append mixed-port if missing. A duplicate top-level key would be
			   invalid YAML, so we replace in place (see docs/内核接口参考.md §5). */
			StringBuilder out = new StringBuilder();
			boolean ecWritten = false;
			boolean secretWritten = false;
			for (String line : text.split("\n", -1)) {
				if (needEc && topLevelLine(line + "\n", "external-controller:") != null) {
					out.append("external-controller: 127.0.0.1:")
					   .append(MihomoConfig.API_PORT).append('\n');
					ecWritten = true;
				} else if (needSecret && topLevelLine(line + "\n", "secret:") != null) {
					out.append("secret: \"").append(prefs.getSecret()).append("\"\n");
					secretWritten = true;
				} else {
					out.append(line).append('\n');
				}
			}
			if (needEc && !ecWritten)
				out.append("external-controller: 127.0.0.1:")
					.append(MihomoConfig.API_PORT).append('\n');
			if (needSecret && !secretWritten)
				out.append("secret: \"").append(prefs.getSecret()).append("\"\n");
			if (needMp)
				out.append("mixed-port: ").append(prefs.getProxyPort()).append('\n');

			java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile, false);
			fos.write(out.toString().getBytes("UTF-8"));
			fos.close();
			appendLog("config: 自定义配置已对齐 App 必需项（external-controller=127.0.0.1:"
				+ MihomoConfig.API_PORT
				+ (needMp ? ", mixed-port=" + prefs.getProxyPort() : "")
				+ (needSecret ? ", secret" : "") + "）");
		} catch (Exception e) {
			appendLog("config: 检查自定义配置失败：" + e);
		}
	}

	/* A one-line summary of the keys the app itself depends on, so "the tunnel
	   works but the counters/pickers are dead" is traceable to the config. */
	private static String configKeySummary(File configFile) {
		try {
			byte[] buf = new byte[(int) configFile.length()];
			java.io.FileInputStream in = new java.io.FileInputStream(configFile);
			int n = in.read(buf);
			in.close();
			String text = new String(buf, 0, n, "UTF-8");
			String ec = topLevelLine(text, "external-controller:");
			String mp = topLevelLine(text, "mixed-port:");
			if (mp == null)
			  mp = topLevelLine(text, "port:");
			return "external-controller="
				+ (ec == null ? "缺失!" : ec.substring("external-controller:".length()).trim())
				+ ", mixed-port="
				+ (mp == null ? "缺失!" : mp.substring(mp.indexOf(':') + 1).trim());
		} catch (Exception e) {
			return "(读取配置失败: " + e + ")";
		}
	}

	/* True when "key" appears as a top-level YAML key (column 0). */
	private static boolean hasTopLevelKey(String text, String key) {
		return topLevelLine(text, key) != null;
	}

	/* The whole top-level line that starts with `key`, or null. */
	private static String topLevelLine(String text, String key) {
		int idx = 0;
		while ((idx = text.indexOf(key, idx)) >= 0) {
			int lineStart = text.lastIndexOf('\n', idx) + 1;
			if (idx - lineStart == 0) {
				int end = text.indexOf('\n', idx);
				return end < 0 ? text.substring(idx).trim()
					: text.substring(idx, end).trim();
			}
			idx += key.length();
		}
		return null;
	}

	/* The url-test subgroup the top select group should default to, or null when
	   there is nothing to select because the config already picked it. */
	private static String autoTargetGroup(Preferences prefs) {
		/* The generated config already defaults the top group to GLOBAL_GROUP,
		   which is the url-test group of the whole (country-filtered) pool, so
		   there is nothing to select in auto mode. */
		return null;
	}

	/* Ask mihomo to select the chosen proxy/group inside the group we built.
	   In auto mode we select the country url-test subgroup (whose own
	   health-check then keeps picking the fastest node within that country);
	   in manual mode we select the exact node the user tapped. */
	private void applySelectedNode(Preferences prefs) {
		if (prefs.getAutoSelect()) {
			String target = autoTargetGroup(prefs);
			/* null = "auto best country": the generated config already lists that
			   country's url-test group first, so there is nothing to select. */
			if (target != null)
			  selectInGroup(MihomoConfig.GROUP, target);
			return;
		}
		String sel = prefs.getSubSelected();
		if (sel == null || sel.isEmpty())
		  return;
		selectInGroup(MihomoConfig.GROUP, sel);
	}

	/* Ask mihomo to select the chosen proxy/group inside the group we built.
	   We use the external-controller REST API (loopback :9090) instead of the
	   JNI invokeAction bridge: the bridge hands Java strings to the core as
	   "modified UTF-8", which corrupts supplementary-plane characters such as
	   flag emoji in a node name, so changeProxy then reports "proxy not
	   exist". REST carries standard UTF-8 end to end, so the name matches
	   exactly what the config registered. */
	private void selectInGroup(String group, String proxy) {
		appendLog("selected: " + proxy + " in group " + group);
		/* Best-effort and network-bound, so run off the calling thread. */
		new Thread(() -> {
			/* The core brings the TUN up before its external-controller HTTP
			   listener is bound, and with a large subscription the config parse
			   can keep that listener closed for several seconds longer. A single
			   early probe would then report "未就绪" and silently drop the user's
			   pick — yet the tunnel is already proxying, so the failure is easy
			   to miss (proxy traffic keeps flowing via the default node). Retry
			   the whole apply so the selector lands once the control API is
			   actually listening. */
			/* The core brings the TUN up before its external-controller HTTP
			   listener binds, and a large subscription can keep that listener
			   closed for many seconds. Keep retrying until it answers (or the
			   tunnel is torn down), instead of giving up after one short window
			   and silently dropping the user's pick. */
			for (int attempt = 1; attempt <= 120; attempt++) {
				if (startupAborted)
				  return;
				if (waitForController()) {
					applySelector(group, proxy);
					return;
				}
				if (attempt == 1)
				  appendLog("selector: 控制接口 " + controllerHost + ":" + MihomoConfig.API_PORT
						+ " 未就绪，后台持续重试中");
				if (attempt % 20 == 0)
				  appendLog("selector: 仍等待 " + controllerHost + ":" + MihomoConfig.API_PORT
						+ "（" + lastControllerError + "）");
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (startupAborted)
			  return;
			appendLog("selector set failed: 控制接口 " + controllerHost + ":"
				+ MihomoConfig.API_PORT + " 未就绪（内核可能未监听）：" + lastControllerError);
		}).start();
	}

	/* PUT the chosen proxy into `group` once the control API is reachable.
	   Extracted so selectInGroup can retry it without re-duplicating the
	   resolve-and-PUT logic. */
	private void applySelector(String group, String proxy) {
		try {
			String target = null;
			try {
				target = resolveMember(group, proxy);
			} catch (Throwable ignore) {
			}
			if (target == null)
			  target = proxy; /* last-ditch: try the raw name */
			int code = putSelector(group, target);
			String note = (code >= 200 && code < 300) ? "ok" : ("http " + code);
			if (!target.equals(proxy))
			  note += " (resolved to " + target + ")";
			appendLog("selector set: " + note);
			testProxyConnectivity(new Preferences(this));
		} catch (Throwable e) {
			appendLog("selector set skipped: " + e);
		}
	}

	/* Poll the core's control API until it answers, so a selector PUT right
	   after startup does not race the core's own HTTP listener. Returns false
	   when it never came up within the window (then the log says so, instead
	   of the failure being invisible). */
	private boolean waitForController() {
		for (int i = 0; i < 24; i++) {
			if (isControllerUp())
			  return true;
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				return false;
			}
		}
		return false;
	}

	/* Confirm the control API is up. mihomo binds the clash-api HTTP listener
	   only after it has finished bringing the TUN up and parsing a possibly
	   large config, so poll for a bounded 90s instead of giving up in 6s - a
	   too-early "NOT ready" is exactly the misleading signal we keep hitting.
	   When it truly never listens, say so and point at the core's own log. */
	private void verifyController() {
		new Thread(() -> {
			boolean ok = false;
			for (int i = 0; i < 90; i++) {
				if (isControllerUp())
				  { ok = true; break; }
				try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
			}
			if (ok) {
				appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT + " ready");
				return;
			}
			/* The core brought mixed-port up but skipped the external-controller
			   listener. Try to force it via the UpdateConfig action, then re-poll;
			   if that brings it up we skip the alarming "never ready" dump. */
			kickClashApi();
			boolean recovered = false;
			for (int i = 0; i < 30; i++) {
				if (isControllerUp()) { recovered = true; break; }
				try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
			}
			if (recovered) {
				appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT
					+ " ready（经 UpdateConfig 兜底启动）");
				return;
			}
			appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT
				+ " NOT ready（90s 内未监听，核心未启动 clash-api）");
			if (lastControllerError != null)
			  appendLog("controller: 最后一次探测：" + lastControllerError);
			if (quickSetupError != null && !quickSetupError.isEmpty())
			  appendLog("controller: 内核配置加载报错：" + quickSetupError);
			/* Some Android builds only expose the IPv6 loopback to the app
			   process; if mihomo bound [::1] the IPv4 probe fails even though
			   the API is alive - probe it so we can tell the two apart. */
			if (probeHost("::1"))
			  appendLog("controller: 但 [::1]:" + MihomoConfig.API_PORT
				  + " 通了 —— 核心绑在 IPv6 回环，App 走 IPv4 才失败");
			/* Pin down whether the chosen API port is genuinely still occupied
			   (a stale tunnel / adb forward / emulator holding 9090) or free but
			   mihomo refused to bind it (config / permission issue). This single
			   line is the most useful one for "9090 起不来" debugging. */
			boolean portFree = false;
			java.net.ServerSocket probe = null;
			try {
				probe = new java.net.ServerSocket();
				probe.setReuseAddress(true);
				probe.bind(new java.net.InetSocketAddress("127.0.0.1", MihomoConfig.API_PORT));
				portFree = true;
			} catch (Throwable e) {
				appendLog("controller: 端口 127.0.0.1:" + MihomoConfig.API_PORT
					+ " 仍被占用（" + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : (": " + e.getMessage()))
					+ "）—— 这就是控制接口起不来的直接原因");
			} finally {
				if (probe != null) {
					try { probe.close(); } catch (Throwable ignore) { }
				}
			}
			if (portFree)
			  appendLog("controller: 127.0.0.1:" + MihomoConfig.API_PORT
				+ " 当前空闲但仍未监听 —— 不是端口占用，而是 mihomo 绑定/配置问题（见上方 mihomo: 日志）");
			if (probeHost(deviceHost()))
			  appendLog("controller: 但 " + deviceHost() + ":" + MihomoConfig.API_PORT
				+ " 通了 —— 核心绑在网卡/LAN 地址，App 应改连该地址（见下 listen: 行）");
			dumpListeningPorts();
			dumpControllerLog();
			dumpRawLog();
			dumpMihomoLog();
			dumpConfigTail();
			}).start();
			}

			/* Force the clash-api (external-controller) listener to start. On some
			   libmihomo-android builds quickSetup brings mixed-port up but never
			   starts the external-controller listener, even though the profile
			   carries external-controller + secret - so the RESTful API (selectors,
			   /connections, node switching) stays dead while the tunnel proxies
			   fine. mihomo's UpdateConfig action re-applies the general config and
			   (re)starts that listener. Called both proactively after startup and
			   as a fallback from verifyController; harmless if it was already up. */
			private void kickClashApi() {
				/* Best-effort: (re)start the core's listeners via the in-process
				   startListener action. On libmihomo-android v0.3.3 quickSetup brings
				   the mixed-port up but leaves the clash-api (9090) unbound; startListener
				   recreates every listener, including external-controller, so the REST API
				   (selectors, /connections, node switching) comes up for external clients
				   too. In-app stats already work through the apiAction bridge regardless. */
				try {
					String json = "{\"id\":\"\",\"method\":\"startListener\",\"data\":null}";
					appendLog("clash-api: 尝试用 startListener 拉起监听（含 external-controller 9090）");
					Clash.INSTANCE.invokeAction(json, new InvokeInterface() {
						@Override
						public void onResult(String result) {
							appendLog("clash-api: startListener 结果: "
								+ (result == null || result.isEmpty() ? "ok" : result));
						}
					});
				} catch (Throwable e) {
					appendLog("clash-api: invokeAction 失败: " + e);
				}
			}

			/* Definitive: read /proc/net/tcp[6] and report which of our ports (API 9090,
			mixed 7890) are actually LISTEN-ing and on which address. Settles whether
			mihomo bound the clash-api at all (the "9090 never listens" symptom) vs a
			pure reachability problem, and shows the exact bound address. */
			private void dumpListeningPorts() {
			for (String file : new String[] { "/proc/net/tcp", "/proc/net/tcp6" }) {
				java.io.File f = new java.io.File(file);
				if (!f.exists()) continue;
				try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
					String l; boolean header = true;
					while ((l = r.readLine()) != null) {
						if (header) { header = false; continue; }
						String[] c = l.trim().split("\\s+");
						if (c.length < 4) continue;
						if (!"0A".equalsIgnoreCase(c[3])) continue; /* 0A = LISTEN */
						String local = c[1];
						int colon = local.indexOf(':');
						if (colon < 0) continue;
						int port;
						try { port = Integer.parseInt(local.substring(colon + 1), 16); }
						catch (Throwable e) { continue; }
						if (port == MihomoConfig.API_PORT || port == 7890)
						  appendLog("listen: " + file + " " + local + " (port " + port + ")");
					}
				} catch (Throwable ignore) { }
			}
			appendLog("listen: 以上为 9090/7890 的实际 LISTEN 状态（无条目=该端口未监听）");
			}

			/* Quick TCP connect probe to host:API_PORT, used to tell an IPv4-only
	   failure apart from "the API is up but on a different loopback". */
	private boolean probeHost(String host) {
		java.net.Socket s = null;
		try {
			s = new java.net.Socket();
			protectLocalSocket(s);
			s.connect(new java.net.InetSocketAddress(host, MihomoConfig.API_PORT), 800);
			return true;
		} catch (Throwable e) {
			return false;
		} finally {
			if (s != null) {
				try { s.close(); } catch (Throwable ignore) { }
			}
		}
	}

	/* When the API never binds, the generated config is the first thing to
	   suspect (a malformed external-controller line, a stray duplicate key, an
	   indentation slip). Dump the tail of config.yaml - the sections the app
	   appends (dns/log/tun/external-controller/...) - so the log is
	   self-contained for diagnosis without pulling the file off the device. */
	private void dumpConfigTail() {
		File f = new File(getFilesDir(), "config.yaml");
		if (!f.exists()) {
			appendLog("controller: 配置未生成（config.yaml 不存在）");
			return;
		}
		try {
			java.util.List<String> lines = new java.util.ArrayList<String>();
			try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
				String l;
				while ((l = r.readLine()) != null)
				  lines.add(l);
			}
			int start = Math.max(0, lines.size() - 50);
			StringBuilder sb = new StringBuilder("controller: config.yaml 尾部（共 ")
				.append(lines.size()).append(" 行）：\n");
			for (int i = start; i < lines.size(); i++)
			  sb.append("  ").append(lines.get(i)).append('\n');
			appendLog(sb.toString());
		} catch (Throwable e) {
			appendLog("controller: 读取配置失败：" + e);
		}
	}

	/* One quick reachability probe of the control API (no retries). Used by
	   verifyController; the selector/test loops use waitForController which
	   retries internally. */
	/* The clash-api binds the loopback (external-controller: 127.0.0.1) and, with
	   allow-lan off, only serves loopback clients - so the device IP is never a
	   valid controller address. Probing it as a fallback used to latch onto a
	   host with no listener and report every test as failed; keep it 127.0.0.1. */
	private boolean isControllerUp() {
		/* Primary signal: the in-process action bridge reaches the core without
		   any HTTP listener, so the controller is "ready" the moment
		   getConnections answers - regardless of whether mihomo ever bound
		   external-controller on 9090 (on this build it does not). The HTTP probe
		   below is only a best-effort fallback that resolves the exact bound host
		   for external clients; if it never connects we no longer treat that as a
		   failure, which removes the misleading "9090 NOT ready" dump. */
		if (isCoreReachable()) {
			controllerReady = true;
			return true;
		}
		for (String h : new String[] { "127.0.0.1", deviceHost(), "::1" }) {
			if (h == null) continue;
			if (probeVersion(h)) {
				controllerHost = h;
				apiHost = h;
				controllerReady = true;
				appendLog("controller: " + h + ":" + MihomoConfig.API_PORT + " ready");
				return true;
			}
		}
		return false;
	}

	/* === Local control API over a VPN-bypassing socket =========================
	   The VPN this service creates would capture the app's own sockets and they
	   would never reach mihomo's loopback listeners (the "9090 never listens"
	   symptom). protect() pulls each socket out of the VPN routing so the app
	   can talk to its own core. Activities reuse localApi() via the static hook. */
	private static TProxyService sInstance;
	/* Host mihomo's clash-api actually bound to (127.0.0.1 / LAN IP / [::1]),
	   resolved at startup by isControllerUp so every localApi call reaches it
	   wherever the core decided to listen. */
	private String apiHost = "127.0.0.1";
	static String apiBaseHost() {
		return sInstance != null ? sInstance.apiHost : "127.0.0.1";
	}
	static boolean protectLocalSocket(java.net.Socket s) {
		return sInstance != null && sInstance.protect(s);
	}
	/* Clear all recorded recent requests from both the in-memory list and the
	   persisted store. Called by the Recent Requests screen's "清空" action;
	   without clearing memory the background flush would rewrite the list on
	   its next tick. lastRecentFlush is reset so the subsequent flush is not
	   skipped by the 2s throttle. */
	public static void clearRecentRequests() {
		if (sInstance == null)
		  return;
		synchronized (sInstance.recentRequests) {
			sInstance.recentRequests.clear();
		}
		sInstance.lastRecentFlush = 0;
		sInstance.flushRecentRequests(android.os.SystemClock.elapsedRealtime());
	}
	/* Latest /connections snapshot the stats poll captured, or null when the
	   tunnel is not running / has not polled yet. The connections screen reads
	   this so it never has to issue its own bridge call. */
	static String lastConnectionsSnapshot() {
		return sInstance != null ? sInstance.connSnapshot : null;
	}
	static final class ApiResult {
		int code = -1;   /* -1 = transport failure (API unreachable) */
		String body;
		long rtt;
		/* Exception detail when code == -1: distinguishes "nothing listening
		   yet" (Connection refused during warmup) from "socket captured by the
		   VPN / protect failed" (timeout). Used to word the error accurately. */
		String error;
	}
	/* GET/PUT on host:port over a socket that BYPASSES the VPN. protect() is the
	   documented way to keep the app's own socket out of the tunnel it created.
	   Body reading is byte-based so multi-byte (Chinese) JSON isn't truncated by
	   Content-Length. */
	static ApiResult localApi(String method, String host, int port, String path,
			String body, String secret) {
		return localApi(method, host, port, path, body, secret, 6000);
	}

	/* Same, with an explicit socket timeout. Some replies legitimately take
	   longer than the default 6s - GET /proxies/{name}/delay runs the probe
	   inside the core for as long as its own `timeout` - and cutting it off
	   leaves a working-but-slow node with no verdict at all. */
	static ApiResult localApi(String method, String host, int port, String path,
			String body, String secret, int timeoutMs) {
		ApiResult r = new ApiResult();
		long t0 = System.currentTimeMillis();
		java.net.Socket s = null;
		boolean prot = false;
		try {
			s = new java.net.Socket();
			prot = protectLocalSocket(s);
			/* Dialing is either quick or hopeless, so keep a short connect
			   timeout and spend the caller's budget waiting for the reply. */
			s.connect(new java.net.InetSocketAddress(host, port),
				Math.min(6000, Math.max(2000, timeoutMs)));
			s.setSoTimeout(Math.max(2000, timeoutMs));
			java.io.InputStream is = s.getInputStream();
			java.io.OutputStream os = s.getOutputStream();
			byte[] bodyBytes = body == null ? new byte[0]
				: body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder();
			head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
				.append("Host: ").append(host).append(':').append(port).append("\r\n");
			if (secret != null && !secret.isEmpty())
			  head.append("Authorization: Bearer ").append(secret).append("\r\n");
			head.append("Accept: */*\r\n").append("Connection: close\r\n");
			if (body != null) {
				head.append("Content-Type: application/json\r\n")
					.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
			}
			head.append("\r\n");
			os.write(head.toString().getBytes(StandardCharsets.UTF_8));
			if (body != null) os.write(bodyBytes);
			os.flush();
			String status = readLineBytes(is);
			if (status == null) { r.rtt = System.currentTimeMillis() - t0; return r; }
			try { r.code = Integer.parseInt(status.split(" ")[1]); } catch (Throwable ignore) {}
			int contentLength = -1; boolean chunked = false; String line;
			while ((line = readLineBytes(is)) != null && !line.isEmpty()) {
				String ll = line.toLowerCase();
				if (ll.startsWith("content-length:")) {
					try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Throwable ignore) {}
				} else if (ll.startsWith("transfer-encoding:") && ll.contains("chunked"))
				  chunked = true;
			}
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			if (chunked) {
				while (true) {
					String sz = readLineBytes(is); if (sz == null) break;
					int i = sz.indexOf(';'); if (i >= 0) sz = sz.substring(0, i);
					int len; try { len = Integer.parseInt(sz.trim(), 16); } catch (Throwable e) { len = 0; }
					if (len <= 0) break;
					byte[] buf = new byte[len]; int got = 0;
					while (got < len) { int n = is.read(buf, got, len - got); if (n < 0) break; got += n; }
					bos.write(buf, 0, got);
					readLineBytes(is); /* trailing CRLF after chunk */
				}
			} else if (contentLength >= 0) {
				byte[] buf = new byte[2048]; int total = 0;
				while (total < contentLength) {
					int n = is.read(buf, 0, Math.min(buf.length, contentLength - total));
					if (n < 0) break; bos.write(buf, 0, n); total += n;
				}
			} else {
				byte[] buf = new byte[2048]; int n;
				while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
			}
			r.body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Throwable e) {
			r.code = -1;
			r.error = (prot ? "" : "[protect未生效] ")
				+ e.getClass().getSimpleName()
				+ (e.getMessage() != null ? (": " + e.getMessage()) : "");
		} finally {
			if (s != null) try { s.close(); } catch (Throwable ignore) {}
		}
		r.rtt = System.currentTimeMillis() - t0;
		return r;
	}

	/* Serialises every apiAction() bridge call - the native bridge supports
	   only one in-flight callback, so concurrent calls lose results (see
	   apiAction). Static because callers are spread across the service, the
	   Activities and the embedded clash-api server. */
	private static final Object API_LOCK = new Object();
	/* Whether the action bridge wants `data` as a JSON STRING (FlClash's shape)
	   or as an inline JSON object: -1 unknown, 1 string, 0 object. Decided once,
	   empirically, by apiActionRaw - see the note there. */
	private static volatile int dataAsString = -1;
	/* Serialises that one-shot decision (see apiActionRaw). */
	private static final Object DETECT_LOCK = new Object();
	/* Latest /connections snapshot fetched by accumulateProxy() on statsThread
	   (kept for in-process callers). */
	private volatile String connSnapshot = null;
	/* Compact form of that snapshot, published to SharedPreferences so the
	   connections screen - which runs in the MAIN process and cannot reach the
	   bridge or sInstance - can read it cross-process. Capped and only
	   rewritten when it actually changed. */
	private static final int MAX_CONN_SNAPSHOT = 200;
	private volatile String lastConnSnapshotJson = "";

	/* === In-process control bridge ============================================
	   libmihomo-android v0.3.3's quickSetup brings the mixed-port (7890) up but
	   often never binds the external-controller (clash-api) HTTP listener on 9090
	   ("9090 never listens") - even though the profile carries external-controller
	   + secret. Every REST endpoint (/connections, /proxies, selector switching)
	   then dies while the tunnel proxies fine. The SDK also exposes the SAME
	   actions the REST endpoints use through Clash.INSTANCE.invokeAction (the
	   "action" mechanism): {"id","method","data"} in, ActionResult
	   {"id","method","data","code"} back. Those run IN-PROCESS and need no HTTP
	   listener at all, so connections/proxies/traffic are reachable regardless of
	   9090. We block on the async callback (it fires on a JNI thread, no deadlock). */
	static String apiAction(String method, String data) {
		return apiAction(method, data, 6000);
	}

	/* Same, with an explicit callback wait. Some actions legitimately take
	   longer than the default 6s - the isolated node delay probe runs for its
	   own `timeout` (up to 30s) - and giving up early used to lose the answer
	   entirely (the caller then falls back or reports the node untested), so
	   such callers must extend the wait past the action's own deadline. */
	static String apiAction(String method, String data, long waitMs) {
		String raw = apiActionRaw(method, data, waitMs);
		if (raw == null)
		  return null;
		try {
			JSONObject r = new JSONObject(raw);
			if (r.optInt("code", -1) != 0)
			  return null;
			Object d = r.opt("data");
			return (d == null || d == JSONObject.NULL) ? "" : d.toString();
		} catch (Throwable e) {
			return null;
		}
	}

	/* The RAW {"id","method","data","code"} reply, or null when there was no
	   reply at all. Needed to tell "the core REFUSED these parameters"
	   (code != 0, e.g. "invalid data type" for a field with the wrong JSON
	   type) from "no answer" - the convenience wrapper above cannot express
	   the difference, since both surface as null.
	   The native action bridge keeps a SINGLE in-flight callback: two
	   overlapping invokeAction() calls make the earlier result get delivered
	   to the wrong waiter, so that waiter never wakes and times out with null.
	   The traffic poll (every 1s) and an Activity's own poll (every 1.5s)
	   overlap constantly, which is why the connections screen once came back
	   empty while the background stats saw connections. Serialise here. */
	static String apiActionRaw(String method, String data, long waitMs) {
		if (!Clash.INSTANCE.isLoaded())
		  return null;   /* no core here (e.g. the main process) - not an error */
		if (data == null)
		  return invokeAction(method, null, false, waitMs);
		/* How `data` must be carried is NOT documented, and the core validates
		   the container BEFORE dispatching: the wrong one is answered with
		   code=-1 "invalid data type". Getting it wrong silently broke EVERY
		   action that takes parameters - the delay test and node switching -
		   while the parameterless ones (getConnections / getProxies) kept
		   working, which is exactly what made this so hard to see. Try the
		   string form first (what FlClash sends: the core unmarshals the string
		   itself), fall back to the inline object, and remember the winner. */
		if (dataAsString == 1)
		  return invokeAction(method, data, true, waitMs);
		if (dataAsString == 0)
		  return invokeAction(method, data, false, waitMs);
		/* Undecided: settle it on ONE thread. This latch is shared by every
		   probe, and 16 concurrent ones racing it could latch the WRONG form and
		   then fail at once - the "单个测速能用、测速全部全废" symptom. */
		synchronized (DETECT_LOCK) {
			if (dataAsString == 1)
			  return invokeAction(method, data, true, waitMs);
			if (dataAsString == 0)
			  return invokeAction(method, data, false, waitMs);
			String raw = invokeAction(method, data, true, waitMs);
			/* Only a DEFINITE rejection proves the string form wrong. "No answer
			   at all" (null) is not evidence: latching the object form on it
			   would break every later call - including a later SINGLE test -
			   until the process restarts. */
			if (raw == null || !badDataType(raw)) {
				if (raw != null) {
					dataAsString = 1;
					TestLog.append("bridge: data 采用字符串形式（内核接受）");
				}
				return raw;
			}
			dataAsString = 0;
			TestLog.append("bridge: data 字符串形式被拒 " + truncate(raw) + " → 改用内联对象");
			return invokeAction(method, data, false, waitMs);
		}
	}

	/* The core refused the payload container rather than the node. */
	private static boolean badDataType(String raw) {
		return raw != null && raw.contains("invalid data type");
	}

	/* One bridge call. `asString` carries `data` as a JSON string (the core
	   unmarshals it) instead of an inline JSON object. Serialised on API_LOCK:
	   the native action bridge keeps a SINGLE in-flight callback, so
	   overlapping invokeAction() calls make the earlier result get delivered to
	   the wrong waiter, which then never wakes and times out with null. */
	private static String invokeAction(String method, String data, boolean asString,
			long waitMs) {
		synchronized (API_LOCK) {
			final boolean[] done = { false };
			/* Kept for the diagnosis log below: "no answer at all" (timeout)
			   and "the core answered with an error code" are very different
			   failures, and the return value alone cannot tell them apart. */
			final int[] code = { Integer.MIN_VALUE };
			final String[] raw = { null };
			try {
				JSONObject j = new JSONObject();
				j.put("id", "");
				j.put("method", method);
				if (data == null)
				  j.put("data", JSONObject.NULL);
				else if (asString)
				  j.put("data", data);
				else {
					try { j.put("data", new JSONObject(data)); }
					catch (Throwable e) { j.put("data", data); }
				}
				Clash.INSTANCE.invokeAction(j.toString(), new InvokeInterface() {
					@Override
					public void onResult(String result) {
						raw[0] = result;
						if (result != null) {
							try {
								code[0] = new JSONObject(result).optInt("code", -1);
							} catch (Throwable ignore) { }
						}
						synchronized (done) { done[0] = true; done.notifyAll(); }
					}
				});
				synchronized (done) {
					long end = System.currentTimeMillis() + Math.max(500, waitMs);
					while (!done[0] && System.currentTimeMillis() < end)
					  done.wait(Math.max(1, end - System.currentTimeMillis()));
					if (!done[0]) {
						TestLog.append("bridge: 动作 " + method + " 等待 " + waitMs
							+ "ms 未收到回调（内核无响应 / 该动作不返回）");
					} else if (code[0] != 0) {
						TestLog.append("bridge: 动作 " + method + " 返回 code=" + code[0]
							+ " raw=" + truncate(raw[0]));
					}
				}
			} catch (Throwable e) {
				TestLog.append("bridge: 动作 " + method + " 异常 " + e);
				return null;
			}
			return raw[0];
		}
	}

	/* Short form of a raw bridge reply for the log. */
	private static String truncate(String s) {
		if (s == null)
		  return "null";
		s = s.replace('\n', ' ');
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}

	/* Mirrors localApi()'s signature but routes through the in-process bridge.
	   The action's `data` is byte-identical to the REST response body, so callers
	   parse it exactly as before. Paths we cannot express as an action fall back
	   to the HTTP listener, leaving behaviour unchanged when 9090 IS up. */
	static ApiResult bridgeApi(String method, String host, int port, String path,
			String body, String secret) {
		return bridgeApi(method, host, port, path, body, secret, 6000);
	}

	/* Same, with an explicit HTTP timeout for the paths that fall through to
	   the listener - notably /delay, whose reply arrives only after the core's
	   own probe finishes. */
	static ApiResult bridgeApi(String method, String host, int port, String path,
			String body, String secret, int timeoutMs) {
		ApiResult r = new ApiResult();
		r.code = -1;
		try {
			/* Each action-mapped path FALLS THROUGH to the HTTP listener when
			   the in-process bridge returns nothing - which is always the case
			   in the MAIN process, where the core is not loaded. That is what
			   makes these calls work from Activities (e.g. the manual latency
			   test), while the :native process keeps using the bridge. */
			if ("GET".equals(method) && "/connections".equals(path)) {
				String d = apiAction("getConnections", null);
				if (d != null) { r.code = 200; r.body = d; return r; }
			} else if ("DELETE".equals(method) && "/connections".equals(path)) {
				String d = apiAction("closeAllConnections", null);
				if (d != null) { r.code = 204; r.body = d; return r; }
			} else if ("GET".equals(method) && "/proxies".equals(path)) {
				String d = apiAction("getProxies", null);
				if (d != null) { r.code = 200; r.body = d; return r; }
			} else if ("GET".equals(method) && path.startsWith("/proxies/")
					&& path.indexOf("/delay") > 0) {
				/* /delay is async in the core and has no bridge action: always
				   use the HTTP listener. */
				return localApi(method, host, port, path, body, secret, timeoutMs);
			} else if ("GET".equals(method) && path.startsWith("/proxies/")) {
				String group = path.substring("/proxies/".length());
				String d = apiAction("getProxies", null);
				if (d != null) {
					JSONObject all = new JSONObject(d).optJSONObject("proxies");
					JSONObject g = all == null ? null : all.optJSONObject(group);
					if (g != null) { r.code = 200; r.body = g.toString(); return r; }
				}
			} else if ("PUT".equals(method) && path.startsWith("/proxies/")) {
				String group = path.substring("/proxies/".length());
				String proxy = "";
				if (body != null) {
					try { proxy = new JSONObject(body).optString("name", ""); } catch (Throwable ignore) {}
				}
				String d = apiAction("changeProxy",
					"{\"group-name\":\"" + group.replace("\\", "\\\\").replace("\"", "\\\"")
					+ "\",\"proxy-name\":\"" + proxy.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
				if (d != null) { r.code = 204; r.body = d; return r; }
			}
		} catch (Throwable e) {
			r.code = -1;
		}
		return localApi(method, host, port, path, body, secret, timeoutMs);
	}

	/* Core reachable via the in-process bridge (no 9090 needed). Used as the
	   reachability gate so selector/speed-test logic proceeds even when the HTTP
	   listener never bound. */
	static boolean isCoreReachable() {
		return apiAction("getConnections", null) != null;
	}

	private static String readLineBytes(java.io.InputStream is) throws java.io.IOException {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		int b;
		while ((b = is.read()) != -1) {
			if (b == '\r') {
				int c = is.read();
				if (c == '\n') break;
				bos.write('\r');
				if (c != -1) bos.write(c);
			} else if (b == '\n') {
				break;
			} else {
				bos.write(b);
			}
		}
		return new String(bos.toByteArray(), StandardCharsets.UTF_8);
	}

	private boolean probeVersion(String host) {
		ApiResult r = localApi("GET", host, MihomoConfig.API_PORT,
			"/version", null, prefs.getSecret());
		if (r.code == -1) {
			/* Word the error by its real cause instead of always blaming the
			   VPN: a refused connect means the API simply has not bound yet
			   (warmup), a timeout means the socket was likely captured (or
			   protect failed), anything else is surfaced verbatim. */
			String d = r.error == null ? "" : r.error;
			if (d.contains("refused") || d.contains("ECONNREFUSED"))
			  lastControllerError = "控制接口尚未监听（核心预热中，clash-api 还没 bind）";
			else if (d.contains("timed out") || d.contains("SocketTimeout")
					|| d.contains("timeout") || d.contains("保护"))
			  lastControllerError = "连接控制接口超时（疑似 VPN 捕获回环 / protect 未生效）";
			else
			  lastControllerError = "transport error（" + d + "）";
			return false;
		}
		/* 2xx = healthy; 401 = listener up but auth wrong, still reachable. */
		return (r.code >= 200 && r.code < 300) || r.code == 401;
	}

	/* First non-loopback, non-TUN IPv4 of the device, kept as a fallback in case
	   the 127.0.0.1 loopback is ever captured by the TUN. The VPN tunnel
	   interface (tun*) is skipped so we never pick the captured tunnel. Falls
	   back to 127.0.0.1. */
	private String deviceHost() {
		try {
			java.util.Enumeration<java.net.NetworkInterface> en =
				java.net.NetworkInterface.getNetworkInterfaces();
			while (en.hasMoreElements()) {
				java.net.NetworkInterface nif = en.nextElement();
				if (nif.isLoopback() || !nif.isUp())
				  continue;
				String n = nif.getName();
				if (n != null && (n.startsWith("tun")
						|| n.startsWith("ppp") || n.contains("tun")))
				  continue;
				java.util.Enumeration<java.net.InetAddress> adds = nif.getInetAddresses();
				while (adds.hasMoreElements()) {
					java.net.InetAddress a = adds.nextElement();
					if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress())
					  return a.getHostAddress();
				}
			}
		} catch (Throwable ignore) { }
		return "127.0.0.1";
	}

	private String apiBase() {
		return "http://" + controllerHost + ":" + MihomoConfig.API_PORT;
	}

	/* mihomo's startup/bind lines go to STDOUT (captured in tproxy.log by the
	   dup2 redirect at startup), NOT to cache/mihomo.log (this build never
	   creates it) and NOT through setEventListener (which stays silent about
	   the API). So the clash-api bind failure - e.g. "Failed to start API:
	   listen tcp 127.0.0.1:9090: bind: ..." - lives in tproxy.log as a raw
	   line, not as a "mihomo:" event and not as a "corelog:"/"mihomolog:" line.
	   Dump the tail verbatim AND surface every api/controller/bind line on its
	   own "rawlog-api:" prefix so the reason is unmissable. */
	private void dumpRawLog() {
		File f = new File(getCacheDir(), "tproxy.log");
		if (!f.exists()) return;
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
			java.util.List<String> all = new java.util.ArrayList<String>();
			String line;
			while ((line = r.readLine()) != null) all.add(line);
			r.close();
			int start = Math.max(0, all.size() - 60);
			for (int i = start; i < all.size(); i++)
			  appendLog("rawlog: " + all.get(i));
			/* Re-scan the WHOLE file for the core's API/controller/bind chatter
			   and print it under a distinct prefix, so a single decisive line
			   ("Failed to start API", "listen tcp ... bind: ...", "RESTful API
			   listening at ...") is not lost among 60 unrelated raw lines. */
			java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
			for (String l : all) {
				String low = l.toLowerCase();
				if (low.contains("api") || low.contains("controller") || low.contains("bind")
						|| low.contains("listen") || low.contains("external") || low.contains("clash")
						|| low.contains("fail") || low.contains("error") || low.contains("panic")
						|| low.contains("secret") || low.contains("9090")) {
					String t = l.trim();
					if (seen.add(t))
					  appendLog("rawlog-api: " + t);
				}
			}
			if (seen.isEmpty())
			  appendLog("rawlog-api: 无 api/controller/bind 相关行（核心未打印原因，或日志已被截断）");
		} catch (Throwable ignore) { }
	}

	private void dumpControllerLog() {
		/* mihomo writes its startup/bind lines to the file named by log.file
		   (cache/mihomo.log), not to tproxy.log - read that first so a silent
		   controller bind failure actually shows up. */
		boolean found = dumpLog(new File(getCacheDir(), "mihomo.log"));
		if (!found)
		  dumpLog(new File(getCacheDir(), "tproxy.log"));
	}

	private boolean dumpLog(File f) {
		if (f == null || !f.exists()) return false;
		int shown = 0;
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
			String line;
			while ((line = r.readLine()) != null && shown < 40) {
				String l = line.toLowerCase();
				if (l.contains("controller") || l.contains("bind") || l.contains("listen")
						|| l.contains("external") || l.contains("fail") || l.contains("error")
						|| l.contains("panic") || l.contains("api")) {
					appendLog("corelog: " + line.trim());
					shown++;
				}
			}
			r.close();
		} catch (Throwable ignore) { }
		return shown > 0;
	}

	/* mihomo writes its own log (incl. clash-api start/bind lines and any panic)
	   to <cacheDir>/mihomo.log once the config sets log.file. Surface the lines
	   that explain why the control API never came up. */
	private void dumpMihomoLog() {
		File f = new File(getCacheDir(), "mihomo.log");
		if (!f.exists()) {
			appendLog("mihomolog: 未生成（config 未设置 log.file 或核心未写日志）");
			return;
		}
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
			java.util.List<String> all = new java.util.ArrayList<String>();
			String line;
			int shown = 0;
			while ((line = r.readLine()) != null) {
				all.add(line);
				String l = line.toLowerCase();
				if (l.contains("controller") || l.contains("clash-api") || l.contains("bind")
						|| l.contains("listen") || l.contains("external") || l.contains("api")
						|| l.contains("fail") || l.contains("error") || l.contains("panic")
						|| l.contains("secret")) {
					appendLog("mihomolog: " + line.trim());
					shown++;
				}
			}
			r.close();
			/* Always surface the tail verbatim: the filtered view above can be
			   empty even when the core logged a plain "Started API server" or a
			   startup line, and that line is the missing clue for why 9090 did
			   or didn't come up. */
			int from = Math.max(0, all.size() - 30);
			appendLog("mihomolog-tail: 末尾 " + (all.size() - from) + " 行（共 " + all.size() + " 行）");
			for (int i = from; i < all.size(); i++)
			  appendLog("mihomolog-tail: " + all.get(i).trim());
			if (shown == 0 && all.isEmpty())
			  appendLog("mihomolog: 文件为空（核心未写日志）");
		} catch (Throwable e) {
			appendLog("mihomolog: 读取失败：" + e);
		}
	}

	/* FlClash-style real reachability WITHOUT the control API: drive a request
	   through the core's own local mixed-port (127.0.0.1:<proxyPort>) so the
	   bytes actually leave via the selected node and come back. Semantics match
	   Clash's own /delay test - getting *any* HTTP response from the proxy means
	   the node pipeline is alive (tunnel up); a 5xx only means the chosen test
	   target is blocked at the node's egress, not that the proxy is dead. So a
	   response => 可达/可用 with measured latency; only a connection-level
	   failure (no response at all) => 不可用. */
	private void testProxyConnectivity(Preferences prefs) {
		final int port = prefs.getProxyPort();
		new Thread(() -> {
			for (int i = 0; i < 40; i++) {
				if (startupAborted)
				  return;
				int[] res = proxyTestOnce(port);
				if (res[0] > 0) { // 拿到 HTTP 响应 => 节点管线通
					int code = res[0], rtt = res[1];
					if (code >= 200 && code < 400) {
						proxyTestStatus = "代理实测可用（延迟 " + rtt + "ms）";
						appendLog("proxy test: 可用（" + rtt + "ms，经 127.0.0.1:" + port
							+ " 实测 " + lastTestUrl + "）");
					} else {
						/* 节点回了响应（如 502）即说明隧道已通，只是该测试目标被
						   节点出口拦截/不可达；这仍算“可达”，但如实标注状态码。 */
						proxyTestStatus = "代理可达（延迟 " + rtt + "ms，节点响应 " + code
							+ "，测试目标可能被节点侧拦截）";
						appendLog("proxy test: 节点可达但目标返回 " + code + "（延迟 " + rtt
							+ "ms，经 127.0.0.1:" + port + "，" + lastTestUrl
							+ "，隧道应已可用）");
					}
					updateNotification();
					return;
				}
				proxyTestStatus = "代理实测不可用：" + lastTestError;
				appendLog("proxy test: 经 127.0.0.1:" + port + " 无任何 HTTP 响应（" + lastTestError + "）");
				updateNotification();
				try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
			}
			proxyTestStatus = "代理实测多次失败：节点可能失效或 TUN 未真正捕获流量";
			appendLog("proxy test: 多次尝试仍不可用，代理可能未真正连通（节点失效 / TUN 未捕获流量 / 127.0.0.1:" + port + " 未监听）");
			updateNotification();
		}).start();
	}

	/* 逐个候选 URL 经本地 mixed-port 实测。返回首个拿到 2xx 的 {code, rtt}；若所有
	   URL 都只拿到 5xx，也返回最后一个 5xx（节点已应答，证明隧道通）；仅当连接级
	   失败（拒绝/超时，代理本身不可达）才返回 {-1,-1}。 */
	private int[] proxyTestOnce(int port) {
		int last5xx = -1, lastRtt = -1;
		for (String url : PROXY_TEST_URLS) {
			lastTestUrl = url;
			ApiResult r = proxyFetch(port, url);
			if (r.code == -1) {
				lastTestError = "transport（代理不可达）";
				return new int[] { -1, -1 };
			}
			if (r.code >= 200 && r.code < 400)
			  return new int[] { r.code, (int) r.rtt };
			last5xx = r.code; lastRtt = (int) r.rtt;
		}
		if (last5xx > 0)
		  return new int[] { last5xx, lastRtt };
		return new int[] { -1, -1 };
	}

	/* Send an absolute-form GET to the local mixed-port acting as an HTTP proxy,
	   over a socket that bypasses the VPN (otherwise the app's own socket is
	   captured by the tunnel). Returns the proxy's response code and rtt. */
	static ApiResult proxyFetch(int port, String url) {
		ApiResult r = new ApiResult();
		long t0 = System.currentTimeMillis();
		java.net.Socket s = null;
		try {
			java.net.URL u = new java.net.URL(url);
			s = new java.net.Socket();
			protectLocalSocket(s);
			s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 8000);
			s.setSoTimeout(8000);
			java.io.OutputStream os = s.getOutputStream();
			StringBuilder req = new StringBuilder();
			req.append("GET ").append(url).append(" HTTP/1.1\r\n")
				.append("Host: ").append(u.getHost()).append("\r\n")
				.append("Connection: close\r\n\r\n");
			os.write(req.toString().getBytes(StandardCharsets.UTF_8));
			os.flush();
			java.io.InputStream is = s.getInputStream();
			String status = readLineBytes(is);
			if (status != null) {
				try { r.code = Integer.parseInt(status.split(" ")[1]); } catch (Throwable ignore) {}
			}
			byte[] buf = new byte[2048];
			while (is.read(buf) != -1) ; /* drain body */
		} catch (Throwable e) {
			r.code = -1;
		} finally {
			if (s != null) try { s.close(); } catch (Throwable ignore) {}
		}
		r.rtt = System.currentTimeMillis() - t0;
		return r;
	}

	/* 读空响应体，避免连接挂起/复用异常。 */
	private static void drainConn(HttpURLConnection conn, int code) {
		try { java.io.InputStream is = code < 400 ? conn.getInputStream()
				: conn.getErrorStream();
			if (is != null) while (is.read() != -1) ; } catch (Throwable ignore) { }
	}

	/* Like httpGet but returns the body even on a non-2xx status, so a failed
	   proxy-delay test still surfaces the reason (e.g. the node's connect error)
	   instead of being swallowed as "no response". */
	private String httpGetAny(String path) {
		ApiResult r = bridgeApi("GET", apiHost, MihomoConfig.API_PORT, path, null, prefs.getSecret());
		return (r.code == -1) ? null : r.body;
	}

	/* Resolve `wanted` to a real member name of `group`, tolerating the
	   trailing " (N)" de-duplication suffix the config may have added when two
	   subscriptions shipped the same node label. Returns null when the group
	   cannot be read. */
	private String resolveMember(String group, String wanted) throws IOException {
		ApiResult r = bridgeApi("GET", apiHost, MihomoConfig.API_PORT,
			"/proxies/" + encodePath(group), null, prefs.getSecret());
		if (r.code == -1 || r.body == null) return null;
		String base = wanted.replaceAll(" \\(\\d+\\)$", "");
		String fallback = null;
		try {
			JSONObject o = new JSONObject(r.body);
			JSONArray all = o.optJSONArray("all");
			if (all != null) {
				for (int i = 0; i < all.length(); i++) {
					String m = all.getString(i);
					if (m.equals(wanted))
					  return m;
					if (fallback == null
						&& m.replaceAll(" \\(\\d+\\)$", "").equals(base))
					  fallback = m;
				}
			}
		} catch (JSONException e) {
		}
		return fallback;
	}

	/* PUT /proxies/{group} {"name": proxy} to move the selector. */
	private int putSelector(String group, String proxy) throws IOException {
		ApiResult r = bridgeApi("PUT", apiHost, MihomoConfig.API_PORT,
			"/proxies/" + encodePath(group),
			"{\"name\":\"" + escapeJson(proxy) + "\"}", prefs.getSecret());
		return (r.code == -1) ? -1 : r.code;
	}

	private static String encodePath(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return s;
		}
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String readBody(HttpURLConnection c) {
		BufferedReader r;
		try {
			r = new BufferedReader(new InputStreamReader(
				c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(),
				StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
		try {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null)
			  sb.append(line);
			return sb.toString();
		} catch (IOException e) {
			return null;
		} finally {
			try {
				r.close();
			} catch (IOException e) {
			}
		}
	}

	public void stopService() {
		if (tunFd == null)
		  return;

		/* Flush the traffic counters before the tunnel goes away. */
		stopStats();
		stopEmbeddedApi();

		new Preferences(this).setEnable(false);
		QSTileService.requestUpdate(this);

		stopForeground(true);

		/* Tear the tunnel down before releasing the fd. */
		try {
			Clash.INSTANCE.stopTun();
		} catch (Throwable e) {
		}

		try {
			tunFd.close();
		} catch (IOException e) {
		}
		tunFd = null;

		stopSelf();
	}

	/* Tear the live tunnel down and bring it straight back up, as a single
	   onStartCommand action. Used when a setting that only applies at
	   establish() time changes while connected (per-app scope, global mode).
	   Sending DISCONNECT then CONNECT as two separate intents races: the
	   CONNECT can reach onStartCommand while the DISCONNECT's stopSelf() is
	   still pending, and the freshly built tunnel gets destroyed with it -
	   which is exactly why "configure per-app, and the tunnel never came
	   back up". Here we stop the old fd WITHOUT stopSelf, then rebuild. */
	private void rebuildTunnel() {
		if (tunFd != null) {
			stopStats();
			try { Clash.INSTANCE.stopTun(); } catch (Throwable ignore) { }
			try { tunFd.close(); } catch (IOException ignore) { }
			tunFd = null;
		}
		startService();
	}

	private void createNotification() {
		Notification notify = buildNotification();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFY_ID, notify);
		} else {
			startForeground(NOTIFY_ID, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		}
	}

	private Notification buildNotification() {
		/* 实时/会话/总展示隧道总流量（取自 getTotalTraffic，独立于 9090）。代理
		   专属统计依赖 /connections，控制接口不可用时为 0，会显得“流量不动”。 */
		String line = statsLine(R.string.stats_realtime,
			formatRate(txRate), formatRate(rxRate));
		String big = line + "\n" +
			statsLine(R.string.stats_session,
				formatBytes(sessionTx), formatBytes(sessionRx)) + "\n" +
			statsLine(R.string.stats_total,
				formatBytes(totalTx), formatBytes(totalRx));

		/* The node goes into the title, where a long name is simply ellipsized,
		   so the live rates below can never be pushed out of the notification.
		   statsPrefs is still null for the very first notification (it is set
		   by startStats), hence the fallback. */
		Preferences p = (statsPrefs != null) ? statsPrefs : new Preferences(this);
		String node = p.getCurrentNode();
		if (node.isEmpty())
		  node = p.hasSubscription() ? getString(R.string.node_default)
									 : getString(R.string.node_none);
		String bigText = getString(R.string.notify_node, node)
			+ (proxyTestStatus.isEmpty() ? "" : ("\n" + proxyTestStatus)) + "\n" + big;

		Intent i = new Intent(this, MainActivity.class);
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);

		Intent stop = new Intent(this, TProxyService.class).setAction(ACTION_DISCONNECT);
		PendingIntent psi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);

		return new NotificationCompat.Builder(this, NOTIFY_CHANNEL)
			.setContentTitle(node)
			.setContentText(line)
			.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
			.setSmallIcon(android.R.drawable.sym_def_app_icon)
			.setContentIntent(pi)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel,
				getString(R.string.control_disable), psi)
			.build();
	}

	private String statsLine(int labelId, String up, String down) {
		return getString(R.string.stats_line, getString(labelId), up, down);
	}

	/* Host our own loopback HTTP control API (the "clash-api"). libmihomo is a
	   JNI-first library and never binds external-controller itself, so nothing
	   would listen on the API port otherwise: a browser could not reach
	   127.0.0.1:<API_PORT>, and the app's REST paths (the latency test)
	   would fail. ClashApiServer translates REST -> in-process bridge. */
	private void startEmbeddedApi() {
		try {
			if (clashApiServer != null)
			  return;
			clashApiServer = new ClashApiServer(MihomoConfig.API_PORT, prefs);
			if (clashApiServer.start())
			  appendLog("clash-api: 内嵌控制接口已监听 127.0.0.1:" + MihomoConfig.API_PORT
				+ "（浏览器打开 http://127.0.0.1:" + MihomoConfig.API_PORT + "/ 可见）");
			else {
				clashApiServer = null;
				appendLog("clash-api: 端口 " + MihomoConfig.API_PORT
					+ " 已被占用（内核已自行监听），沿用内核的监听");
			}
		} catch (Throwable e) {
			clashApiServer = null;
			appendLog("clash-api: 内嵌服务启动失败：" + e);
		}
	}

	private void stopEmbeddedApi() {
		if (clashApiServer != null) {
			clashApiServer.stop();
			clashApiServer = null;
		}
	}

	/* Poll mihomo's traffic counters once a second. */
	private void startStats(Preferences prefs) {
		statsPrefs = prefs;
		baseTx = prefs.getTotalTx();
		baseRx = prefs.getTotalRx();
		sessionTx = sessionRx = 0;
		sessionBaseTx = sessionBaseRx = 0;
		lastTx = lastRx = 0;
		txRate = rxRate = 0;
		trafficPrimed = false;
		totalTx = baseTx;
		totalRx = baseRx;
		lastTime = SystemClock.elapsedRealtime();
		lastNotifyText = null;
		trafficSamples = 0;

		proxyBaseTx = prefs.getProxyTotalTx();
		proxyBaseRx = prefs.getProxyTotalRx();
		proxySessionTx = proxySessionRx = 0;
		lastProxyTx = lastProxyRx = 0;
		proxyRateTx = proxyRateRx = 0;
		proxyPrimed = false;
		lastProxyTime = SystemClock.elapsedRealtime();
		connSeen.clear();
		loadRecentRequests();

		prefs.setStats(totalTx, totalRx, 0, 0, 0, 0);
		prefs.setProxyStats(proxyBaseTx, proxyBaseRx, 0, 0, 0, 0);
		prefs.setAppStats(snapshotApps(this, prefs), prefs.getAppTotal());

		/* Run the sampler on a dedicated background thread: it performs a
		   synchronous httpGet() to the core, which the main thread forbids. */
		if (statsThread != null) {
			statsThread.quitSafely();
			statsThread = null;
		}
		statsThread = new HandlerThread("traffic-stats");
		statsThread.start();
		statsHandler = new Handler(statsThread.getLooper());
		statsTask = new Runnable() {
			@Override
			public void run() {
				sampleStats(true);
				if (statsHandler != null)
				  statsHandler.postDelayed(this, STATS_INTERVAL);
			}
		};
		statsHandler.postDelayed(statsTask, STATS_INTERVAL);
	}

	private void sampleStats(boolean updateNotify) {
		long now = SystemClock.elapsedRealtime();
		long dt = now - lastTime;
		lastTime = now;

		/* These must come from the since-the-core-started total. getTraffic()
		   only carries the last second's delta, so treating it as a running
		   total makes both the rate (a delta of a delta) and the session
		   figure meaningless. */
		long tx = -1, rx = -1;
		String raw = null;
		Throwable error = null;
		try {
			raw = Clash.INSTANCE.getTotalTraffic();
			tx = jsonBytes(raw, "uploadTotal", "up", "Upload");
			rx = jsonBytes(raw, "downloadTotal", "down", "Download");
		} catch (Throwable e) {
			error = e;
		}
		if (tx < 0 || rx < 0) {
			/* Fall back to the delta counter, for a bridge that has no
			   total one. */
			try {
				String s = Clash.INSTANCE.getTraffic();
				if (s != null) {
					if (tx < 0)
					  tx = jsonBytes(s, "up", "Upload", "uploadTotal");
					if (rx < 0)
					  rx = jsonBytes(s, "down", "Download", "downloadTotal");
					if (raw == null)
					  raw = s;
				}
			} catch (Throwable e) {
				if (error == null)
				  error = e;
			}
		}
		logTraffic(raw, tx, rx, error);

		if (tx >= 0 && rx >= 0) {
			if (!trafficPrimed) {
				/* First valid sample: establish baselines so the very first
				   rate is not "the whole core cumulative in one tick" (which
				   shows a bogus 9G/s spike) and the session total is not the
				   whole core cumulative. */
				lastTx = tx; lastRx = rx;
				sessionBaseTx = tx; sessionBaseRx = rx;
				trafficPrimed = true;
				sessionTx = 0; sessionRx = 0;
			} else if (rx < lastRx || tx < lastTx) {
				/* Core counter reset (config reload / core restart): re-baseline
				   instead of emitting a negative-delta spike. */
				lastTx = tx; lastRx = rx;
				sessionBaseTx = tx; sessionBaseRx = rx;
				sessionTx = 0; sessionRx = 0;
			} else {
				if (dt > 0) {
					txRate = Math.max((tx - lastTx) * 1000 / dt, 0);
					rxRate = Math.max((rx - lastRx) * 1000 / dt, 0);
				}
				lastTx = tx; lastRx = rx;
				sessionTx = Math.max(tx - sessionBaseTx, 0);
				sessionRx = Math.max(rx - sessionBaseRx, 0);
			}
			totalTx = baseTx + sessionTx;
			totalRx = baseRx + sessionRx;
		}

		if (updateNotify)
		  updateNotification();

		accumulateProxy();

		saveStats();
		saveProxyStats();
	}

	/* A stuck traffic counter is invisible from the UI - it just keeps
	   showing 0 - so record what the core actually returned. The first few
	   samples plus one a minute, otherwise the log floods. */
	private void logTraffic(String raw, long tx, long rx, Throwable error) {
		trafficSamples++;
		if (error != null) {
			if (trafficSamples <= 5)
			  appendLog("traffic: sample failed: " + error);
			return;
		}
		if (trafficSamples <= 3 || (trafficSamples % 60) == 0)
		  appendLog("traffic: raw=" + raw + " tx=" + tx + " rx=" + rx);
	}

	/* mihomo reports the running totals as uploadTotal/downloadTotal and the
	   per-second deltas as up/down; accept every spelling seen in the wild. */
	private static long jsonBytes(String json, String... keys) {
		if (json == null)
		  return -1;
		for (String key : keys) {
			try {
				Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
				Matcher m = p.matcher(json);
				if (m.find())
				  return Long.parseLong(m.group(1));
			} catch (Exception e) {
			}
		}
		return -1;
	}

	/* Pull the core's connection list and add up what actually went through a
	   node. Only the deltas are counted, so a connection that stays open keeps
	   contributing as it transfers. */
	private void accumulateProxy() {
		/* Read the connection snapshot through the in-process bridge (apiAction
		   "getConnections" == statistic.DefaultManager.Snapshot()) - it needs no
		   HTTP listener, so proxy-only traffic/conns stay accurate even when the
		   clash-api (9090) never binds. The snapshot JSON is the same shape as the
		   REST /connections body, so everything below parses unchanged. */
		String body = apiAction("getConnections", null);
		if (body == null) {
			/* Core still warming up, or bridge error. Count quietly and say once;
			   total traffic (getTotalTraffic) is unaffected. */
			connFailStreak++;
			if (connFailStreak == 5)
			  appendLog("流量统计：无法读取连接快照（in-process 桥 getConnections 不可用，"
				+ lastControllerError + "），仅“代理专属流量/连接数”归零；总流量取自 getTotalTraffic，不受影响");
			return;
		}
		controllerReady = true;
		if (connFailStreak >= 5)
		  appendLog("流量统计：/connections 已恢复");
		connFailStreak = 0;
		/* Publish this snapshot so the connections screen can show it without
		   making its own (competing) bridge call. */
		connSnapshot = body;
		try {
			JSONObject root = new JSONObject(body);
			JSONArray arr = root.optJSONArray("connections");
			if (arr == null)
			  return;

			/* Hand the live list to the main-process connections screen. It
			   cannot read the bridge / sInstance (different process), so this
			   is the only channel; it is written only when the list changed. */
			publishConnSnapshot(arr);

			Set<String> alive = new HashSet<String>();
			int proxyConnCount = 0;
			for (int i = 0; i < arr.length(); i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				String id = c.optString("id");
				if (id.isEmpty())
				  continue;
				alive.add(id);
				recordConnInfo(id, c);
				if (isDirectConnection(c))
				  continue;
				proxyConnCount++;

				long up = c.optLong("upload");
				long down = c.optLong("download");
				long[] prev = connSeen.get(id);
				if (prev == null) {
					/* First sighting: it already moved this much before we
					   noticed it. */
					proxySessionTx += up;
					proxySessionRx += down;
				} else {
					if (up > prev[0])
					  proxySessionTx += up - prev[0];
					if (down > prev[1])
					  proxySessionRx += down - prev[1];
				}
				connSeen.put(id, new long[] { up, down });
				}
				/* A connection missing from this poll has closed: record it as a recent
				request (where it went, which rule/chain, how much it moved, how long
				it lasted) so history survives past the live view. */
				if (!connInfo.isEmpty()) {
				long endMs = SystemClock.elapsedRealtime();
				Iterator<String> it = connInfo.keySet().iterator();
				boolean changed = false;
				while (it.hasNext()) {
					String cid = it.next();
					if (!alive.contains(cid)) {
						ConnInfo info = connInfo.get(cid);
						RecentRequest rr = new RecentRequest();
						rr.startMs = info.startMs;
						rr.endMs = endMs;
						rr.target = info.target;
						rr.rule = info.rule;
						rr.chain = info.chain;
						rr.process = info.process;
						rr.up = info.up;
						rr.down = info.down;
						recentRequests.add(0, rr);
						it.remove();
						changed = true;
					}
				}
				if (changed) {
					while (recentRequests.size() > MAX_RECENT_REQUESTS)
					  recentRequests.remove(recentRequests.size() - 1);
					flushRecentRequests(endMs);
				}
				}
				/* Drop finished connections so the map cannot grow forever. */
				connSeen.keySet().retainAll(alive);
			/* A zero proxy count while traffic is clearly flowing usually means
			   the filter is wrong (field name, or every connection routed
			   DIRECT) - make it visible instead of a silent blank counter. */
			if (trafficSamples <= 3 || (trafficSamples % 60) == 0)
			  appendLog("流量统计：活跃连接=" + arr.length()
				+ " 代理连接=" + proxyConnCount
				+ " session tx/rx=" + proxySessionTx + "/" + proxySessionRx);

			long now = SystemClock.elapsedRealtime();
			long dt = now - lastProxyTime;
			lastProxyTime = now;
			if (!proxyPrimed) {
				/* First valid sample primes baselines; no bogus rate spike. */
				proxyPrimed = true;
				lastProxyTx = proxySessionTx;
				lastProxyRx = proxySessionRx;
			} else if (dt > 0) {
				proxyRateTx = Math.max((proxySessionTx - lastProxyTx) * 1000 / dt, 0);
				proxyRateRx = Math.max((proxySessionRx - lastProxyRx) * 1000 / dt, 0);
				lastProxyTx = proxySessionTx;
				lastProxyRx = proxySessionRx;
			}
		} catch (Exception e) {
		}
	}

	/* Remember a connection's metadata the first time we see it, then keep its
	   last counters current; this drives the recent-requests history. */
	private void recordConnInfo(String id, JSONObject c) {
		ConnInfo info = connInfo.get(id);
		long up = c.optLong("upload");
		long down = c.optLong("download");
		if (info == null) {
			info = new ConnInfo();
			info.startMs = SystemClock.elapsedRealtime();
			info.target = connTarget(c);
			info.rule = c.optString("rule", "");
			info.chain = connChain(c);
			info.process = connProcess(c);
			info.up = up;
			info.down = down;
			connInfo.put(id, info);
		} else {
			info.up = up;
			info.down = down;
		}
	}
	private static String connTarget(JSONObject c) {
		JSONObject meta = c.optJSONObject("metadata");
		if (meta == null)
		  return "";
		String host = meta.optString("host", "");
		if (host.isEmpty())
		  host = meta.optString("destinationIP", "");
		String port = meta.optString("destinationPort", "");
		if (host.isEmpty())
		  return port;
		return port.isEmpty() ? host : host + ":" + port;
	}
	private static String connChain(JSONObject c) {
		JSONArray chains = c.optJSONArray("chains");
		if (chains == null || chains.length() == 0)
		  return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < chains.length(); i++) {
			if (i > 0)
			  sb.append(" → ");
			sb.append(chains.optString(i));
		}
		return sb.toString();
	}
	private static String connProcess(JSONObject c) {
		JSONObject meta = c.optJSONObject("metadata");
		return meta != null ? meta.optString("process", "") : "";
	}
	/* Build a compact, capped snapshot of the live connections and publish it
	   to SharedPreferences for the main-process connections screen. Only
	   rewritten when the content changed, so an idle list costs nothing. */
	private void publishConnSnapshot(JSONArray arr) {
		try {
			JSONArray out = new JSONArray();
			int n = Math.min(arr.length(), MAX_CONN_SNAPSHOT);
			for (int i = 0; i < n; i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				JSONObject o = new JSONObject();
				o.put("t", connTarget(c));
				o.put("r", connRoute(c));
				o.put("p", connProcess(c));
				o.put("u", c.optLong("upload"));
				o.put("d", c.optLong("download"));
				o.put("s", c.optString("start", ""));
				out.put(o);
			}
			String json = out.toString();
			if (json.equals(lastConnSnapshotJson))
			  return;
			lastConnSnapshotJson = json;
			if (statsPrefs != null)
			  statsPrefs.setConnSnapshot(json);
		} catch (Throwable ignore) {
		}
	}
	/* "<rule>(<payload>) · <chain>": the one-line "why + where" of a row. */
	private static String connRoute(JSONObject c) {
		StringBuilder sb = new StringBuilder();
		String rule = c.optString("rule", "");
		if (!rule.isEmpty()) {
			String payload = c.optString("rulePayload", "");
			sb.append(rule);
			if (!payload.isEmpty())
			  sb.append('(').append(payload).append(')');
		}
		String chain = connChain(c);
		if (!chain.isEmpty()) {
			if (sb.length() > 0)
			  sb.append(" · ");
			sb.append(chain);
		}
		return sb.toString();
	}

	/* Serialize the recent-requests history to Preferences, throttled so we are
	   not writing to disk on every poll. */
	private void flushRecentRequests(long now) {
		if (statsPrefs == null)
		  return;
		if (now - lastRecentFlush < 2000 && recentRequests.size() < 20)
		  return;
		lastRecentFlush = now;
		JSONArray arr = new JSONArray();
		for (RecentRequest rr : recentRequests) {
			JSONObject o = new JSONObject();
			try {
				o.put("t", rr.target);
				o.put("r", rr.rule);
				o.put("c", rr.chain);
				o.put("p", rr.process);
				o.put("u", rr.up);
				o.put("d", rr.down);
				o.put("s", rr.startMs);
				o.put("e", rr.endMs);
				arr.put(o);
			} catch (Exception e) {
			}
		}
		statsPrefs.setRecentRequests(arr.toString());
	}
	private void loadRecentRequests() {
		recentRequests.clear();
		connInfo.clear();
		if (statsPrefs == null)
		  return;
		String raw = statsPrefs.getRecentRequests();
		if (raw == null || raw.isEmpty())
		  return;
		try {
			JSONArray arr = new JSONArray(raw);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.optJSONObject(i);
				if (o == null)
				  continue;
				RecentRequest rr = new RecentRequest();
				rr.target = o.optString("t", "");
				rr.rule = o.optString("r", "");
				rr.chain = o.optString("c", "");
				rr.process = o.optString("p", "");
				rr.up = o.optLong("u", 0);
				rr.down = o.optLong("d", 0);
				rr.startMs = o.optLong("s", 0);
				rr.endMs = o.optLong("e", 0);
				recentRequests.add(rr);
			}
		} catch (Exception e) {
		}
	}

	/* An empty chain, or a DIRECT hop in it, means the request never reached a
	   proxy node. mihomo has exposed the proxy path as both "chains" (array) and
	   "chain" (single string) across builds, so accept either spelling -
	   otherwise a field-name mismatch makes every connection look direct and the
	   proxied counters stay at zero. */
	private static boolean isDirectConnection(JSONObject c) {
		JSONArray chains = c.optJSONArray("chains");
		if (chains == null) {
			String single = c.optString("chain", null);
			if (single != null && !single.isEmpty()) {
				chains = new JSONArray();
				chains.put(single);
			}
		}
		if (chains == null || chains.length() == 0)
		  return true;
		for (int i = 0; i < chains.length(); i++) {
			if ("DIRECT".equalsIgnoreCase(chains.optString(i)))
			  return true;
		}
		return false;
	}

	private String httpGet(String path) {
		ApiResult r = bridgeApi("GET", apiHost, MihomoConfig.API_PORT, path, null, prefs.getSecret());
		return (r.code >= 200 && r.code < 300) ? r.body : null;
	}

	private void saveProxyStats() {
		if (statsPrefs != null)
		  statsPrefs.setProxyStats(proxyBaseTx + proxySessionTx,
			proxyBaseRx + proxySessionRx, proxySessionTx, proxySessionRx,
			proxyRateTx, proxyRateRx);
	}

	/* Only re-post the notification when the shown text really changed. */
	private void updateNotification() {
		String line = statsLine(R.string.stats_realtime,
			formatRate(txRate), formatRate(rxRate));
		if (line.equals(lastNotifyText))
		  return;
		lastNotifyText = line;
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null)
		  nm.notify(NOTIFY_ID, buildNotification());
	}

	private void saveStats() {
		if (statsPrefs != null)
		  statsPrefs.setStats(totalTx, totalRx, sessionTx, sessionRx, txRate, rxRate);
	}

	private void stopStats() {
		if (statsHandler != null) {
			statsHandler.removeCallbacks(statsTask);
			statsHandler = null;
			statsTask = null;
		}
		if (statsThread != null) {
			statsThread.quitSafely();
			statsThread = null;
		}
		if (statsPrefs != null) {
			saveStats();
			flushRecentRequests(SystemClock.elapsedRealtime());
			accumulateApps(this, statsPrefs);
			statsPrefs = null;
		}
	}

	/* Current value of the per-app byte counters (device-wide, since boot). */
	private static String snapshotApps(Context context, Preferences prefs) {
		Map<String, long[]> map = new HashMap<String, long[]>();
		PackageManager pm = context.getPackageManager();
		for (String pkg : prefs.getRoutedApps(context)) {
			int uid = uidOf(pm, pkg);
			if (uid < 0)
			  continue;
			long tx = android.net.TrafficStats.getUidTxBytes(uid);
			long rx = android.net.TrafficStats.getUidRxBytes(uid);
			map.put(pkg, new long[] { Math.max(tx, 0), Math.max(rx, 0) });
		}
		return Preferences.formatAppStats(map);
	}

	/* Fold the usage of this session into the per-app totals. */
	private static void accumulateApps(Context context, Preferences prefs) {
		Map<String, long[]> base = Preferences.parseAppStats(prefs.getAppBase());
		Map<String, long[]> now = Preferences.parseAppStats(snapshotApps(context, prefs));
		Map<String, long[]> total = Preferences.parseAppStats(prefs.getAppTotal());

		for (Map.Entry<String, long[]> e : now.entrySet()) {
			long[] b = base.get(e.getKey());
			long[] v = e.getValue();
			long tx = b != null ? Math.max(v[0] - b[0], 0) : 0;
			long rx = b != null ? Math.max(v[1] - b[1], 0) : 0;
			long[] t = total.get(e.getKey());
			if (t == null)
			  total.put(e.getKey(), new long[] { tx, rx });
			else {
				t[0] += tx;
				t[1] += rx;
			}
		}
		prefs.setAppStats("", Preferences.formatAppStats(total));
	}

	private static int uidOf(PackageManager pm, String pkg) {
		try {
			return pm.getApplicationInfo(pkg, 0).uid;
		} catch (NameNotFoundException e) {
			return -1;
		}
	}

	public static String formatRate(long bytesPerSecond) {
		return formatBytes(bytesPerSecond) + "/s";
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024)
		  return bytes + " B";
		String[] units = { "KB", "MB", "GB", "TB" };
		double value = bytes;
		/* unit starts at -1 because the FIRST division already turns bytes into
		   KB, so the result must land on units[0]. Starting from 0 (and
		   incrementing before use) shifted EVERY value one unit up: 100 KB came
		   out as "100.0 MB", 3.7 MB as "3.7 GB", and so on. */
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		if (unit < 0)
		  unit = 0;
		return String.format(Locale.US, value < 10 ? "%.2f %s" : "%.1f %s", value, units[unit]);
	}

	// create NotificationChannel
	private void initNotificationChannel(String channelName) {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.app_name);
			/* The notification is refreshed every second, so it must be a
			   silent/low-importance channel. Importance can only be set when
			   the channel is created, hence the delete + recreate once. */
			NotificationChannel old = notificationManager.getNotificationChannel(channelName);
			if (old != null && old.getImportance() != NotificationManager.IMPORTANCE_LOW)
			  notificationManager.deleteNotificationChannel(channelName);
			if (notificationManager.getNotificationChannel(channelName) == null) {
				NotificationChannel channel = new NotificationChannel(channelName, name,
					NotificationManager.IMPORTANCE_LOW);
				channel.setSound(null, null);
				channel.enableVibration(false);
				notificationManager.createNotificationChannel(channel);
			}
		}
	}
}
