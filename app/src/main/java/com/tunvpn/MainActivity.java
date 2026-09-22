/*
 ============================================================================
 文件名  : MainActivity.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2023 xyz
 说明    : 首页：连接开关 + 状态卡片 + 当前节点 + 配置档切换 + 流量卡片，并承载底部导航。
 ============================================================================
 */

package com.tunvpn;

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
import android.widget.Toast;
import android.net.VpnService;


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
	private Button button_control;
	private TextView textview_realtime;
	private TextView textview_session;
	private TextView textview_total;

	private static final long STATS_INTERVAL = 1500;
	private Handler statsHandler;
	private Runnable statsTask;
	/* 已经用 Toast 提示过的失败原因，避免每 1.5 秒的刷新又弹一次。 */
	private String lastShownError = "";

	/* 本页可见时，隧道在别处被开关（例如快捷设置磁贴）也要刷新连接状态。 */
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
					/* 这些入口打开的是独立页面，所以底部导航保持停在"首页"，
					   而不是让被点的那个标签一直处于选中态。 */
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
		button_control = (Button) findViewById(R.id.control);
		textview_realtime = (TextView) findViewById(R.id.stats_realtime);
		textview_session = (TextView) findViewById(R.id.stats_session);
		textview_total = (TextView) findViewById(R.id.stats_total);
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

		/* Android 13+ 若未授予 POST_NOTIFICATIONS 权限，常驻流量通知会被系统隐藏。 */
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
				android.content.pm.PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1);
		}

		/* 申请 VPN 权限 */
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
			/* 先让运行中的服务把**内存里**的计数器一起归零并重新定基准 —— 否则它的
			   采样线程下一秒就用旧值把 preferences 覆盖掉，症状正是"界面上当时清空了、
			   实际没重置"。服务没在跑时这一步是空操作。 */
			TProxyService.resetTraffic();
			prefs.resetStats();
			prefs.resetProxyStats();
			refreshTraffic();
			return;
		}
		if (view == button_control) {
			boolean isEnable = prefs.getEnable();
			/* 重新尝试连接时，先清掉上一次的失败提示。 */
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

	/* 流量计数器：隧道总量是精确值；分应用的数字来自系统计数器，包含隧道之外的流量。 */
	private void refreshTraffic() {
		prefs = new Preferences(this);
		/* 代理专属流量：其背后的计数器来自内核连接列表并按链路过滤，直连流量不计入。 */
		/* 隧道总流量来自核心 getTotalTraffic，独立于 9090 控制接口；而代理专属
		   统计依赖 /connections（9090），控制接口不可用时恒为 0，会导致“流量不动”
		   的假象。故实时/会话/总一律用总流量。 */
		textview_realtime.setText(statsLine(R.string.stats_realtime,
			TProxyService.formatRate(prefs.getRateTx()),
			TProxyService.formatRate(prefs.getRateRx())));
		textview_session.setText(statsLine(R.string.stats_session,
			TProxyService.formatBytes(prefs.getSessionTx()),
			TProxyService.formatBytes(prefs.getSessionRx())));
		textview_total.setText(statsLine(R.string.stats_total,
			TProxyService.formatBytes(prefs.getTotalTx()),
			TProxyService.formatBytes(prefs.getTotalRx())));
		updateNode();

		/* Enable 与 LastError 由 :native 进程写入，它无法通过
		   OnSharedPreferenceChangeListener 通知本进程 —— 所以这里轮询。没有这一步，
		   启动失败后卡片会一直显示"已连接"，要等到下次回到本页才看得到失败。 */
		updateControlState();
	}

	/* 隧道正在用哪个节点：隧道**在运行时**显示内核**真正选中**的那个（TProxyService
	   从运行中的配置里读出来记下的）；否则显示所选订阅节点、手动 SOCKS5 上游，或订阅
	   自带的默认节点。 */
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

	private void updateControlState() {
		updateStatus();
	}

	/* 绘制状态卡片与主按钮。
	   状态是**三种**而不是两种：启动尝试可能在 Enable 已被置 true **之后**才失败
	   （本页会在请求服务启动前就先置位），所以"未连接"单独一个状态说不清发生过什么。
	   服务报失败时会写 LastError 并把 Enable 置回 false，我们在这里把原因显示出来，
	   而不是悄悄退回"未连接"。 */
	private void updateStatus() {
		final boolean connected = prefs.getEnable();
		final String error = prefs.getLastError();
		final boolean failed = !connected && !error.isEmpty();

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

		/* 每种不同的失败只提示一次，不是每 1.5 秒弹一次。 */
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
