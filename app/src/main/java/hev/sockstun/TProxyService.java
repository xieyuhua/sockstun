/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service
 ============================================================================
 */

package hev.sockstun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.net.TrafficStats;
import android.net.IpPrefix;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;

import androidx.core.app.NotificationCompat;

import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.Method;

import java.io.FileDescriptor;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.widget.Toast;

public class TProxyService extends VpnService {
	/* These signatures must match exactly what the prebuilt
	   libhev-socks5-tunnel.so registers in JNI_OnLoad:
	     class  hev/sockstun/TProxyService
	     TProxyStartService (Ljava/lang/String;I)V   -> void
	     TProxyStopService  ()V                      -> void
	     TProxyGetStats     ()[J                     -> long[]
	   A return-type mismatch (e.g. boolean instead of void) makes
	   RegisterNatives() fail silently, and the call then dies with
	   UnsatisfiedLinkError "No implementation found for ...". */
	private static native void TProxyStartService(String config_path, int fd);
	private static native void TProxyStopService();
	private static native long[] TProxyGetStats();

	public static final String ACTION_CONNECT = "hev.sockstun.CONNECT";
	public static final String ACTION_DISCONNECT = "hev.sockstun.DISCONNECT";

	/* Traffic statistics */
	private static final int NOTIFY_ID = 1;
	private static final String NOTIFY_CHANNEL = "socks5";
	private static final long STATS_INTERVAL = 1000;

	/* Layout of the long[] returned by TProxyGetStats():
	   [0] = bytes sent (tx / up), [1] = bytes received (rx / down).
	   Swap these two constants if a future build of the native library
	   reports them the other way round. */
	private static final int STATS_TX = 0;
	private static final int STATS_RX = 1;

	private Handler statsHandler = null;
	private Runnable statsTask = null;
	private Preferences statsPrefs = null;
	private long lastTx, lastRx, lastTime;
	private long sessionTx, sessionRx;
	private long baseTx, baseRx, totalTx, totalRx;
	private long txRate, rxRate;
	private String lastNotifyText = null;

	/* Append a line to the log file directly (bypassing the fd-1/2
	   redirection), so startup diagnostics are always captured even if the
	   native library fails to load or redirect. */
	private void appendLog(String s) {
		try {
			File f = new File(getCacheDir(), "tproxy.log");
			FileOutputStream fos = new FileOutputStream(f, true);
			String ts = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " ";
			fos.write((ts + s + "\n").getBytes("UTF-8"));
			fos.close();
		} catch (Exception e) {
		}
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

	private ParcelFileDescriptor tunFd = null;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			stopService();
			return START_NOT_STICKY;
		}
		startService();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void onRevoke() {
		stopService();
		super.onRevoke();
	}

