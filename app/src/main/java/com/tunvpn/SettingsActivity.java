/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : The "Settings" tab: a sectioned list (connection, subscription,
               general, about). Every row shows an icon, a title and a live
               subtitle; tapping it opens the matching detail page or dialog.
 ============================================================================
*/

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
		addRow(group_connection, R.drawable.ic_routing, R.string.settings_recent_requests,
			getString(R.string.settings_recent_requests_hint), R.id.settings_recent_requests);
		addRow(group_connection, R.drawable.ic_rules, R.string.settings_secret,
			secretSubtitle(), R.id.settings_secret);

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
		addRow(group_subscription, R.drawable.ic_routing, R.string.sub_test_url_title,
			getString(R.string.sub_test_url, prefs.getAutoTestUrl()), R.id.settings_test_url);
		addRow(group_subscription, R.drawable.ic_routing, R.string.settings_test_timeout,
			getString(R.string.sub_test_timeout, prefs.getProxyTestTimeout()), R.id.settings_test_timeout);
		addRow(group_subscription, R.drawable.ic_routing, R.string.settings_autosel_interval,
			autoSelectIntervalSubtitle(), R.id.settings_autosel_interval);
		/* Latency-test behaviour: whether a TUN-less core is kept alive so the
		   real forwarding delay can be measured while disconnected, and
		   whether that core carries ALL nodes (ignore the country filter). */
		addSwitchRow(group_subscription, R.drawable.ic_routing, R.string.settings_preload_core,
			R.string.settings_preload_core_hint, prefs.getPreloadCore(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setPreloadCore(checked);
					CoreTestHost.reset();
				}
			});
		/* Whether the subscribe list also shows unusable / untested nodes. */
		addSwitchRow(group_subscription, R.drawable.ic_routing, R.string.settings_show_unavailable,
			R.string.settings_show_unavailable_hint, prefs.getShowUnavailable(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setShowUnavailable(checked);
				}
			});
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

	/* The URL mihomo's url-test groups measure against. If a node's network
	   cannot reach this host, every node scores as failed there even though the
	   node itself works — the usual cause of "tested OK but unusable". */
	/* Test URL: blank restores the default; any value must be an http(s) URL,
	   validated live so a bad entry is caught before saving. */
	private void editTestUrl() {
		showInputDialog(R.string.sub_test_url_title,
			getString(R.string.sub_test_url_title),
			getString(R.string.sub_test_url_hint),
			InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT,
			prefs.getAutoTestUrl(),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return null;
					if (!isHttpUrl(v))
					  return getString(R.string.sub_test_url_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					prefs.setAutoTestUrl(v.trim());
					buildList();
					afterNetworkChange();
				}
			},
			null);
	}

	private static boolean isHttpUrl(String url) {
		String u = url.toLowerCase();
		return u.startsWith("http://") || u.startsWith("https://");
	}

	/* Proxy port: a numeric field with live range checking; "默认" restores the
	   built-in default. applyProxyPort() still clamps, so an in-range value is
	   saved as-is. */
	private void editPort() {
		showInputDialog(R.string.settings_lan_port,
			getString(R.string.settings_port_hint),
			getString(R.string.settings_port_desc),
			InputType.TYPE_CLASS_NUMBER,
			Integer.toString(prefs.getProxyPort()),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return getString(R.string.settings_port_invalid);
					try {
						Integer.parseInt(v);
					} catch (NumberFormatException e) {
						return getString(R.string.settings_port_invalid);
					}
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					int port;
					try {
						port = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						port = prefs.getProxyPort();
					}
					applyProxyPort(port);
				}
			},
			new Runnable() {
				@Override public void run() {
					applyProxyPort(Preferences.DEFAULT_PROXY_PORT);
				}
			});
	}

	/* Shared save path for the proxy port: clamp into the valid range, persist,
	   refresh the settings row, and re-apply if the tunnel is live. */
	private void applyProxyPort(int port) {
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

	/* Shared, polished single-field editor: an outlined Material input with a
	   live validation error and an optional help line. Replaces the bare
	   EditText dialogs so the test URL, proxy port and auto-test interval all
	   get a consistent, legible UI with inline error feedback. */
	private interface InputValidator {
		String validate(String value);
	}
	private interface OnValue {
		void onReceiveValue(String value);
	}

	private AlertDialog showInputDialog(int titleRes, String hint, String help,
			int inputType, String initial, final InputValidator validator,
			final OnValue onOk, final Runnable onDefault) {
		View v = getLayoutInflater().inflate(R.layout.dialog_input, null);
		final TextInputLayout til = (TextInputLayout) v.findViewById(R.id.til);
		final TextInputEditText edit = (TextInputEditText) v.findViewById(R.id.edit_text);
		TextView helpView = (TextView) v.findViewById(R.id.help_text);
		til.setHint(hint);
		edit.setInputType(inputType);
		if (help != null && !help.isEmpty()) {
			helpView.setText(help);
			helpView.setVisibility(View.VISIBLE);
		} else {
			helpView.setVisibility(View.GONE);
		}
		if (initial != null) {
			edit.setText(initial);
			edit.setSelection(initial.length());
		}
		AlertDialog.Builder b = new AlertDialog.Builder(this)
			.setTitle(titleRes)
			.setView(v)
			.setPositiveButton(R.string.save, null)
			.setNegativeButton(android.R.string.cancel, null);
		if (onDefault != null)
			b.setNeutralButton(R.string.settings_port_default,
				new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface di, int w) {
						onDefault.run();
					}
				});
		final AlertDialog d = b.show();
		edit.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
			@Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
			@Override public void afterTextChanged(Editable s) {
				til.setError(validator.validate(s.toString().trim()));
			}
		});
		d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View vv) {
				String val = edit.getText().toString().trim();
				String err = validator.validate(val);
				if (err != null) {
					til.setError(err);
					return;
				}
				d.dismiss();
				onOk.onReceiveValue(val);
			}
		});
		return d;
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

	/* Auto re-test interval for the url-test group, moved here from the
	   subscription page so its auto-select row can stay a plain on/off switch.
	   The value is in minutes and applies when the tunnel is (re)started. */
	private String autoSelectIntervalSubtitle() {
		return getString(R.string.sub_minutes,
			Math.max(1, Math.round(prefs.getAutoSelectInterval() / 60f)));
	}

	/* Auto re-test interval: a single minute field (1–1440), replacing the old
	   preset-list-then-custom flow. Blank or out-of-range is caught inline. */
	private void editAutoSelectInterval() {
		final int cur = Math.max(1, Math.round(prefs.getAutoSelectInterval() / 60f));
		showInputDialog(R.string.sub_interval_title,
			getString(R.string.sub_interval_hint),
			getString(R.string.sub_interval_custom_title),
			InputType.TYPE_CLASS_NUMBER,
			Integer.toString(cur),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return getString(R.string.sub_interval_invalid);
					int m;
					try {
						m = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						return getString(R.string.sub_interval_invalid);
					}
					if (m < 1 || m > 1440)
					  return getString(R.string.sub_interval_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					int m;
					try {
						m = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						m = cur;
					}
					int clamped = Math.max(1, Math.min(1440, m));
					prefs.setAutoSelectInterval(clamped * 60);
					buildList();
					afterNetworkChange();
				}
			},
			null);
	}

	/* Latency-test timeout for the manual "test node" pass (seconds, 1–30).
	   It caps how long mihomo may spend forwarding the probe, so a slow but
	   usable node is not flagged unavailable by an over-tight default. */
	private void editTestTimeout() {
		final int cur = prefs.getProxyTestTimeout();
		showInputDialog(R.string.settings_test_timeout,
			getString(R.string.settings_test_timeout),
			getString(R.string.sub_test_timeout_hint),
			InputType.TYPE_CLASS_NUMBER,
			Integer.toString(cur),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return getString(R.string.sub_test_timeout_invalid);
					int s;
					try {
						s = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						return getString(R.string.sub_test_timeout_invalid);
					}
					if (s < Preferences.MIN_PROXY_TEST_TIMEOUT
							|| s > Preferences.MAX_PROXY_TEST_TIMEOUT)
					  return getString(R.string.sub_test_timeout_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					int s;
					try {
						s = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						s = cur;
					}
					int clamped = Math.max(Preferences.MIN_PROXY_TEST_TIMEOUT,
						Math.min(Preferences.MAX_PROXY_TEST_TIMEOUT, s));
					prefs.setProxyTestTimeout(clamped);
					buildList();
				}
			},
			null);
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
		else if (id == R.id.settings_recent_requests)
		  startActivity(new Intent(this, RecentRequestsActivity.class));
		else if (id == R.id.settings_subscription)
		  startActivity(new Intent(this, SubscribeConfigActivity.class));
		else if (id == R.id.settings_test_url)
		  editTestUrl();
		else if (id == R.id.settings_test_timeout)
		  editTestTimeout();
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
		else if (id == R.id.settings_autosel_interval)
		  editAutoSelectInterval();
		else if (id == R.id.settings_secret)
		  showSecretDialog();
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

	/* Masked preview of the clash-api bearer token for the settings row: show
	   only the first and last four chars so the value is recognisable without
	   being fully exposed in the list. */
	private String secretSubtitle() {
		String s = prefs.getSecret();
		if (s == null || s.isEmpty())
		  return getString(R.string.settings_secret_hint);
		int n = s.length();
		if (n <= 8)
		  return s;
		return s.substring(0, 4) + "••••••••" + s.substring(n - 4);
	}

	/* clash-api bearer token viewer: read-only (selectable for manual copy),
	   plus Copy and Reset. Resetting regenerates the token; the running core
	   only picks it up after the tunnel (re)starts, so we warn via
	   afterNetworkChange(). */
	private void showSecretDialog() {
		prefs = new Preferences(this);
		final String secret = prefs.getSecret();
		View v = getLayoutInflater().inflate(R.layout.dialog_input, null);
		final TextInputLayout til = (TextInputLayout) v.findViewById(R.id.til);
		final TextInputEditText edit = (TextInputEditText) v.findViewById(R.id.edit_text);
		TextView helpView = (TextView) v.findViewById(R.id.help_text);
		til.setHint(getString(R.string.settings_secret));
		edit.setText(secret);
		edit.setInputType(InputType.TYPE_NULL);
		edit.setTextIsSelectable(true);
		helpView.setText(getString(R.string.settings_secret_hint));
		helpView.setVisibility(View.VISIBLE);

		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_secret)
			.setView(v)
			.setPositiveButton(R.string.settings_secret_copy,
				new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface di, int w) {
						ClipboardManager cm = (ClipboardManager)
							getSystemService(android.content.Context.CLIPBOARD_SERVICE);
						if (cm != null)
						  cm.setPrimaryClip(ClipData.newPlainText("secret", secret));
						Toast.makeText(SettingsActivity.this,
							R.string.settings_secret_copied, Toast.LENGTH_SHORT).show();
					}
				})
			.setNeutralButton(R.string.settings_secret_reset,
				new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface di, int w) {
						prefs.resetSecret();
						buildList();
						afterNetworkChange();
						Toast.makeText(SettingsActivity.this,
							R.string.settings_secret_reset_done, Toast.LENGTH_LONG).show();
					}
				})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}
}
