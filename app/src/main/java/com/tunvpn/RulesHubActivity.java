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

import android.content.Intent;
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
import com.google.android.material.textfield.TextInputLayout;

public class RulesHubActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;

	private CompoundButton checkbox_global;
	private CompoundButton checkbox_ipv4;
	private CompoundButton checkbox_ipv6;
	private CompoundButton checkbox_udp_in_tcp;
	private MaterialButton button_apps;

	private CompoundButton checkbox_remote_dns;
	private EditText edittext_dns_ipv4;
	private EditText edittext_dns_ipv6;
	private TextInputLayout til_dns_ipv4;
	private TextInputLayout til_dns_ipv6;

	private CompoundButton switch_default_proxy;
	private Spinner spinner_type;
	private Spinner spinner_action;
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
		edit_value = (EditText) findViewById(R.id.rule_value);
		rules_list = (LinearLayout) findViewById(R.id.rules_list);
		rules_empty = (TextView) findViewById(R.id.rules_empty);
		((MaterialButton) findViewById(R.id.rule_add)).setOnClickListener(this);

		setupSpinner(spinner_type, R.array.rule_types);
		setupSpinner(spinner_action, R.array.rule_actions);

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
		switch_default_proxy.setEnabled(editable);
		spinner_type.setEnabled(editable);
		spinner_action.setEnabled(editable);
		edit_value.setEnabled(editable);
		findViewById(R.id.rule_add).setEnabled(editable);

		button_apps.setEnabled(editable && !checkbox_global.isChecked());
		applyDnsEnabled(editable, checkbox_remote_dns.isChecked());
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
			return;
		}
		if (view == checkbox_remote_dns) {
			applyDnsEnabled(!prefs.getEnable(), checkbox_remote_dns.isChecked());
			return;
		}
		addRule();
	}

	private String typeLabel(int type) {
		if (type == Preferences.Rule.TYPE_DOMAIN)
		  return getString(R.string.rule_type_domain);
		if (type == Preferences.Rule.TYPE_CIDR)
		  return getString(R.string.rule_type_cidr);
		return getString(R.string.rule_type_ip);
	}

	private void renderRules() {
		rules_list.removeAllViews();
		LayoutInflater inflater = LayoutInflater.from(this);

		for (int i = 0; i < rules.size(); i++) {
			final int index = i;
			Preferences.Rule rule = rules.get(i);
			View row = inflater.inflate(R.layout.ruleitem, rules_list, false);

			((TextView) row.findViewById(R.id.value))
				.setText(typeLabel(rule.type) + "  " + rule.value);
			((TextView) row.findViewById(R.id.action))
				.setText(rule.proxy ? R.string.rule_action_proxy : R.string.rule_action_direct);
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

	private boolean checkValue(int type, String value) {
		if (value.isEmpty() || value.indexOf('|') >= 0 || value.indexOf(';') >= 0)
		  return false;

		if (type == Preferences.Rule.TYPE_DOMAIN)
		  return value.indexOf('/') < 0;

		String addr = value;
		int prefix = -1;
		int slash = value.lastIndexOf('/');
		if (slash > 0) {
			if (type != Preferences.Rule.TYPE_CIDR)
			  return false;
			addr = value.substring(0, slash);
			try {
				prefix = Integer.parseInt(value.substring(slash + 1));
			} catch (NumberFormatException e) {
				return false;
			}
			if (prefix < 0 || prefix > 128)
			  return false;
		} else if (type == Preferences.Rule.TYPE_CIDR) {
			return false;
		}

		try {
			InetAddress ia = InetAddress.getByName(addr);
			if (prefix > (ia instanceof Inet6Address ? 128 : 32))
			  return false;
		} catch (Exception e) {
			return false;
		}
		return true;
	}
}
