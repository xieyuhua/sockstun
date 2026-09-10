/*
 ============================================================================
 Name        : MainActivity.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Main Activity
 ============================================================================
 */

package hev.sockstun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.net.VpnService;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

public class MainActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private MaterialCardView card_status;
	private MaterialCardView card_status_dot;
	private ImageView imageview_status_icon;
	private TextView textview_status_title;
	private TextView textview_status_subtitle;
	private TextView textview_proxy_name;
	private TextView textview_proxy_detail;
	private Button button_control;
	private TextView textview_realtime;
	private TextView textview_session;
	private TextView textview_total;
	private TextView textview_apps;

	private static final long STATS_INTERVAL = 1500;
	private static final int MAX_APPS_SHOWN = 3;
	private Handler statsHandler;
	private Runnable statsTask;
	/* Last state painted on the hero card, to avoid redundant redraws. */
	private boolean statusPainted = false;
	private boolean statusConnected = false;

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

		setupBottomNav(R.id.nav_home);

		card_status = (MaterialCardView) findViewById(R.id.status_card);
		card_status_dot = (MaterialCardView) findViewById(R.id.status_dot);
		imageview_status_icon = (ImageView) findViewById(R.id.status_icon);
		textview_status_title = (TextView) findViewById(R.id.status_title);
		textview_status_subtitle = (TextView) findViewById(R.id.status_subtitle);
		textview_proxy_name = (TextView) findViewById(R.id.proxy_name);
		textview_proxy_detail = (TextView) findViewById(R.id.proxy_detail);
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
		/* The server may have been switched on the Server tab, so re-read
		   the profile before painting the proxy line. */
		prefs = new Preferences(this);
		updateUI();
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
		textview_proxy_name.setText(prefs.getProfileName());
		textview_proxy_detail.setText(prefs.getSocksAddress() + ":" + prefs.getSocksPort());
		updateControlState();
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

		/* Enable is written by the :native process, which cannot notify this
		   one through OnSharedPreferenceChangeListener. Polling it here keeps
		   the card honest, e.g. after switching servers while connected: the
		   tunnel is down for ~1s, then comes back with Enable=true. */
		updateControlState();
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
		updateStatus(prefs.getEnable());
	}

	/* Paint the hero card and the main button for the current tunnel state.
	   Called from the 1.5s stats tick too, so skip the work when nothing
	   changed (the colors and texts would be rewritten every tick). */
	private void updateStatus(boolean connected) {
		if (statusPainted && statusConnected == connected)
		  return;
		statusPainted = true;
		statusConnected = connected;

		int bg = getThemeColor(connected ?
			com.google.android.material.R.attr.colorPrimaryContainer :
			com.google.android.material.R.attr.colorSurfaceVariant);
		int fg = getThemeColor(connected ?
			com.google.android.material.R.attr.colorOnPrimaryContainer :
			com.google.android.material.R.attr.colorOnSurfaceVariant);

		card_status.setCardBackgroundColor(ColorStateList.valueOf(bg));
		textview_status_title.setTextColor(fg);
		textview_status_subtitle.setTextColor(fg);
		textview_proxy_name.setTextColor(fg);
		textview_proxy_detail.setTextColor(fg);
		textview_status_title.setText(connected ?
			R.string.status_connected : R.string.status_disconnected);
		textview_status_subtitle.setText(connected ?
			R.string.status_connected_hint : R.string.status_disconnected_hint);

		card_status_dot.setCardBackgroundColor(ColorStateList.valueOf(getThemeColor(connected ?
			com.google.android.material.R.attr.colorPrimary :
			com.google.android.material.R.attr.colorOutline)));
		imageview_status_icon.setImageResource(connected ?
			R.drawable.ic_stop : R.drawable.ic_power);
		imageview_status_icon.setColorFilter(getThemeColor(connected ?
			com.google.android.material.R.attr.colorOnPrimary :
			com.google.android.material.R.attr.colorSurface), PorterDuff.Mode.SRC_IN);

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
