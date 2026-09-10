/*
 ============================================================================
 Name        : TProxyService.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 xyz
 Description : TProxy Service (mihomo / clash.meta core)
 ============================================================================
 */

package com.tunvpn;

import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.widget.Toast;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.TunInterface;
import io.github.oviron.libmihomo.InvokeInterface;

public class TProxyService extends VpnService {
	public static final String ACTION_CONNECT = "tunvpn.CONNECT";
	public static final String ACTION_DISCONNECT = "tunvpn.DISCONNECT";
	/* Switch the selected node while the tunnel keeps running. */
	public static final String ACTION_SELECT = "tunvpn.SELECT";

	/* Traffic statistics */
	private static final int NOTIFY_ID = 1;
	private static final String NOTIFY_CHANNEL = "socks5";
	private static final long STATS_INTERVAL = 1000;

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

		/* Build the clash config from the stored subscription + TUN sections. */
		File configFile;
		try {
			configFile = MihomoConfig.build(this, prefs);
			appendLog("config: " + configFile.getAbsolutePath());
		} catch (Throwable e) {
			failStartup("生成配置失败：" + e.getMessage());
			return;
		}

		/* VPN interface. mihomo owns all routing, so we send every packet into
		   the tunnel and let its rule engine decide proxy vs direct. */
		boolean ipv4 = prefs.getIpv4();
		boolean ipv6 = prefs.getIpv6();
		String tunAddr = "198.18.0.1";
		String tunAddr6 = "fc00::1";
		int tunPrefix = 24;

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

		/* Per-app routing stays at the VpnService level: only the selected
		   apps (or everything but us) have their traffic routed into the VPN. */
		boolean disallowSelf = true;
		if (prefs.getGlobal()) {
			/* Default: all apps through the tunnel. */
		} else {
			for (String appName : prefs.getApps()) {
				try {
					builder.addAllowedApplication(appName);
					disallowSelf = false;
				} catch (NameNotFoundException e) {
				}
			}
		}
		if (disallowSelf) {
			try {
				builder.addDisallowedApplication(getApplicationContext().getPackageName());
			} catch (NameNotFoundException e) {
			}
		}
		builder.setSession("tunVPN/mihomo");
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
		String initParams = "{\"home-dir\":\"" + homeDir + "\"," +
			"\"homeDir\":\"" + homeDir + "\"}";
		String setupParams = "{\"selected-map\":" + selectedMap(prefs) + "," +
			"\"profile\":\"" + configFile.getAbsolutePath() + "\"}";
		try {
			Clash.INSTANCE.quickSetup(initParams, setupParams, new InvokeInterface() {
				@Override
				public void onResult(String result) {
					if (result == null || result.isEmpty())
					  appendLog("mihomo quickSetup OK");
					else
					  appendLog("mihomo quickSetup: " + result);
				}
			});
		} catch (Throwable e) {
			failStartup("启动内核失败：" + e.getMessage());
			return;
		}

		/* Forward mihomo's log/event stream into our log file. */
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

		/* Bring the TUN up on the VPN fd we just established. The TunInterface
		   forwards socket protection to VpnService.protect() so the core's
		   outbound traffic never loops back into the VPN. */
		String stack = "system";
		String address = (ipv4 ? tunAddr + "/" + tunPrefix : "") +
			(ipv6 ? (ipv4 ? "," : "") + tunAddr6 + "/64" : "");
		String dns = "223.5.5.5,119.29.29.29";
		try {
			Clash.INSTANCE.startTUN(tunFd.getFd(), new TunInterface() {
				@Override
				public void protect(int fd) {
					TProxyService.this.protect(fd);
				}
				@Override
				public String resolverProcess(int protocol, String source, String target, int uid) {
					return "";
				}
			}, "tunvpn", stack, address, dns, prefs.getTunnelMtu());
			appendLog("Clash.startTUN OK (fd=" + tunFd.getFd() + ")");
		} catch (Throwable e) {
			failStartup("启动 TUN 失败：" + e.getMessage());
			return;
		}

