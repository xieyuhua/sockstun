/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : The "Settings" tab: per-app list, log, theme and version.
               Each entry opens its own detail (page or dialog).
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;

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

		int[] ids = {
			R.id.settings_connection, R.id.settings_apps, R.id.settings_log,
			R.id.settings_theme, R.id.settings_version,
		};
		for (int id : ids)
		  ((MaterialButton) findViewById(id)).setOnClickListener(this);
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.settings_connection)
		  showConnectionDialog();
		else if (id == R.id.settings_apps)
		  startActivity(new Intent(this, AppListActivity.class));
		else if (id == R.id.settings_log)
		  startActivity(new Intent(this, LogActivity.class));
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
