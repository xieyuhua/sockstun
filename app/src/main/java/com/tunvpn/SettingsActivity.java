/*
 ============================================================================
 文件名  : SettingsActivity.java
 说明    : 「设置」页：按分组排列的列表（连接 / 局域网代理 / 订阅 / 通用 / 关于）。
           每行显示图标、标题和实时副标题；点击打开对应的详情页或对话框。
 ============================================================================
*/

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.app.ProgressDialog;
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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private LinearLayout group_connection;
	private LinearLayout group_lan;
	private LinearLayout group_subscription;
	private LinearLayout group_latency;
	private LinearLayout group_backup;
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
		group_latency = (LinearLayout) findViewById(R.id.settings_group_latency);
		group_backup = (LinearLayout) findViewById(R.id.settings_group_backup);
		group_general = (LinearLayout) findViewById(R.id.settings_group_general);
		group_about = (LinearLayout) findViewById(R.id.settings_group_about);
	}

	/* 每次 resume 都重建一次列表，这样副标题能反映用户刚刚在那些入口页面里改过的
	   内容（应用、订阅、主题）。 */
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
		group_latency.removeAllViews();
		group_backup.removeAllViews();
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
		/* 订阅管理：进入订阅配置页管理地址 / 启用；以及是否在列表里显示不可用节点。 */
		addRow(group_subscription, R.drawable.ic_subscribe, R.string.subs_config,
			subsSubtitle(), R.id.settings_subscription);
		addSwitchRow(group_subscription, R.drawable.ic_routing,
			R.string.settings_show_unavailable,
			R.string.settings_show_unavailable_hint, prefs.getShowUnavailable(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setShowUnavailable(checked);
				}
			});

		/* ===== 延迟测试配置 =====
		   测速地址 / 超时 / 探测超时判定 / 自动重测间隔 / 预加载测速内核，
		   这些只影响"怎么测延迟"，与订阅本身无关，单独归到一类更清楚。 */
		addRow(group_latency, R.drawable.ic_routing, R.string.sub_test_url_title,
			getString(R.string.sub_test_url, prefs.getAutoTestUrl()), R.id.settings_test_url);
		addRow(group_latency, R.drawable.ic_routing, R.string.settings_test_timeout,
			getString(R.string.sub_test_timeout, prefs.getProxyTestTimeout()), R.id.settings_test_timeout);
		/* 探测**完全没有回应**时该怎么算：不可用（默认）还是未测速。做成开关，是因为
		   否则"所有节点都变不可用"与"内核/桥坏了"这两种情况很难区分。 */
		addSwitchRow(group_latency, R.drawable.ic_routing,
			R.string.settings_probe_timeout_fail,
			R.string.settings_probe_timeout_fail_hint, prefs.getProbeTimeoutAsFail(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setProbeTimeoutAsFail(checked);
				}
			});
		addRow(group_latency, R.drawable.ic_routing, R.string.settings_autosel_interval,
			autoSelectIntervalSubtitle(), R.id.settings_autosel_interval);
		/* 未连接时是否预加载无 TUN 的测速内核（以便测真实转发延迟），
		   以及该内核是否携带**全部**节点（忽略国家/地区筛选）。 */
		addSwitchRow(group_latency, R.drawable.ic_routing, R.string.settings_preload_core,
			R.string.settings_preload_core_hint, prefs.getPreloadCore(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setPreloadCore(checked);
					CoreTestHost.reset();
				}
			});

		/* ===== 备份分组：WebDAV 远程备份 =====
		   总开关开启**且**填了地址时，"备份可用节点"会传到远端；否则落成本地订阅
		   （原行为，见 SubscribeActivity.backupAvailableNodes）。 */
		addSwitchRow(group_backup, R.drawable.ic_subscribe, R.string.settings_webdav,
			R.string.settings_webdav_hint, prefs.getWebdavEnabled(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setWebdavEnabled(checked);
					buildList();
				}
			});
		addRow(group_backup, R.drawable.ic_routing, R.string.settings_webdav_config,
			webdavConfigSubtitle(), R.id.settings_webdav_config);
		/* 备份管理：进入独立界面，里面才有「备份当前可用节点」按钮、
		   备份历史记录、以及「覆盖当前配置」—— 之前这些只藏在订阅页菜单里。 */
		addRow(group_backup, R.drawable.ic_backup, R.string.backup_manager_title,
			backupCountSubtitle(), R.id.settings_backup_manager);
		addRow(group_general, R.drawable.ic_log, R.string.log,
			logSubtitle(), R.id.settings_log);
		addRow(group_general, R.drawable.ic_rules, R.string.settings_config,
			configSubtitle(), R.id.settings_config);
		addRow(group_general, R.drawable.ic_palette, R.string.theme,
			themeSubtitle(), R.id.settings_theme);
		addRow(group_about, R.drawable.ic_traffic, R.string.settings_version,
			versionSubtitle(), R.id.settings_version);
	}

	/* 一行：图标 + 标题 + 可选副标题 + 右侧箭头。行与行之间插一条细分隔线，让卡片
	   看起来是一个分组列表。 */
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

	/* 与 addRow() 相同，只是右侧控件换成开关。开关本身点击区域很小，所以点整行
	   也能切换。 */
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

	/* 代理端口与"允许局域网"都会写进生成的配置，所以运行中的隧道会一直沿用旧值，
	   直到重启。 */
	private void afterNetworkChange() {
		if (prefs.getEnable())
		  Toast.makeText(this, R.string.settings_restart_needed, Toast.LENGTH_LONG).show();
	}

	/* mihomo 的 url-test 组用来测速的目标地址。如果一个节点所在的网络访问不到这个
	   主机，那么在该组里每个节点都会被测成失败，哪怕节点本身是好的 —— 这正是
	   "测速正常但用不了"的常见原因。 */
	/* 测速地址：留空恢复默认值；任何输入都必须是 http(s) 地址，且边输边校验，
	   避免把坏值存进去。 */
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

	/* 代理端口：数字输入框，边输边做范围校验；「默认」恢复内置默认值。
	   applyProxyPort() 仍会再做一次夹取，所以范围内的值会原样保存。 */
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

	/* 代理端口的统一保存路径：夹到合法范围内、持久化、刷新设置行；隧道在运行时
	   还要重新应用。 */
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
		/* 与标题左对齐：16dp 内边距 + 22dp 图标 + 16dp 间距 */
		lp.leftMargin = (int) (54 * density);
		divider.setLayoutParams(lp);
		divider.setBackgroundColor(MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorOutlineVariant, 0));
		return divider;
	}

	/* 统一的单字段编辑器：Material 描边输入框 + 实时校验错误 + 可选的帮助文字。
	   取代了原来朴素的 EditText 对话框，让测速地址、代理端口、自动测速间隔都有一致、
	   易读的界面与行内错误提示。 */
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

	/* 本次会话隧道跑了多少流量。这一行是连接的实时数据，而不是配置代理的地方，
	   所以把计数器放在前面 —— 用的是哪个节点，首页和弹窗里都已经显示了。 */
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

	/* 说明生成的配置是否存在，以及是否被手动接管（使用自定义配置）。 */
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
			/* PackageInfo.versionCode 从 API 28 起已废弃。 */
			long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? pi.getLongVersionCode() : pi.versionCode;
			return pi.versionName + " (" + code + ")";
		} catch (Exception e) {
			return "";
		}
	}

	/* url-test 组的自动重测间隔。从订阅页挪到设置里，好让订阅页的"自动选择"行保持
	   一个朴素的开/关。单位是分钟，在隧道（重）启动时生效。 */
	private String autoSelectIntervalSubtitle() {
		return getString(R.string.sub_minutes,
			Math.max(1, Math.round(prefs.getAutoSelectInterval() / 60f)));
	}

	/* 自动重测间隔：单个分钟输入框（1–1440），取代原来"先选预设再自定义"的流程。
	   留空或超出范围会在输入框内直接报错。 */
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

	/* 手动"测节点"时单个探测目标的超时（秒，1–30）。它限定内核转发这次探测最多花多久，
	   免得一个慢但可用的节点被过紧的默认值误判为不可用。 */
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
		else if (id == R.id.settings_webdav_config)
		  showWebdavConfig();
		else if (id == R.id.settings_backup_manager)
		  startActivity(new Intent(this, BackupActivity.class));
	}

	/* 运行中隧道的实时快照：真实状态（含启动失败）、所在节点/服务器、以及当前计数器。
	   先重新读取 Preferences，因为 Enable / LastError / 统计都是 :native 进程写的。 */
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
		/* 粘贴式节点（vmess / vless / …）没有可用的地址端口，直接拼 addr:port 会显示
		   ":0"；那种情况退成协议名 —— "节点"那一行已经写了它的名字。 */
		String serverText = getString(R.string.connection_server_sub);
		if (server != null) {
			String addr = server.addr == null ? "" : server.addr.trim();
			serverText = addr.isEmpty()
				? ((server.type == null || server.type.isEmpty()) ? "socks5" : server.type)
				: (addr + ":" + server.port);
		}

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

	/* 主题选择：从内置的几套配色里挑一个，然后 recreate 让新主题生效。 */
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
			/* PackageInfo.versionCode 从 API 28 起已废弃。 */
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

	/* 设置行里 clash-api 令牌的脱敏预览：只显示首尾各四个字符，既能辨认又不会在列表里
	   完全暴露。 */
	private String secretSubtitle() {
		String s = prefs.getSecret();
		if (s == null || s.isEmpty())
		  return getString(R.string.settings_secret_hint);
		int n = s.length();
		if (n <= 8)
		  return s;
		return s.substring(0, 4) + "••••••••" + s.substring(n - 4);
	}

	/* WebDAV 配置弹窗：把地址 / 账号 / 密码 / 目录收进一个页面，并可在存盘前先"测试连接"
	   （用当前填的值，不必先存盘）。这样设置列表里只占一行（开关 + 配置入口）。 */
	private void showWebdavConfig() {
		final View v = getLayoutInflater().inflate(R.layout.dialog_webdav, null);
		final EditText url = (EditText) v.findViewById(R.id.webdav_url);
		final EditText user = (EditText) v.findViewById(R.id.webdav_user);
		final EditText pass = (EditText) v.findViewById(R.id.webdav_pass);
		final EditText dir = (EditText) v.findViewById(R.id.webdav_dir);
		final Button testBtn = (Button) v.findViewById(R.id.webdav_test);
		url.setText(prefs.getWebdavUrl());
		user.setText(prefs.getWebdavUser());
		pass.setText(prefs.getWebdavPass());
		dir.setText(prefs.getWebdavDir());

		final ProgressDialog[] pd = new ProgressDialog[1];
		testBtn.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View b) {
				final String u = url.getText().toString().trim();
				if (u.isEmpty()) {
					Toast.makeText(SettingsActivity.this, R.string.settings_webdav_need_url,
						Toast.LENGTH_SHORT).show();
					return;
				}
				if (!isHttpUrl(u)) {
					Toast.makeText(SettingsActivity.this, R.string.settings_webdav_url_invalid,
						Toast.LENGTH_SHORT).show();
					return;
				}
				final String us = user.getText().toString().trim();
				final String pa = pass.getText().toString();
				final String di = dir.getText().toString().trim();
				pd[0] = ProgressDialog.show(SettingsActivity.this, null,
					getString(R.string.settings_webdav_testing), true, false);
				new Thread(new Runnable() {
					@Override public void run() {
						try {
							final String where = WebDav.test(u, us, pa, di);
							runOnUiThread(new Runnable() {
								@Override public void run() {
									if (pd[0] != null && pd[0].isShowing()) pd[0].dismiss();
									Toast.makeText(SettingsActivity.this,
										getString(R.string.settings_webdav_ok, where),
										Toast.LENGTH_LONG).show();
								}
							});
						} catch (final IOException e) {
							runOnUiThread(new Runnable() {
								@Override public void run() {
									if (pd[0] != null && pd[0].isShowing()) pd[0].dismiss();
									Toast.makeText(SettingsActivity.this,
										getString(R.string.settings_webdav_fail, e.getMessage()),
										Toast.LENGTH_LONG).show();
								}
							});
						}
					}
				}).start();
			}
		});

		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_webdav_config)
			.setView(v)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int w) {
					String u = url.getText().toString().trim();
					if (!u.isEmpty() && !isHttpUrl(u)) {
						Toast.makeText(SettingsActivity.this, R.string.settings_webdav_url_invalid,
							Toast.LENGTH_SHORT).show();
						return;
					}
					prefs.setWebdavUrl(u);
					prefs.setWebdavUser(user.getText().toString().trim());
					prefs.setWebdavPass(pass.getText().toString());
					prefs.setWebdavDir(dir.getText().toString().trim());
					buildList();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* 备份管理入口那一行下面的副标题：显示已有几份本地备份。 */
	private String backupCountSubtitle() {
		int n = 0;
		for (Subscription s : prefs.getSubscriptions())
		  if (s.local)
			n++;
		return n > 0 ? getString(R.string.backup_count_subtitle, n)
			: getString(R.string.backup_count_none);
	}

	/* 配置入口那一行下面的副标题：一眼看出开关状态 + 是否已填地址。 */
	private String webdavConfigSubtitle() {
		if (!prefs.getWebdavEnabled())
		  return getString(R.string.settings_webdav_off);
		if (prefs.getWebdavUrl().isEmpty())
		  return getString(R.string.settings_webdav_unset);
		return getString(R.string.settings_webdav_on, prefs.getWebdavUrl());
	}

	/* clash-api 令牌查看器：只读（可选中手动复制），另有"复制"与"重置"。重置会重新
	   生成令牌，而运行中的内核要等隧道（重）启动才会采用它，所以用 afterNetworkChange()
	   给出提示。 */
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
