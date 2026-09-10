/*
 ============================================================================
 Name        : MainActivity.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Main Activity
 ============================================================================
 */

package com.tunvpn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.net.VpnService;
import java.net.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;


import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.navigation.NavigationBarView;

public class MainActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private BottomNavigationView bottomNav;
	private MaterialCardView card_status;
	private MaterialCardView card_status_dot;
	private ImageView imageview_status_icon;
	private TextView textview_status_title;
	private TextView textview_status_subtitle;
	private TextView textview_status_node;
	private TextView textview_status_ip;
	private Button button_control;
	private TextView textview_realtime;
	private TextView textview_session;
	private TextView textview_total;
	private TextView textview_apps;

	private static final long STATS_INTERVAL = 1500;
	private static final int MAX_APPS_SHOWN = 3;
	/* Local HTTP port of the core, and how often to refresh the public IP. */
	private static final int PROXY_PORT = 7890;
	private static final long IP_QUERY_INTERVAL = 60000;
	private Handler statsHandler;
	private Runnable statsTask;
	private final Handler ui = new Handler(Looper.getMainLooper());
	private long lastIpQuery = 0;
	/* Last failure already surfaced as a Toast, so the 1.5s tick stays quiet. */
	private String lastShownError = "";

	/* Refresh the control state when the tunnel is toggled elsewhere
	   (e.g. from the Quick Settings tile) while this screen is visible. */
	private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
		new SharedPreferences.OnSharedPreferenceChangeListener() {
			@Override
			public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
				if (Preferences.ENABLE.equals(key))
				  updateControlState();
			}
		};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.main);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		bottomNav = (BottomNavigationView) findViewById(R.id.bottom_nav);
		bottomNav.setSelectedItemId(R.id.nav_home);
		bottomNav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
			@Override
			public boolean onNavigationItemSelected(android.view.MenuItem item) {
				int id = item.getItemId();
				if (id == R.id.nav_home)
				  return true;
				Intent intent = null;
				if (id == R.id.nav_subscribe)
				  intent = new Intent(MainActivity.this, SubscribeActivity.class);
				else if (id == R.id.nav_server)
				  intent = new Intent(MainActivity.this, ServerListActivity.class);
				else if (id == R.id.nav_rules)
				  intent = new Intent(MainActivity.this, RulesHubActivity.class);
				else if (id == R.id.nav_settings)
				  intent = new Intent(MainActivity.this, SettingsActivity.class);
				if (intent != null) {
					startActivity(intent);
					/* Those entries open their own screen, so keep "home"
					   highlighted instead of leaving the tab selected. */
					bottomNav.setSelectedItemId(R.id.nav_home);
				}
				return true;
			}
		});

		card_status = (MaterialCardView) findViewById(R.id.status_card);
		card_status_dot = (MaterialCardView) findViewById(R.id.status_dot);
		imageview_status_icon = (ImageView) findViewById(R.id.status_icon);
		textview_status_title = (TextView) findViewById(R.id.status_title);
		textview_status_subtitle = (TextView) findViewById(R.id.status_subtitle);
		textview_status_node = (TextView) findViewById(R.id.status_node);
		textview_status_ip = (TextView) findViewById(R.id.status_ip);
		button_control = (Button) findViewById(R.id.control);
		textview_realtime = (TextView) findViewById(R.id.stats_realtime);
		textview_session = (TextView) findViewById(R.id.stats_session);
		textview_total = (TextView) findViewById(R.id.stats_total);
		textview_apps = (TextView) findViewById(R.id.stats_apps);
		((Button) findViewById(R.id.traffic_reset)).setOnClickListener(this);

		button_control.setOnClickListener(this);
		updateUI();

		statsHandler = new Handler(Looper.getMainLooper());
		statsTask = new Runnable() {
			@Override
			public void run() {
				refreshTraffic();
				statsHandler.postDelayed(this, STATS_INTERVAL);
			}
		};

		/* Android 13+ hides the ongoing traffic notification unless the
		   POST_NOTIFICATIONS permission has been granted. */
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
				android.content.pm.PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1);
		}

		/* Request VPN permission */
		Intent intent = VpnService.prepare(MainActivity.this);
		if (intent != null)
		  startActivityForResult(intent, 0);
		else
		  onActivityResult(0, RESULT_OK, null);
	}

	@Override
	protected void onStart() {
		super.onStart();
		prefs.registerOnChange(prefsListener);
		refreshTraffic();
		statsHandler.postDelayed(statsTask, STATS_INTERVAL);
	}

	@Override
	protected void onResume() {
		super.onResume();
		updateControlState();
		refreshTraffic();
	}

	@Override
	protected void onStop() {
		super.onStop();
		prefs.unregisterOnChange(prefsListener);
		statsHandler.removeCallbacks(statsTask);
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		if ((result == RESULT_OK) && prefs.getEnable()) {
			Intent intent = new Intent(this, TProxyService.class);
			startService(intent.setAction(TProxyService.ACTION_CONNECT));
		}
	}

	@Override
	public void onClick(View view) {
		if (view.getId() == R.id.traffic_reset) {
			prefs.resetStats();
			refreshTraffic();
			return;
		}
		if (view == button_control) {
			boolean isEnable = prefs.getEnable();
			/* A fresh attempt clears the previous failure message. */
			if (!isEnable)
			  prefs.clearLastError();
			prefs.setEnable(!isEnable);
			updateUI();
			Intent intent = new Intent(this, TProxyService.class);
			if (isEnable)
			  startService(intent.setAction(TProxyService.ACTION_DISCONNECT));
			else
			  startService(intent.setAction(TProxyService.ACTION_CONNECT));
			QSTileService.requestUpdate(this);
		}
	}

	private void updateUI() {
		updateControlState();
		updateNode();
	}

	/* Traffic counters: the tunnel totals are exact, the per-app numbers come
	   from the system counters and include traffic outside the tunnel. */
	private void refreshTraffic() {
		prefs = new Preferences(this);
		textview_realtime.setText(statsLine(R.string.stats_realtime,
			TProxyService.formatRate(prefs.getRateTx()),
			TProxyService.formatRate(prefs.getRateRx())));
		textview_session.setText(statsLine(R.string.stats_session,
			TProxyService.formatBytes(prefs.getSessionTx()),
			TProxyService.formatBytes(prefs.getSessionRx())));
		textview_total.setText(statsLine(R.string.stats_total,
			TProxyService.formatBytes(prefs.getTotalTx()),
			TProxyService.formatBytes(prefs.getTotalRx())));
		textview_apps.setText(appSummary(Preferences.parseAppStats(prefs.getAppTotal())));
		updateNode();
		updateExternalIp();

		/* Enable and LastError are written by the :native process, which cannot
		   notify this one through OnSharedPreferenceChangeListener - so poll.
		   Without this the card would keep showing "connected" after a failed
		   start, and the failure would only become visible on the next resume. */
		updateControlState();
	}

	/* Ask an echo service through the core's local HTTP port, so the answer
	   is the address the proxy exposes rather than the device's own one. */
	private void updateExternalIp() {
		if (!prefs.getEnable()) {
			textview_status_ip.setText(getString(R.string.ip_unknown));
			return;
		}
		long now = SystemClock.elapsedRealtime();
		if (now - lastIpQuery < IP_QUERY_INTERVAL)
		  return;
		lastIpQuery = now;
		new Thread(new Runnable() {
			@Override
			public void run() {
				final String ip = queryExternalIp();
				ui.post(new Runnable() {
					@Override
					public void run() {
						textview_status_ip.setText(ip == null || ip.isEmpty()
							? getString(R.string.ip_unknown)
							: getString(R.string.ip_label, ip));
					}
				});
			}
		}).start();
	}

	private String queryExternalIp() {
		HttpURLConnection conn = null;
		try {
			Proxy proxy = new Proxy(Proxy.Type.HTTP,
				new InetSocketAddress("127.0.0.1", PROXY_PORT));
			conn = (HttpURLConnection) new URL("http://api.ipify.org").openConnection(proxy);
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(conn.getInputStream()));
			String line = reader.readLine();
			reader.close();
			return line == null ? null : line.trim();
		} catch (Exception e) {
			return null;
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}

	/* Which node the tunnel is using: the picked subscription node, the manual
	   SOCKS5 upstream, or the subscription's own default. */
	private void updateNode() {
		String node = prefs.getCurrentNode();
		String label = node.isEmpty()
			? getString(prefs.hasSubscription() ? R.string.node_default : R.string.node_none)
			: node;
		textview_status_node.setText(getString(R.string.node_current, label));
	}

	private String statsLine(int labelId, String up, String down) {
		return getString(R.string.stats_line, getString(labelId), up, down);
	}

	private String appSummary(Map<String, long[]> totals) {
		if (totals.isEmpty())
		  return "";

		List<Map.Entry<String, long[]>> entries =
			new ArrayList<Map.Entry<String, long[]>>(totals.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, long[]>>() {
			@Override
			public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
				long ca = a.getValue()[0] + a.getValue()[1];
				long cb = b.getValue()[0] + b.getValue()[1];
				if (ca != cb)
				  return ca < cb ? 1 : -1;
				return 0;
			}
		});

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < entries.size() && i < MAX_APPS_SHOWN; i++) {
			if (sb.length() > 0)
			  sb.append("  ·  ");
			long[] v = entries.get(i).getValue();
			sb.append(labelOf(entries.get(i).getKey())).append(' ')
			  .append(TProxyService.formatBytes(v[0] + v[1]));
		}
		return sb.toString();
	}

	private String labelOf(String pkg) {
		try {
			return getPackageManager().getApplicationInfo(pkg, 0).loadLabel(getPackageManager()).toString();
		} catch (Exception e) {
			return pkg;
		}
	}

	private void updateControlState() {
		updateStatus();
	}

	/* Paint the hero card and the main button.
	   Three states, not two: a start attempt can fail *after* Enable was
	   already flipped to true (MainActivity does that before asking the service
	   to start), so "not connected" alone cannot describe what happened. When
	   the service reports a failure it writes LastError + Enable=false, and we
	   surface the reason here instead of silently going back to "disconnected". */
	private void updateStatus() {
		final boolean connected = prefs.getEnable();
		final String error = prefs.getLastError();
		final boolean failed = !connected && !error.isEmpty();

		/* Refresh the public IP right after the tunnel comes up. */
		if (connected)
		  lastIpQuery = 0;

		int bg;
		int fg;
		if (connected) {
			bg = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer);
			fg = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer);
		} else if (failed) {
			bg = getThemeColor(com.google.android.material.R.attr.colorErrorContainer);
			fg = getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer);
		} else {
			bg = getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant);
			fg = getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant);
		}

		card_status.setCardBackgroundColor(ColorStateList.valueOf(bg));
		textview_status_title.setTextColor(fg);
		textview_status_subtitle.setTextColor(fg);
		textview_status_node.setTextColor(fg);

		if (connected) {
			textview_status_title.setText(R.string.status_connected);
			textview_status_subtitle.setText(R.string.status_connected_hint);
		} else if (failed) {
			textview_status_title.setText(R.string.status_failed);
			textview_status_subtitle.setText(error);
		} else {
			textview_status_title.setText(R.string.status_disconnected);
			textview_status_subtitle.setText(R.string.status_disconnected_hint);
		}

		/* Tell the user once per distinct failure, not on every 1.5s tick. */
		if (failed && !error.equals(lastShownError)) {
			lastShownError = error;
			Toast.makeText(this, getString(R.string.status_failed) + "：" + error,
				Toast.LENGTH_LONG).show();
		} else if (!failed) {
			lastShownError = "";
		}

		card_status_dot.setCardBackgroundColor(ColorStateList.valueOf(getThemeColor(
			connected ? com.google.android.material.R.attr.colorPrimary
				: failed ? com.google.android.material.R.attr.colorError
				: com.google.android.material.R.attr.colorOutline)));
		imageview_status_icon.setImageResource(connected ?
			R.drawable.ic_stop : R.drawable.ic_power);
		imageview_status_icon.setColorFilter(getThemeColor(connected
			? com.google.android.material.R.attr.colorOnPrimary
			: failed ? com.google.android.material.R.attr.colorOnError
			: com.google.android.material.R.attr.colorSurface), PorterDuff.Mode.SRC_IN);

		MaterialButton button = (MaterialButton) button_control;
		int buttonFg = getThemeColor(connected ?
			com.google.android.material.R.attr.colorOnErrorContainer :
			com.google.android.material.R.attr.colorOnPrimary);
		button.setText(connected ? R.string.control_disable : R.string.control_enable);
		button.setIconResource(connected ? R.drawable.ic_stop : R.drawable.ic_power);
		button.setBackgroundTintList(ColorStateList.valueOf(getThemeColor(connected ?
			com.google.android.material.R.attr.colorErrorContainer :
			com.google.android.material.R.attr.colorPrimary)));
		button.setTextColor(ColorStateList.valueOf(buttonFg));
		button.setIconTint(ColorStateList.valueOf(buttonFg));
	}

	private int getThemeColor(int attr) {
		return MaterialColors.getColor(this, attr, 0);
	}
}
