/*
 ============================================================================
 文件名  : RulesHubActivity.java
 说明    : 分流 / DNS / 路由规则三块设置合在一页，且按生效顺序排列：
           分流作用域 → DNS → 路由规则。
 ============================================================================
 */

package com.tunvpn;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputLayout;

public class RulesHubActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;

	private CompoundButton checkbox_global;
	private CompoundButton checkbox_ipv4;
	private CompoundButton checkbox_ipv6;
	private CompoundButton checkbox_udp_in_tcp;
	private MaterialButton button_apps;
	/* 分流卡片下方的实时提示：解释"两层路由"，并在"部分应用"却没勾任何应用
	   （隧道作用域为空）时给出警告。 */
	private TextView textview_scope_hint;
	private int scopeHintColor;
	private int scopeErrorColor;
	/* 规则行的动作配色：走代理 = 主色，直连 = 弱化色。 */
	private int ruleProxyColor;
	private int ruleDirectColor;

	private CompoundButton checkbox_remote_dns;
	private EditText edittext_dns_ipv4;
	private EditText edittext_dns_ipv6;
	private TextInputLayout til_dns_ipv4;
	private TextInputLayout til_dns_ipv6;

	private CompoundButton switch_default_proxy;
	private Spinner spinner_type;
	private Spinner spinner_action;
	/* 值输入框的容器，方便让它的 hint 跟着所选规则类型变化。 */
	private TextInputLayout til_rule_value;
	private Spinner spinner_strategy;
	private TextView textview_mode_hint;
	private EditText edit_value;
	private LinearLayout rules_list;
	private TextView rules_empty;
	private List<Preferences.Rule> rules;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_rules_hub);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		/* --- 路由开关 --- */
		checkbox_global = (CompoundButton) findViewById(R.id.global);
		checkbox_ipv4 = (CompoundButton) findViewById(R.id.ipv4);
		checkbox_ipv6 = (CompoundButton) findViewById(R.id.ipv6);
		checkbox_udp_in_tcp = (CompoundButton) findViewById(R.id.udp_in_tcp);
		button_apps = (MaterialButton) findViewById(R.id.apps);
		button_apps.setOnClickListener(this);
		checkbox_global.setOnClickListener(this);
		textview_scope_hint = (TextView) findViewById(R.id.routing_scope_hint);
		scopeHintColor = MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
		scopeErrorColor = MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorError, Color.GRAY);
		ruleProxyColor = MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorPrimary, Color.GRAY);
		ruleDirectColor = MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);

		/* --- DNS --- */
		checkbox_remote_dns = (CompoundButton) findViewById(R.id.remote_dns);
		edittext_dns_ipv4 = (EditText) findViewById(R.id.dns_ipv4);
		edittext_dns_ipv6 = (EditText) findViewById(R.id.dns_ipv6);
		til_dns_ipv4 = (TextInputLayout) findViewById(R.id.til_dns_ipv4);
		til_dns_ipv6 = (TextInputLayout) findViewById(R.id.til_dns_ipv6);
		checkbox_remote_dns.setOnClickListener(this);

		/* --- 路由规则 --- */
		switch_default_proxy = (CompoundButton) findViewById(R.id.rules_default_proxy);
		spinner_type = (Spinner) findViewById(R.id.rule_type);
		spinner_action = (Spinner) findViewById(R.id.rule_action);
		til_rule_value = (TextInputLayout) findViewById(R.id.til_rule_value);
		edit_value = (EditText) findViewById(R.id.rule_value);
		rules_list = (LinearLayout) findViewById(R.id.rules_list);
		rules_empty = (TextView) findViewById(R.id.rules_empty);
		((MaterialButton) findViewById(R.id.rule_add)).setOnClickListener(this);

		setupSpinner(spinner_type, R.array.rule_types);
		setupSpinner(spinner_action, R.array.rule_actions);
		/* 每种规则类型期望的值不一样，所以 hint 跟着选择器走（它会立刻以 position 0
		   触发一次，正好把初始提示刷成第一种类型）。 */
		spinner_type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				updateValueHint(position);
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		/* 分流策略：规则模式 / 全局代理 / 全局直连。 */
		spinner_strategy = (Spinner) findViewById(R.id.rules_strategy);
		textview_mode_hint = (TextView) findViewById(R.id.rules_mode_hint);
		setupSpinner(spinner_strategy, R.array.rule_strategies);
		String strat = prefs.getRulesStrategy();
		spinner_strategy.setSelection(
			Preferences.RULES_STRATEGY_GLOBAL.equals(strat) ? 1
			: Preferences.RULES_STRATEGY_DIRECT.equals(strat) ? 2 : 0);
		spinner_strategy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				String s = position == 1 ? Preferences.RULES_STRATEGY_GLOBAL
						: position == 2 ? Preferences.RULES_STRATEGY_DIRECT
						: Preferences.RULES_STRATEGY_RULES;
				prefs.setRulesStrategy(s);
				updateModeUi();
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		switch_default_proxy.setChecked(prefs.getRulesDefaultProxy());
		switch_default_proxy.setOnCheckedChangeListener(
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setRulesDefaultProxy(checked);
				}
			});

		rules = prefs.getRules();
		renderRules();
		loadUI();
	}

	private void setupSpinner(Spinner spinner, int arrayRes) {
		ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(this,
			arrayRes, android.R.layout.simple_spinner_item);
		a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(a);
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
	}

	private void loadUI() {
		checkbox_global.setChecked(prefs.getGlobal());
		checkbox_ipv4.setChecked(prefs.getIpv4());
		checkbox_ipv6.setChecked(prefs.getIpv6());
		checkbox_udp_in_tcp.setChecked(prefs.getUdpInTcp());
		checkbox_remote_dns.setChecked(prefs.getRemoteDns());
		edittext_dns_ipv4.setText(prefs.getDnsIpv4());
		edittext_dns_ipv6.setText(prefs.getDnsIpv6());

		boolean editable = !prefs.getEnable();
		/* 全局/部分应用这个开关在已连接时也必须可切换：只有切到"部分应用"，应用
		   白名单才会生效。若锁住它，运行中的隧道就永远出不了全局模式，AppListActivity
		   里编辑的应用列表会被永久忽略。 */
		checkbox_global.setEnabled(true);
		checkbox_ipv4.setEnabled(editable);
		checkbox_ipv6.setEnabled(editable);
		checkbox_udp_in_tcp.setEnabled(editable);
		checkbox_remote_dns.setEnabled(editable);

		/* 已连接时也允许进入应用列表：AppListActivity 现在保存后会重建隧道，新的
		   作用域可以立即生效。 */
		button_apps.setEnabled(!checkbox_global.isChecked());
		applyDnsEnabled(editable, checkbox_remote_dns.isChecked());
		/* 规则控件的状态同时取决于"可编辑"与"当前策略"，所以统一交给 updateModeUi()
		   处理。 */
		updateModeUi();
		updateScopeHint();
	}

	/* 刷新分流卡片下方的提示。它始终说明"VPN 作用域（全局/部分应用）先于分流策略
	   生效"；当"部分应用"却没勾任何应用时（隧道将捕获不到任何流量）变成警告。 */
	private void updateScopeHint() {
		if (textview_scope_hint == null)
		  return;
		boolean global = checkbox_global.isChecked();
		if (global) {
			textview_scope_hint.setText(R.string.routing_scope_hint);
			textview_scope_hint.setTextColor(scopeHintColor);
			return;
		}
		Set<String> apps = prefs.getApps();
		if (apps == null || apps.isEmpty()) {
			textview_scope_hint.setText(R.string.routing_scope_empty_warn);
			textview_scope_hint.setTextColor(scopeErrorColor);
		} else {
			textview_scope_hint.setText(R.string.routing_scope_hint);
			textview_scope_hint.setTextColor(scopeHintColor);
		}
	}

	/* 按当前分流策略启用/禁用规则编辑控件，并选择对应的模式提示。两个全局模式下
	   规则列表根本不参与匹配，还允许编辑会误导用户。 */
	private void updateModeUi() {
		boolean editable = !prefs.getEnable();
		String strat = prefs.getRulesStrategy();
		boolean rulesMode = Preferences.RULES_STRATEGY_RULES.equals(strat);
		boolean canEdit = editable && rulesMode;
		switch_default_proxy.setEnabled(canEdit);
		spinner_type.setEnabled(canEdit);
		spinner_action.setEnabled(canEdit);
		edit_value.setEnabled(canEdit);
		findViewById(R.id.rule_add).setEnabled(canEdit);
		if (Preferences.RULES_STRATEGY_GLOBAL.equals(strat))
		  textview_mode_hint.setText(R.string.rules_mode_hint_global);
		else if (Preferences.RULES_STRATEGY_DIRECT.equals(strat))
		  textview_mode_hint.setText(R.string.rules_mode_hint_direct);
		else
		  textview_mode_hint.setText(R.string.rules_mode_hint_rules);
	}

	private void applyDnsEnabled(boolean editable, boolean remote) {
		edittext_dns_ipv4.setEnabled(editable && !remote);
		edittext_dns_ipv6.setEnabled(editable && !remote);
		til_dns_ipv4.setEnabled(editable && !remote);
		til_dns_ipv6.setEnabled(editable && !remote);
	}

	@Override
	protected void onPause() {
		super.onPause();
		savePrefs();
		prefs.setRules(rules);
	}

	@Override
	protected void onResume() {
		super.onResume();
		/* 应用列表是在另一个页面编辑的，回到本页要重新同步提示
		   （刚才"空的部分应用范围"现在可能已经有应用了，反之亦然）。 */
		updateScopeHint();
	}

	private void savePrefs() {
		prefs.setGlobal(checkbox_global.isChecked());
		prefs.setIpv4(checkbox_ipv4.isChecked());
		prefs.setIpv6(checkbox_ipv6.isChecked());
		prefs.setUdpInTcp(checkbox_udp_in_tcp.isChecked());
		prefs.setRemoteDns(checkbox_remote_dns.isChecked());
		prefs.setDnsIpv4(edittext_dns_ipv4.getText().toString());
		prefs.setDnsIpv6(edittext_dns_ipv6.getText().toString());
	}

	@Override
	public void onClick(View view) {
		if (view == button_apps) {
			startActivity(new Intent(this, AppListActivity.class));
			return;
		}
		if (view == checkbox_global) {
			boolean global = checkbox_global.isChecked();
			prefs.setGlobal(global);
			button_apps.setEnabled(!global);
			updateScopeHint();
			if (!global) {
				Set<String> apps = prefs.getApps();
				if (apps == null || apps.isEmpty())
				  Toast.makeText(RulesHubActivity.this,
					R.string.routing_scope_empty_warn, Toast.LENGTH_LONG).show();
			}
			/* 全局 vs 部分应用的作用域是在**建立隧道**时决定的，所以在已连接状态切换
			   它必须重建隧道才能生效。 */
			if (prefs.getEnable()) {
				startService(new Intent(this, TProxyService.class)
					.setAction(TProxyService.ACTION_RECONNECT));
				Toast.makeText(this, R.string.apps_applied_restart, Toast.LENGTH_LONG).show();
			}
			return;
		}
		if (view == checkbox_remote_dns) {
			applyDnsEnabled(!prefs.getEnable(), checkbox_remote_dns.isChecked());
			return;
		}
		addRule();
	}

	private String typeLabel(int type) {
		switch (type) {
			case Preferences.Rule.TYPE_DOMAIN:
				return getString(R.string.rule_type_domain);
			case Preferences.Rule.TYPE_IP:
				return getString(R.string.rule_type_ip);
			case Preferences.Rule.TYPE_CIDR:
				return getString(R.string.rule_type_cidr);
			case Preferences.Rule.TYPE_KEYWORD:
				return getString(R.string.rule_type_keyword);
			case Preferences.Rule.TYPE_GEOIP:
				return getString(R.string.rule_type_geoip);
			case Preferences.Rule.TYPE_PROCESS:
				return getString(R.string.rule_type_process);
			case Preferences.Rule.TYPE_DOMAIN_FULL:
				return getString(R.string.rule_type_domain_full);
			case Preferences.Rule.TYPE_GEOSITE:
				return getString(R.string.rule_type_geosite);
			case Preferences.Rule.TYPE_PROCESS_PATH:
				return getString(R.string.rule_type_process_path);
			case Preferences.Rule.TYPE_DST_PORT:
				return getString(R.string.rule_type_dst_port);
			case Preferences.Rule.TYPE_SRC_PORT:
				return getString(R.string.rule_type_src_port);
			case Preferences.Rule.TYPE_NETWORK:
				return getString(R.string.rule_type_network);
			default:
				return "?";
		}
	}

	/* 值输入框的 hint 跟着所选规则类型走，把期望格式直接摆出来，不用用户猜。 */
	private void updateValueHint(int type) {
		String[] hints = getResources().getStringArray(R.array.rule_value_hints);
		if (type >= 0 && type < hints.length)
		  til_rule_value.setHint(hints[type]);
		else
		  til_rule_value.setHint(R.string.rule_value_hint);
	}

	private void renderRules() {
		rules_list.removeAllViews();
		LayoutInflater inflater = LayoutInflater.from(this);

		for (int i = 0; i < rules.size(); i++) {
			final int index = i;
			Preferences.Rule rule = rules.get(i);
			View row = inflater.inflate(R.layout.ruleitem, rules_list, false);

			((TextView) row.findViewById(R.id.type)).setText(typeLabel(rule.type));
			((TextView) row.findViewById(R.id.value)).setText(rule.value);
			TextView action = (TextView) row.findViewById(R.id.action);
			action.setText(rule.proxy
				? R.string.rule_action_proxy : R.string.rule_action_direct);
			action.setTextColor(rule.proxy ? ruleProxyColor : ruleDirectColor);
			ImageButton delete = (ImageButton) row.findViewById(R.id.delete);
			delete.setEnabled(!prefs.getEnable());
			delete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					rules.remove(index);
					prefs.setRules(rules);
					renderRules();
				}
			});

			rules_list.addView(row);
		}

		rules_empty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void addRule() {
		String value = edit_value.getText().toString().trim();
		int type = spinner_type.getSelectedItemPosition();
		boolean proxy = spinner_action.getSelectedItemPosition() == 0;

		if (!checkValue(type, value)) {
			Toast.makeText(this, R.string.rule_invalid, Toast.LENGTH_SHORT).show();
			return;
		}
		for (Preferences.Rule rule : rules) {
			if (rule.type == type && rule.value.equals(value)) {
				Toast.makeText(this, R.string.rule_exists, Toast.LENGTH_SHORT).show();
				return;
			}
		}

		rules.add(new Preferences.Rule(type, value, proxy));
		prefs.setRules(rules);
		edit_value.setText("");
		renderRules();
	}

	/* 按所选规则类型校验值。'|' 与 ';' 一律拒绝，因为规则持久化时用的就是它们做
	   字段 / 记录分隔符。 */
	private boolean checkValue(int type, String value) {
		if (value.isEmpty() || value.indexOf('|') >= 0 || value.indexOf(';') >= 0)
		  return false;
		switch (type) {
			case Preferences.Rule.TYPE_DOMAIN:
				/* 域名后缀：不能带斜杠、也不能带前缀。 */
				return value.indexOf('/') < 0;
			case Preferences.Rule.TYPE_IP:
				if (value.indexOf('/') >= 0)
				  return false;
				return looksLikeIp(value);
			case Preferences.Rule.TYPE_CIDR: {
				int slash = value.lastIndexOf('/');
				if (slash <= 0)
				  return false;
				String addr = value.substring(0, slash);
				int prefix;
				try {
					prefix = Integer.parseInt(value.substring(slash + 1));
				} catch (NumberFormatException e) {
					return false;
				}
				if (!looksLikeIp(addr))
				  return false;
				boolean v6 = addr.indexOf(':') >= 0;
				return prefix >= 0 && prefix <= (v6 ? 128 : 32);
			}
			case Preferences.Rule.TYPE_KEYWORD:
				/* 域名里任意一段非空子串都算合法。 */
				return true;
			case Preferences.Rule.TYPE_GEOIP:
				/* ISO-3166 两位国家码。 */
				return value.matches("^[A-Za-z]{2}$");
			case Preferences.Rule.TYPE_PROCESS:
				return true;
			case Preferences.Rule.TYPE_DOMAIN_FULL:
				/* 精确域名：不能带路径，也不能以点开头。 */
				return value.indexOf('/') < 0 && !value.startsWith(".");
			case Preferences.Rule.TYPE_GEOSITE:
				/* geosite 标签，例如 "geolocation-cn" 或 "openai"。 */
				return value.matches("^[A-Za-z0-9_\\-]+$");
			case Preferences.Rule.TYPE_PROCESS_PATH:
				return true;
			case Preferences.Rule.TYPE_DST_PORT:
			case Preferences.Rule.TYPE_SRC_PORT:
				return value.matches("^\\d{1,5}(-\\d{1,5})?$");
			case Preferences.Rule.TYPE_NETWORK: {
				String v = value.toLowerCase();
				return v.equals("tcp") || v.equals("udp")
					|| v.equals("tcp,udp") || v.equals("udp,tcp");
			}
			default:
				return false;
		}
	}

	/* 字面量 IPv4（四段 0-255）或 IPv6（含 ':'）才返回 true。域名一律拒绝，这样
	   "IP" 规则永远不会触发 DNS 查询。 */
	private static boolean looksLikeIp(String s) {
		if (s.indexOf(':') >= 0)
		  return true;
		String[] p = s.split("\\.");
		if (p.length != 4)
		  return false;
		for (String x : p) {
			try {
				int v = Integer.parseInt(x);
				if (v < 0 || v > 255)
				  return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

}
