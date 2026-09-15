/*
 ============================================================================
 Name        : RulesHubActivity.java
 Description : One page for the routing / DNS / rule settings, in the order
               they apply: routing → DNS → routing rules.
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
	/* Live hint under the routing card: explains the two routing layers, and
	   warns when "per-app" mode has no apps selected (empty tunnel scope). */
	private TextView textview_scope_hint;
	private int scopeHintColor;
	private int scopeErrorColor;
	/* Action colours for the rule rows: proxy = primary, direct = muted. */
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
	/* Value field's container, so its hint can follow the picked rule type. */
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

		/* --- routing --- */
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

		/* --- dns --- */
		checkbox_remote_dns = (CompoundButton) findViewById(R.id.remote_dns);
		edittext_dns_ipv4 = (EditText) findViewById(R.id.dns_ipv4);
		edittext_dns_ipv6 = (EditText) findViewById(R.id.dns_ipv6);
		til_dns_ipv4 = (TextInputLayout) findViewById(R.id.til_dns_ipv4);
		til_dns_ipv6 = (TextInputLayout) findViewById(R.id.til_dns_ipv6);
		checkbox_remote_dns.setOnClickListener(this);

		/* --- rules --- */
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
		/* The expected value differs per rule type, so the hint follows the
		   picker (it fires right away with position 0). */
		spinner_type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				updateValueHint(position);
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		/* Routing strategy: rule mode / global proxy / global direct. */
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
		checkbox_global.setEnabled(editable);
		checkbox_ipv4.setEnabled(editable);
		checkbox_ipv6.setEnabled(editable);
		checkbox_udp_in_tcp.setEnabled(editable);
		checkbox_remote_dns.setEnabled(editable);

		button_apps.setEnabled(editable && !checkbox_global.isChecked());
		applyDnsEnabled(editable, checkbox_remote_dns.isChecked());
		/* Rule controls depend on both editability and the chosen strategy, so
		   let updateModeUi() own them. */
		updateModeUi();
		updateScopeHint();
	}

	/* Refresh the hint under the routing card. It always explains that the
	   VPN scope (global / per-app) is evaluated before the routing strategy,
	   and turns into a warning when per-app mode has no apps selected (which
	   would leave the tunnel with zero captured traffic). */
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

	/* Enable/disable the rule-editing controls and pick the mode hint based on
	   the current routing strategy. In the two global modes the rule list is
	   ignored, so editing it would be misleading. */
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
		/* The app list is edited in a separate activity; re-sync the hint
		   (an empty per-app scope may now have apps, or vice versa). */
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
			boolean editable = !prefs.getEnable();
			button_apps.setEnabled(editable && !checkbox_global.isChecked());
			updateScopeHint();
			if (!checkbox_global.isChecked()) {
				Set<String> apps = prefs.getApps();
				if (apps == null || apps.isEmpty())
				  Toast.makeText(RulesHubActivity.this,
					R.string.routing_scope_empty_warn, Toast.LENGTH_LONG).show();
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

	/* The value field's hint follows the picked rule type, so the expected
	   format is visible instead of guessed. */
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

	/* Validate the value against the chosen rule type. '|' and ';' are rejected
	   because they are the field/record separators used when rules are stored. */
	private boolean checkValue(int type, String value) {
		if (value.isEmpty() || value.indexOf('|') >= 0 || value.indexOf(';') >= 0)
		  return false;
		switch (type) {
			case Preferences.Rule.TYPE_DOMAIN:
				/* A domain suffix: no slash, no prefix. */
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
				/* Any non-empty substring of a domain name. */
				return true;
			case Preferences.Rule.TYPE_GEOIP:
				/* ISO-3166 alpha-2 country code. */
				return value.matches("^[A-Za-z]{2}$");
			case Preferences.Rule.TYPE_PROCESS:
				return true;
			case Preferences.Rule.TYPE_DOMAIN_FULL:
				/* An exact domain: no path, no leading dot. */
				return value.indexOf('/') < 0 && !value.startsWith(".");
			case Preferences.Rule.TYPE_GEOSITE:
				/* A geosite tag, e.g. "geolocation-cn" or "openai". */
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

	/* True for a literal IPv4 (four 0-255 octets) or IPv6 (contains ':') address.
	   Hostnames are rejected so an "IP" rule never triggers a DNS lookup. */
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
