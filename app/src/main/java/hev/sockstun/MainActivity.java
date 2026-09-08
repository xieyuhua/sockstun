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
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.net.VpnService;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.navigation.NavigationView;

public class MainActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private DrawerLayout drawer;
	private NavigationView navView;
	private MaterialCardView card_status;
	private MaterialCardView card_status_dot;
	private ImageView imageview_status_icon;
	private TextView textview_status_title;
	private TextView textview_status_subtitle;
	private TextView textview_profile_name;
	private Button button_control;
	private Button button_profile_prev;
	private Button button_profile_next;
	private Button button_profile_menu;
	private TextView textview_realtime;
	private TextView textview_session;
	private TextView textview_total;
	private TextView textview_apps;

	private static final long STATS_INTERVAL = 1500;
	private static final int MAX_APPS_SHOWN = 3;
	private Handler statsHandler;
	private Runnable statsTask;

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

		drawer = (DrawerLayout) findViewById(R.id.drawer);
		navView = (NavigationView) findViewById(R.id.nav);
		ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
				this, drawer, toolbar, R.string.nav_open, R.string.nav_close);
		drawer.addDrawerListener(toggle);
		toggle.syncState();

		navView.setCheckedItem(R.id.nav_home);
		navView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
			@Override
			public boolean onNavigationItemSelected(android.view.MenuItem item) {
				int id = item.getItemId();
				if (id != R.id.nav_home) {
					Intent intent = null;
					if (id == R.id.nav_server)
					  intent = new Intent(MainActivity.this, ServerActivity.class);
					else if (id == R.id.nav_dns)
					  intent = new Intent(MainActivity.this, DnsActivity.class);
					else if (id == R.id.nav_routing)
					  intent = new Intent(MainActivity.this, RoutingActivity.class);
					else if (id == R.id.nav_apps)
					  intent = new Intent(MainActivity.this, AppListActivity.class);
					else if (id == R.id.nav_log)
					  intent = new Intent(MainActivity.this, LogActivity.class);
					else if (id == R.id.nav_rules)
					  intent = new Intent(MainActivity.this, RulesActivity.class);
					else if (id == R.id.nav_conn)
					  intent = new Intent(MainActivity.this, ConnActivity.class);
					else if (id == R.id.nav_theme) {
					  showThemeDialog();
					  drawer.closeDrawers();
					  return true;
					}
					if (intent != null)
					  startActivity(intent);
				}
				drawer.closeDrawers();
				return true;
			}
		});

	}

	/* Theme picker: choose one of the bundled palettes, then recreate
	   so every activity picks up the new theme on next creation. */
	private void showThemeDialog() {
		final int current = prefs.getTheme();
		final String[] names = new String[ThemeManager.count()];
		for (int i = 0; i < names.length; i++)
			names[i] = getString(ThemeManager.nameRes(i));

		new AlertDialog.Builder(this)
			.setTitle(R.string.theme)
			.setSingleChoiceItems(names, current, null)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
					if (sel >= 0 && sel != current) {
						prefs.setTheme(sel);
						recreate();
					}
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

		card_status = (MaterialCardView) findViewById(R.id.status_card);
		card_status_dot = (MaterialCardView) findViewById(R.id.status_dot);
		imageview_status_icon = (ImageView) findViewById(R.id.status_icon);
		textview_status_title = (TextView) findViewById(R.id.status_title);
		textview_status_subtitle = (TextView) findViewById(R.id.status_subtitle);
		textview_profile_name = (TextView) findViewById(R.id.profile_name);
		button_control = (Button) findViewById(R.id.control);
		button_profile_prev = (Button) findViewById(R.id.profile_prev);
		button_profile_next = (Button) findViewById(R.id.profile_next);
		button_profile_menu = (Button) findViewById(R.id.profile_menu);
		textview_realtime = (TextView) findViewById(R.id.stats_realtime);
		textview_session = (TextView) findViewById(R.id.stats_session);
		textview_total = (TextView) findViewById(R.id.stats_total);
		textview_apps = (TextView) findViewById(R.id.stats_apps);
		((Button) findViewById(R.id.traffic_reset)).setOnClickListener(this);

		button_profile_prev.setOnClickListener(this);
		button_profile_next.setOnClickListener(this);
		button_profile_menu.setOnClickListener(this);
		textview_profile_name.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View view) {
				if (prefs.getEnable())
				  return false;
				showProfileMenu();
				return true;
			}
		});
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
			prefs.setEnable(!isEnable);
			updateUI();
			Intent intent = new Intent(this, TProxyService.class);
			if (isEnable)
			  startService(intent.setAction(TProxyService.ACTION_DISCONNECT));
			else
			  startService(intent.setAction(TProxyService.ACTION_CONNECT));
			QSTileService.requestUpdate(this);
		} else if (view == button_profile_prev) {
			switchProfile(-1);
		} else if (view == button_profile_next) {
			switchProfile(1);
		} else if (view == button_profile_menu) {
			if (!prefs.getEnable())
			  showProfileMenu();
		}
	}

	private void switchProfile(int direction) {
		if (prefs.getEnable())
		  return;
		int count = prefs.getProfileCount();
		prefs.setSelected((prefs.getSelected() + direction + count) % count);
		updateUI();
	}

	private void showProfileMenu() {
		String[] items = {
			getString(R.string.profile_rename),
			getString(R.string.profile_add),
			getString(R.string.profile_delete),
		};
		new AlertDialog.Builder(this)
			.setTitle(prefs.getProfileName())
			.setItems(items, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					switch (which) {
					case 0:
						showProfileNameDialog(R.string.profile_rename, false);
						break;
					case 1:
						if (prefs.getProfileCount() >= Preferences.MAX_PROFILES)
						  Toast.makeText(MainActivity.this, R.string.profile_limit, Toast.LENGTH_SHORT).show();
						else
						  showProfileNameDialog(R.string.profile_add, true);
						break;
					case 2:
						deleteProfile();
						break;
					}
				}
			})
			.show();
	}

	private void showProfileNameDialog(int titleId, final boolean add) {
		final android.widget.EditText input = new android.widget.EditText(this);
		input.setText(prefs.getProfileName());
		input.setHint(R.string.profile_name);
		input.setSingleLine(true);
		input.selectAll();

		android.widget.FrameLayout container = new android.widget.FrameLayout(this);
		int padding = (int) (16 * getResources().getDisplayMetrics().density);
		container.setPadding(padding, 0, padding, 0);
		container.addView(input);

		new AlertDialog.Builder(this)
			.setTitle(titleId)
			.setView(container)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					String name = input.getText().toString().trim();
					if (name.isEmpty())
					  return;
					if (add)
					  prefs.addProfile(name);
					else
					  prefs.setProfileName(name);
					updateUI();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void deleteProfile() {
		if (prefs.getProfileCount() <= 1) {
			Toast.makeText(this, R.string.profile_last, Toast.LENGTH_SHORT).show();
			return;
		}
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.profile_delete_confirm, prefs.getProfileName()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					prefs.deleteProfile();
					updateUI();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void updateUI() {
		textview_profile_name.setText(prefs.getProfileName());
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
		boolean editable = !prefs.getEnable();
		button_profile_prev.setEnabled(editable);
		button_profile_next.setEnabled(editable);
		button_profile_menu.setEnabled(editable);
		textview_profile_name.setEnabled(editable);
		updateStatus(!editable);
	}

	/* Paint the hero card and the main button for the current tunnel state. */
	private void updateStatus(boolean connected) {
		int bg = getThemeColor(connected ?
			com.google.android.material.R.attr.colorPrimaryContainer :
			com.google.android.material.R.attr.colorSurfaceVariant);
		int fg = getThemeColor(connected ?
			com.google.android.material.R.attr.colorOnPrimaryContainer :
			com.google.android.material.R.attr.colorOnSurfaceVariant);

		card_status.setCardBackgroundColor(ColorStateList.valueOf(bg));
		textview_status_title.setTextColor(fg);
		textview_status_subtitle.setTextColor(fg);
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