	public void startService() {
		if (tunFd != null)
		  return;

		Preferences prefs = new Preferences(this);

		/* Logging */
		File tproxy_log = new File(getCacheDir(), "tproxy.log");
		if (tproxy_log.exists())
		  tproxy_log.delete();
		appendLog("=== SocksTun start (pid " + android.os.Process.myPid() +
			" logging=" + prefs.getLogEnabled() + ") ===");
		if (prefs.getLogEnabled()) {
			redirectStdioToLog(tproxy_log);
		}

		/* Load the native tunnel library (JNI). If it fails to load, or the
		   .so has no JNI entry points, record the reason so it shows up in the
		   log viewer instead of failing silently with no proxy and no logs. */
		try {
			System.loadLibrary("hev-socks5-tunnel");
			appendLog("library libhev-socks5-tunnel.so loaded OK");
		} catch (Throwable e) {
			appendLog("FATAL: failed to load libhev-socks5-tunnel.so: " + e);
			Toast.makeText(this, "隧道库加载失败，请查看日志", Toast.LENGTH_LONG).show();
			stopSelf();
			return;
		}

		/* VPN */
		String session = new String();
		VpnService.Builder builder = new VpnService.Builder();
		boolean ipv4 = prefs.getIpv4();
		boolean ipv6 = prefs.getIpv6();
		builder.setBlocking(false);
		builder.setMtu(prefs.getTunnelMtu());
		if (ipv4) {
			String addr = prefs.getTunnelIpv4Address();
			int prefix = prefs.getTunnelIpv4Prefix();
			String dns = prefs.getDnsIpv4();
			builder.addAddress(addr, prefix);
			if (!prefs.getRemoteDns() && !dns.isEmpty())
			  builder.addDnsServer(dns);
			session += "IPv4";
		}
		if (ipv6) {
			String addr = prefs.getTunnelIpv6Address();
			int prefix = prefs.getTunnelIpv6Prefix();
			String dns = prefs.getDnsIpv6();
			builder.addAddress(addr, prefix);
			if (!prefs.getRemoteDns() && !dns.isEmpty())
			  builder.addDnsServer(dns);
			if (!session.isEmpty())
			  session += " + ";
			session += "IPv6";
		}
		if (prefs.getRemoteDns()) {
			builder.addDnsServer(prefs.getMappedDns());
		}
		if (applyRoutes(builder, prefs, ipv4, ipv6))
		  session += "/Rules";

		boolean disallowSelf = true;
		if (prefs.getGlobal()) {
			session += "/Global";
		} else {
			for (String appName : prefs.getApps()) {
				try {
					builder.addAllowedApplication(appName);
					disallowSelf = false;
				} catch (NameNotFoundException e) {
				}
			}
			session += "/per-App";
		}
		if (disallowSelf) {
			String selfName = getApplicationContext().getPackageName();
			try {
				builder.addDisallowedApplication(selfName);
			} catch (NameNotFoundException e) {
			}
		}
		builder.setSession(session);
		tunFd = builder.establish();
		if (tunFd == null) {
			stopSelf();
			return;
		}

		/* TProxy */
		File tproxy_file = new File(getCacheDir(), "tproxy.conf");
		try {
			tproxy_file.createNewFile();
			FileOutputStream fos = new FileOutputStream(tproxy_file, false);

			String tproxy_conf = "misc:\n" +
				"  task-stack-size: " + prefs.getTaskStackSize() + "\n" +
				"tunnel:\n" +
				"  mtu: " + prefs.getTunnelMtu() + "\n" +
				"  icmp: 'reply'\n";

			tproxy_conf += "socks5:\n" +
				"  port: " + prefs.getSocksPort() + "\n" +
				"  address: '" + prefs.getSocksAddress() + "'\n" +
				"  udp: '" + (prefs.getUdpInTcp() ? "tcp" : "udp") + "'\n";

			if (!prefs.getSocksUdpAddress().isEmpty()) {
				tproxy_conf += "  udp-address: '" + prefs.getSocksUdpAddress() + "'\n";
			}

			if (!prefs.getSocksUsername().isEmpty() &&
				!prefs.getSocksPassword().isEmpty()) {
				tproxy_conf += "  username: '" + prefs.getSocksUsername() + "'\n";
				tproxy_conf += "  password: '" + prefs.getSocksPassword() + "'\n";
			}

			if (prefs.getRemoteDns()) {
				tproxy_conf += "mapdns:\n" +
					"  address: " + prefs.getMappedDns() + "\n" +
					"  port: 53\n" +
					"  network: 240.0.0.0\n" +
					"  netmask: 240.0.0.0\n" +
					"  cache-size: 10000\n";
			}

			fos.write(tproxy_conf.getBytes());
			fos.close();
		} catch (IOException e) {
			return;
		}
		try {
			TProxyStartService(tproxy_file.getAbsolutePath(), tunFd.getFd());
			appendLog("TProxyStartService OK");
		} catch (Throwable e) {
			appendLog("FATAL: TProxyStartService failed: " + e);
			Toast.makeText(this, "启动隧道失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
			stopSelf();
			return;
		}
		prefs.setEnable(true);
		QSTileService.requestUpdate(this);

		initNotificationChannel(NOTIFY_CHANNEL);
		createNotification();
		startStats(prefs);
	}

	public void stopService() {
		if (tunFd == null)
		  return;

		/* Flush the traffic counters before the tunnel goes away. */
		stopStats();

		new Preferences(this).setEnable(false);
		QSTileService.requestUpdate(this);

		stopForeground(true);

		/* TProxy */
		TProxyStopService();

		/* VPN */
		try {
			tunFd.close();
		} catch (IOException e) {
		}
		tunFd = null;

		System.exit(0);
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
		String line = statsLine(R.string.stats_realtime, formatRate(txRate), formatRate(rxRate));
		String big = line + "\n" +
			statsLine(R.string.stats_session, formatBytes(sessionTx), formatBytes(sessionRx)) + "\n" +
			statsLine(R.string.stats_total, formatBytes(totalTx), formatBytes(totalRx));

		Intent i = new Intent(this, MainActivity.class);
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);

		Intent stop = new Intent(this, TProxyService.class).setAction(ACTION_DISCONNECT);
		PendingIntent psi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);

