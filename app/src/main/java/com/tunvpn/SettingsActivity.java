/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : The "Settings" tab: a sectioned list (connection, subscription,
               general, about). Every row shows an icon, a title and a live
               subtitle; tapping it opens the matching detail page or dialog.
 ============================================================================
*/

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.Set;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private LinearLayout group_connection;
	private LinearLayout group_lan;
	private LinearLayout group_subscription;
	private LinearLayout group_general;
	private LinearLayout group_about;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_settings);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		group_connection = (LinearLayout) findViewById(R.id.settings_group_connection);
		group_lan = (LinearLayout) findViewById(R.id.settings_group_lan);
		group_subscription = (LinearLayout) findViewById(R.id.settings_group_subscription);
		group_general = (LinearLayout) findViewById(R.id.settings_group_general);
		group_about = (LinearLayout) findViewById(R.id.settings_group_about);
	}

	/* Rebuilt on every resume so the subtitles reflect what the user just
	   changed in the pages behind these rows (apps, subscription, theme). */
	@Override
	protected void onResume() {
		super.onResume();
		prefs = new Preferences(this);
		buildList();
	}

	private void buildList() {
		group_connection.removeAllViews();
		group_lan.removeAllViews();
		group_subscription.removeAllViews();
		group_general.removeAllViews();
		group_about.removeAllViews();

		addRow(group_connection, R.drawable.ic_server, R.string.settings_connection,
			connectionSubtitle(), R.id.settings_connection);
		addRow(group_connection, R.drawable.ic_apps, R.string.apps,
			appsSubtitle(), R.id.settings_apps);
		addRow(group_connection, R.drawable.ic_routing, R.string.settings_connections,
			getString(R.string.settings_connections_hint), R.id.settings_connections);

		addSwitchRow(group_lan, R.drawable.ic_routing, R.string.settings_allow_lan,
			R.string.settings_allow_lan_hint, prefs.getAllowLan(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setAllowLan(checked);
					afterNetworkChange();
				}
			});
		addRow(group_lan, R.drawable.ic_dns, R.string.settings_lan_port,
			Integer.toString(prefs.getProxyPort()), R.id.settings_proxy_port);
		addRow(group_subscription, R.drawable.ic_subscribe, R.string.subs_config,
			subsSubtitle(), R.id.settings_subscription);
		addRow(group_general, R.drawable.ic_log, R.string.log,
			logSubtitle(), R.id.settings_log);
		addRow(group_general, R.drawable.ic_rules, R.string.settings_config,
			configSubtitle(), R.id.settings_config);
		addRow(group_general, R.drawable.ic_palette, R.string.theme,
			themeSubtitle(), R.id.settings_theme);
		addRow(group_about, R.drawable.ic_traffic, R.string.settings_version,
			versionSubtitle(), R.id.settings_version);
	}

	/* One row: icon + title + optional subtitle + chevron. A hairline divider
	   is inserted between rows so the card reads as a grouped list. */
	private void addRow(LinearLayout group, int iconRes, int titleRes, String subtitle, int id) {
		if (group.getChildCount() > 0)
		  group.addView(makeDivider());

		View row = getLayoutInflater().inflate(R.layout.settings_item, group, false);
		row.setId(id);
		((ImageView) row.findViewById(R.id.item_icon)).setImageResource(iconRes);
		((TextView) row.findViewById(R.id.item_title)).setText(titleRes);

		TextView sub = (TextView) row.findViewById(R.id.item_subtitle);
		if (subtitle == null || subtitle.isEmpty()) {
			sub.setVisibility(View.GONE);
		} else {
			sub.setText(subtitle);
			sub.setVisibility(View.VISIBLE);
		}

		row.setOnClickListener(this);
		group.addView(row);
	}

	/* Same as addRow(), but the trailing control is a switch. Tapping anywhere
	   on the row toggles it as well, since the switch itself is a small
	   target. */
	private void addSwitchRow(LinearLayout group, int iconRes, int titleRes, int subtitleRes,
			boolean checked, CompoundButton.OnCheckedChangeListener listener) {
		if (group.getChildCount() > 0)
		  group.addView(makeDivider());

		View row = getLayoutInflater().inflate(R.layout.settings_switch_item, group, false);
		((ImageView) row.findViewById(R.id.item_icon)).setImageResource(iconRes);
		((TextView) row.findViewById(R.id.item_title)).setText(titleRes);
		TextView sub = (TextView) row.findViewById(R.id.item_subtitle);
		sub.setText(subtitleRes);
		sub.setVisibility(View.VISIBLE);

		final SwitchMaterial toggle = (SwitchMaterial) row.findViewById(R.id.item_switch);
		toggle.setChecked(checked);
		toggle.setOnCheckedChangeListener(listener);
		row.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				toggle.toggle();
			}
		});
		group.addView(row);
	}

	/* Port and allow-lan both land in the generated config, so a running
	   tunnel keeps the old values until it restarts. */
	private void afterNetworkChange() {
		if (prefs.getEnable())
		  Toast.makeText(this, R.string.settings_restart_needed, Toast.LENGTH_LONG).show();
	}

	private void editPort() {
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setHint(R.string.settings_port_hint);
		input.setText(Integer.toString(prefs.getProxyPort()));
		input.setSelection(input.getText().length());
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		input.setPadding(pad, pad / 2, pad, 0);

		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_lan_port)
			.setMessage(R.string.settings_port_hint)
			.setView(input)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					int port;
					try {
						port = Integer.parseInt(input.getText().toString().trim());
					} catch (NumberFormatException e) {
						port = prefs.getProxyPort();
					}
					int clamped = Math.max(Preferences.MIN_PROXY_PORT,
						Math.min(Preferences.MAX_PROXY_PORT, port));
					if (clamped != port)
					  Toast.makeText(SettingsActivity.this,
						getString(R.string.settings_port_clamped, clamped),
						Toast.LENGTH_SHORT).show();
					prefs.setProxyPort(clamped);
					buildList();
					afterNetworkChange();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private View makeDivider() {
		View divider = new View(this);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, 1);
		float density = getResources().getDisplayMetrics().density;
		/* start where the title starts: 16dp padding + 22dp icon + 16dp gap */
		lp.leftMargin = (int) (54 * density);
		divider.setLayoutParams(lp);
		divider.setBackgroundColor(MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorOutlineVariant, 0));
		return divider;
	}

	/* What the tunnel has moved this session. This row is the connection's
	   live data rather than a place to configure a proxy, so lead with the
	   counters - which node it is using already shows on the home screen and
	   inside the dialog. */
	private String connectionSubtitle() {
		if (!prefs.getEnable())
		  return getString(R.string.status_disconnected);
		long tx = prefs.getSessionTx();
		long rx = prefs.getSessionRx();
		if (tx <= 0 && rx <= 0)
		  return getString(R.string.status_connected);
		return getString(R.string.status_connected) + " · ↑ "
			+ TProxyService.formatBytes(tx) + " / ↓ " + TProxyService.formatBytes(rx);
	}

	private String appsSubtitle() {
		if (prefs.getGlobal())
		  return getString(R.string.global);
		Set<String> apps = prefs.getApps();
		int count = (apps == null) ? 0 : apps.size();
		if (count == 0)
		  return getString(R.string.settings_apps_none);
		return getString(R.string.apps_selected, count);
	}

	private String subsSubtitle() {
		Subscription sub = prefs.getActiveSubscription();
		if (sub != null)
		  return sub.label();
		String url = prefs.getSubUrl();
		if (url == null || url.trim().isEmpty())
		  return getString(R.string.settings_sub_unset);
		return url.trim();
	}

	private String logSubtitle() {
		return getString(prefs.getLogEnabled() ? R.string.settings_state_on
			: R.string.settings_state_off);
	}

	/* Whether a generated config exists, and whether it is overridden by hand. */
	private String configSubtitle() {
		File f = new File(getFilesDir(), "config.yaml");
		if (!f.exists())
		  return getString(R.string.settings_config_none);
		return getString(prefs.getCustomConfig()
			? R.string.settings_config_custom : R.string.settings_config_auto);
	}

	private String themeSubtitle() {
		int theme = prefs.getTheme();
		if (theme < 0 || theme >= ThemeManager.count())
		  theme = 0;
		return getString(ThemeManager.nameRes(theme));
	}

	private String versionSubtitle() {
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			/* PackageInfo.versionCode is deprecated since API 28. */
			long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? pi.getLongVersionCode() : pi.versionCode;
			return pi.versionName + " (" + code + ")";
		} catch (Exception e) {
			return "";
		}
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.settings_connection)
		  showConnectionDialog();
		else if (id == R.id.settings_apps)
		  startActivity(new Intent(this, AppListActivity.class));
		else if (id == R.id.settings_connections)
		  startActivity(new Intent(this, ConnectionsActivity.class));
		else if (id == R.id.settings_subscription)
		  startActivity(new Intent(this, SubscribeConfigActivity.class));
		else if (id == R.id.settings_proxy_port)
		  editPort();
		else if (id == R.id.settings_log)
		  startActivity(new Intent(this, LogActivity.class));
		else if (id == R.id.settings_config)
		  startActivity(new Intent(this, ConfigActivity.class));
		else if (id == R.id.settings_theme)
		  showThemeDialog();
		else if (id == R.id.settings_version)
		  showVersionDialog();
	}

	/* Live snapshot of the running tunnel: the real state (including a failed
	   start), the node/server it is on, and the counters as of now. The
	   Prefs are re-read first because Enable / LastError / stats are written
	   by the :native process. */
	private void showConnectionDialog() {
		prefs = new Preferences(this);

		final boolean connected = prefs.getEnable();
		final String error = prefs.getLastError();

		String state;
		if (connected)
		  state = getString(R.string.status_connected);
		else if (!error.isEmpty())
		  state = getString(R.string.status_failed);
		else
		  state = getString(R.string.status_disconnected);

		String node = prefs.getCurrentNode();
		if (node.isEmpty())
		  node = getString(prefs.hasSubscription() ? R.string.node_default : R.string.node_none);

		SocksServer server = prefs.getActiveSocksServer();
		String serverText = server != null
			? server.addr + ":" + server.port
			: getString(R.string.connection_server_sub);

		StringBuilder sb = new StringBuilder();
		sb.append(getString(R.string.connection_state)).append(": ").append(state);
		if (!connected && !error.isEmpty())
		  sb.append("（").append(error).append("）");
		sb.append('\n')
			.append(getString(R.string.connection_node)).append(": ").append(node).append('\n')
			.append(getString(R.string.connection_server)).append(": ").append(serverText);

		if (connected) {
			sb.append('\n')
				.append(getString(R.string.stats_realtime)).append(": ")
				.append(TProxyService.formatRate(prefs.getRateTx()))
				.append(" / ")
				.append(TProxyService.formatRate(prefs.getRateRx()));
		}

		sb.append('\n')
			.append(getString(R.string.connection_session)).append(": ")
			.append(TProxyService.formatBytes(prefs.getSessionTx()))
			.append(" / ")
			.append(TProxyService.formatBytes(prefs.getSessionRx()))
			.append('\n')
			.append(getString(R.string.connection_total)).append(": ")
			.append(TProxyService.formatBytes(prefs.getTotalTx()))
			.append(" / ")
			.append(TProxyService.formatBytes(prefs.getTotalRx()));

		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_connection)
			.setMessage(sb.toString())
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	/* Theme picker: choose one of the bundled palettes, then recreate so the
	   new theme is picked up. */
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

	private void showVersionDialog() {
		String name = "";
		long code = 0;
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			name = pi.versionName;
			/* PackageInfo.versionCode is deprecated since API 28. */
			code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? pi.getLongVersionCode() : pi.versionCode;
		} catch (Exception e) {
		}

		new AlertDialog.Builder(this)
			.setTitle(R.string.version_info)
			.setMessage(getString(R.string.version_detail, name, code))
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}
}