		/* Best-effort: apply the node the user picked in the subscription. */
		applySelectedNode(prefs);

		prefs.clearLastError();
		prefs.setEnable(true);
		QSTileService.requestUpdate(this);

		initNotificationChannel(NOTIFY_CHANNEL);
		createNotification();
		startStats(prefs);
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

		Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
		stopForeground(true);
		stopSelf();
	}

	/* {"<group>":"<node>"} for quickSetup's selected-map; empty when no node
	   has been picked yet. */
	private String selectedMap(Preferences prefs) {
		String sel = prefs.getSubSelected();
		if (sel == null || sel.isEmpty())
		  return "{}";
		String group = ClashParser.parseSelectorGroup(prefs.getSubRaw());
		if (group == null || group.isEmpty())
		  return "{}";
		try {
			JSONObject map = new JSONObject();
			map.put(group, sel);
			return map.toString();
		} catch (JSONException e) {
			return "{}";
		}
	}

	/* Ask mihomo to select the chosen proxy inside its proxy group. The exact
	   group name varies between subscriptions; failures are non-fatal (the
	   subscription's own default stays in effect). */
	private void applySelectedNode(Preferences prefs) {
		String sel = prefs.getSubSelected();
		if (sel == null || sel.isEmpty())
		  return;
		/* The group is named by the subscription, so look it up instead of
		   assuming it is called GLOBAL. */
		String group = ClashParser.parseSelectorGroup(prefs.getSubRaw());
		if (group == null || group.isEmpty()) {
			appendLog("selector skipped: subscription has no switchable proxy-group");
			return;
		}
		/* An action document is {"id","method","data"}; changeProxy expects
		   data to be a *string* holding {"group-name","proxy-name"}. */
		try {
			JSONObject data = new JSONObject();
			data.put("group-name", group);
			data.put("proxy-name", sel);
			JSONObject action = new JSONObject();
			action.put("id", "select");
			action.put("method", "changeProxy");
			action.put("data", data.toString());
			Clash.INSTANCE.invokeAction(action.toString(), new InvokeInterface() {
				@Override
				public void onResult(String result) {
					appendLog("selector set: " + (result == null ? "ok" : result));
				}
			});
			appendLog("selected node: " + sel + " in group " + group);
		} catch (JSONException e) {
			appendLog("selector set skipped: " + e);
		} catch (Throwable e) {
			appendLog("selector set skipped: " + e);
		}
	}

	public void stopService() {
		if (tunFd == null)
		  return;

		/* Flush the traffic counters before the tunnel goes away. */
		stopStats();

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

		/* The node goes into the title, where a long name is simply ellipsized,
		   so the live rates below can never be pushed out of the notification.
		   statsPrefs is still null for the very first notification (it is set
		   by startStats), hence the fallback. */
		Preferences p = (statsPrefs != null) ? statsPrefs : new Preferences(this);
		String node = p.getCurrentNode();
		if (node.isEmpty())
		  node = p.hasSubscription() ? getString(R.string.node_default)
									 : getString(R.string.node_none);
		String bigText = getString(R.string.notify_node, node) + "\n" + big;

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

	/* Poll mihomo's traffic counters once a second. */
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

		long tx = -1, rx = -1;
		try {
			String s = Clash.INSTANCE.getTraffic();
			if (s != null) {
				tx = jsonLong(s, "up");
				if (tx < 0) tx = jsonLong(s, "Upload");
				rx = jsonLong(s, "down");
				if (rx < 0) rx = jsonLong(s, "Download");
			}
		} catch (Throwable e) {
		}
		if (tx >= 0 && rx >= 0) {
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

	private static long jsonLong(String json, String key) {
		try {
			Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
			Matcher m = p.matcher(json);
			if (m.find())
			  return Long.parseLong(m.group(1));
		} catch (Exception e) {
		}
		return -1;
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
		int unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
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