		return new NotificationCompat.Builder(this, NOTIFY_CHANNEL)
			.setContentTitle(getString(R.string.app_name))
			.setContentText(line)
			.setStyle(new NotificationCompat.BigTextStyle().bigText(big))
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

	/* Sample TProxyGetStats() once a second: the notification shows the live
	   rate, the usage of this session and the usage of all sessions. */
	private void startStats(Preferences prefs) {
		statsPrefs = prefs;
		baseTx = prefs.getTotalTx();
		baseRx = prefs.getTotalRx();
		sessionTx = sessionRx = 0;
		lastTx = lastRx = 0;
		txRate = rxRate = 0;
		totalTx = baseTx;
		totalRx = baseRx;
		lastTime = SystemClock.elapsedRealtime();
		lastNotifyText = null;

		/* Baseline of the per-app counters, so the traffic screen can show
		   what each app used during this session. */
		prefs.setStats(totalTx, totalRx, 0, 0, 0, 0);
		prefs.setAppStats(snapshotApps(this, prefs), prefs.getAppTotal());

		statsHandler = new Handler(Looper.getMainLooper());
		statsTask = new Runnable() {
			@Override
			public void run() {
				sampleStats(true);
				statsHandler.postDelayed(this, STATS_INTERVAL);
			}
		};
		statsHandler.postDelayed(statsTask, STATS_INTERVAL);
	}

	private void sampleStats(boolean updateNotify) {
		long now = SystemClock.elapsedRealtime();
		long dt = now - lastTime;
		lastTime = now;

		long[] stats = null;
		try {
			stats = TProxyGetStats();
		} catch (Throwable e) {
		}
		if (stats != null && stats.length > STATS_RX) {
			long tx = Math.max(stats[STATS_TX], 0);
			long rx = Math.max(stats[STATS_RX], 0);
			if (dt > 0) {
				txRate = Math.max((tx - lastTx) * 1000 / dt, 0);
				rxRate = Math.max((rx - lastRx) * 1000 / dt, 0);
			}
			sessionTx = tx;
			sessionRx = rx;
			lastTx = tx;
			lastRx = rx;
		}
		totalTx = baseTx + sessionTx;
		totalRx = baseRx + sessionRx;

		if (updateNotify)
		  updateNotification();

		saveStats();
	}

	/* Only re-post the notification when the shown text really changed. */
	private void updateNotification() {
		String line = statsLine(R.string.stats_realtime, formatRate(txRate), formatRate(rxRate));
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
		if (statsPrefs != null) {
			sampleStats(false);
			saveStats();
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
			long tx = TrafficStats.getUidTxBytes(uid);
			long rx = TrafficStats.getUidRxBytes(uid);
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
		int unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.US, value < 10 ? "%.2f %s" : "%.1f %s", value, units[unit]);
	}

	/* ------------------------------------------------------------------
	   Routing rules

	   The native engine is a plain tun2socks: it has no rule engine, so
	   "proxy vs direct" is decided by the VPN routing table:
	     * default proxy  -> route everything, excludeRoute() the direct
	                         targets (needs Android 13 / API 33)
	     * default direct -> only addRoute() the proxy targets, everything
	                         else never enters the tunnel (all versions)
	   Domain rules are resolved to addresses when the tunnel is started.
	   ------------------------------------------------------------------ */
	private static class Route {
		public String addr;
		public int prefix;
		public boolean v6;

		public Route(String addr, int prefix, boolean v6) {
			this.addr = addr;
			this.prefix = prefix;
			this.v6 = v6;
		}
	}

	private void addRoute(VpnService.Builder builder, String addr, int prefix) {
		try {
			builder.addRoute(addr, prefix);
		} catch (IllegalArgumentException e) {
			appendLog("invalid route " + addr + "/" + prefix);
		}
	}

	private void excludeRoute(VpnService.Builder builder, Route route) {
		try {
			builder.excludeRoute(new IpPrefix(InetAddress.getByName(route.addr), route.prefix));
		} catch (Exception e) {
			appendLog("invalid excluded route " + route.addr + "/" + route.prefix);
		}
	}

	private boolean applyRoutes(VpnService.Builder builder, Preferences prefs,
			boolean ipv4, boolean ipv6) {
		List<Preferences.Rule> rules = prefs.getRules();
		if (rules.isEmpty()) {
			if (ipv4)
			  addRoute(builder, "0.0.0.0", 0);
			if (ipv6)
			  addRoute(builder, "::", 0);
			return false;
		}

		Map<String, List<String>> hosts = resolveHosts(rules);
		List<Route> proxy = new ArrayList<Route>();
		List<Route> direct = new ArrayList<Route>();

		for (Preferences.Rule rule : rules) {
			List<Route> target = rule.proxy ? proxy : direct;
			if (rule.type == Preferences.Rule.TYPE_DOMAIN) {
				List<String> addrs = hosts.get(rule.value);
				if (addrs == null)
				  continue;
				for (String addr : addrs)
				  addTarget(target, addr, -1);
			} else if (rule.type == Preferences.Rule.TYPE_CIDR) {
				int slash = rule.value.lastIndexOf('/');
				if (slash > 0) {
					try {
						addTarget(target, rule.value.substring(0, slash),
							Integer.parseInt(rule.value.substring(slash + 1)));
						} catch (NumberFormatException e) {
						}
						} else {
						addTarget(target, rule.value, -1);
						}
						} else {
						addTarget(target, rule.value, -1);
						}
		}

		boolean defaultProxy = prefs.getRulesDefaultProxy();
		boolean canExclude = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
		boolean excluded = false;
		int added = 0;

		if (defaultProxy && !direct.isEmpty() && canExclude) {
			if (ipv4) {
				addRoute(builder, "0.0.0.0", 0);
				added++;
			}
			if (ipv6) {
				addRoute(builder, "::", 0);
				added++;
			}
			for (Route route : direct) {
				if ((route.v6 && !ipv6) || (!route.v6 && !ipv4))
				  continue;
				excludeRoute(builder, route);
			}
			excluded = true;
		} else {
			if (defaultProxy && !direct.isEmpty())
			  appendLog("WARN: direct rules need Android 13+, they were ignored");
			for (Route route : proxy) {
				if ((route.v6 && !ipv6) || (!route.v6 && !ipv4))
				  continue;
				addRoute(builder, route.addr, route.prefix);
				added++;
			}
			if (added == 0) {
				/* Nothing left to route: fall back to the default routes so
				   the tunnel still comes up. */
				if (ipv4) {
					addRoute(builder, "0.0.0.0", 0);
					added++;
				}
				if (ipv6) {
					addRoute(builder, "::", 0);
					added++;
				}
			}
		}

		appendLog("routes: " + proxy.size() + " proxy, " + direct.size() +
			" direct, added " + added + ", excluded " + excluded);
		return true;
	}

	private void addTarget(List<Route> routes, String addr, int prefix) {
		InetAddress ia;
		try {
			ia = InetAddress.getByName(addr);
		} catch (Exception e) {
			appendLog("invalid rule target: " + addr);
			return;
		}
		boolean v6 = ia instanceof Inet6Address;
		int length = prefix < 0 ? (v6 ? 128 : 32) : prefix;
		routes.add(new Route(addr, length, v6));
	}

	

private Map<String, List<String>> resolveHosts(List<Preferences.Rule> rules) {
		final List<String> hosts = new ArrayList<String>();
		final Map<String, List<String>> result = new HashMap<String, List<String>>();

		for (Preferences.Rule rule : rules) {
			if (rule.type == Preferences.Rule.TYPE_DOMAIN && !hosts.contains(rule.value))
			  hosts.add(rule.value);
		}
		if (hosts.isEmpty())
		  return result;

		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				for (String host : hosts) {
					try {
						InetAddress[] addrs = InetAddress.getAllByName(host);
						List<String> list = new ArrayList<String>();
						for (InetAddress addr : addrs)
						  list.add(addr.getHostAddress());
						synchronized (result) {
							result.put(host, list);
						}
					} catch (Exception e) {
						appendLog("cannot resolve " + host);
					}
				}
			}
		});
		thread.setDaemon(true);
		thread.start();
		try {
			thread.join(10000);
		} catch (InterruptedException e) {
		}
		synchronized (result) {
			return new HashMap<String, List<String>>(result);
		}
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
