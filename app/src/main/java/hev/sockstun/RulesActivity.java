/*
 ============================================================================
 Name        : RulesActivity.java
 Description : Rules page: domain / IP / CIDR -> proxy or direct, plus the
               routing scope (global or per-app, protocol) and DNS options
               that used to live on their own screens.
 ============================================================================
 */

package hev.sockstun;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
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

public class RulesActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;

	private CompoundButton switch_default_proxy;
	private Spinner spinner_type;
	private Spinner spinner_action;
	private EditText edit_value;
	private LinearLayout rules_list;
	private TextView rules_empty;
	private List<Preferences.Rule> rules;

	private CompoundButton switch_global;
	private CompoundButton switch_ipv4;
	private CompoundButton switch_ipv6;
	private CompoundButton switch_udp_in_tcp;
	private Button button_apps;

	private CompoundButton switch_remote_dns;
	private EditText edittext_dns_ipv4;
	private EditText edittext_dns_ipv6;
	private TextInputLayout til_dns_ipv4;
	private TextInputLayout til_dns_ipv6;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_rules);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		setupBottomNav(R.id.nav_rules);

		/* --- rules --- */
		switch_default_proxy = (CompoundButton) findViewById(R.id.rules_default_proxy);
		spinner_type = (Spinner) findViewById(R.id.rule_type);
		spinner_action = (Spinner) findViewById(R.id.rule_action);
		edit_value = (EditText) findViewById(R.id.rule_value);
		rules_list = (LinearLayout) findViewById(R.id.rules_list);
		rules_empty = (TextView) findViewById(R.id.rules_empty);
		((MaterialButton) findViewById(R.id.rule_add)).setOnClickListener(this);

		switch_default_proxy.setChecked(prefs.getRulesDefaultProxy());
		switch_default_proxy.setOnCheckedChangeListener(
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setRulesDefaultProxy(checked);
				}
			});

		/* --- routing scope --- */
		switch_global = (CompoundButton) findViewById(R.id.global);
		switch_ipv4 = (CompoundButton) findViewById(R.id.ipv4);
		switch_ipv6 = (CompoundButton) findViewById(R.id.ipv6);
		switch_udp_in_tcp = (CompoundButton) findViewById(R.id.udp_in_tcp);
		button_apps = (Button) findViewById(R.id.apps);

		/* --- dns --- */
		switch_remote_dns = (CompoundButton) findViewById(R.id.remote_dns);
		edittext_dns_ipv4 = (EditText) findViewById(R.id.dns_ipv4);
		edittext_dns_ipv6 = (EditText) findViewById(R.id.dns_ipv6);
		til_dns_ipv4 = (TextInputLayout) findViewById(R.id.til_dns_ipv4);
		til_dns_ipv6 = (TextInputLayout) findViewById(R.id.til_dns_ipv6);

		((MaterialButton) findViewById(R.id.save)).setOnClickListener(this);
		button_apps.setOnClickListener(this);
		switch_global.setOnClickListener(this);
		switch_remote_dns.setOnClickListener(this);

		rules = prefs.getRules();
		loadUI();
		renderRules();
	}

	private void loadUI() {
		switch_global.setChecked(prefs.getGlobal());
		switch_ipv4.setChecked(prefs.getIpv4());
		switch_ipv6.setChecked(prefs.getIpv6());
		switch_udp_in_tcp.setChecked(prefs.getUdpInTcp());

		switch_remote_dns.setChecked(prefs.getRemoteDns());
		edittext_dns_ipv4.setText(prefs.getDnsIpv4());
		edittext_dns_ipv6.setText(prefs.getDnsIpv6());

		updateEditable();
	}

	/* Everything here only takes effect on the next connect, so the whole
	   page becomes read-only while the tunnel is up. */
	private void updateEditable() {
		boolean editable = !prefs.getEnable();

		switch_default_proxy.setEnabled(editable);
		spinner_type.setEnabled(editable);
		spinner_action.setEnabled(editable);
		edit_value.setEnabled(editable);
		findViewById(R.id.rule_add).setEnabled(editable);

		switch_global.setEnabled(editable);
		switch_ipv4.setEnabled(editable);
		switch_ipv6.setEnabled(editable);
		switch_udp_in_tcp.setEnabled(editable);
		button_apps.setEnabled(editable && !switch_global.isChecked());

		switch_remote_dns.setEnabled(editable);
		boolean dnsEditable = editable && !switch_remote_dns.isChecked();
		edittext_dns_ipv4.setEnabled(dnsEditable);
		edittext_dns_ipv6.setEnabled(dnsEditable);
		til_dns_ipv4.setEnabled(dnsEditable);
		til_dns_ipv6.setEnabled(dnsEditable);

		renderRules();
	}

	@Override
	protected void onPause() {
		super.onPause();
		savePrefs();
	}

	@Override
	public void onClick(View view) {
		if (view == button_apps) {
			startActivity(new Intent(this, AppListActivity.class));
			return;
		}
		if (view == switch_global || view == switch_remote_dns) {
			updateEditable();
			return;
		}
		if (view.getId() == R.id.save) {
			savePrefs();
			Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
			return;
		}
		addRule();
	}

	private void savePrefs() {
		prefs.setRules(rules);
		prefs.setGlobal(switch_global.isChecked());
		prefs.setIpv4(switch_ipv4.isChecked());
		prefs.setIpv6(switch_ipv6.isChecked());
		prefs.setUdpInTcp(switch_udp_in_tcp.isChecked());
		prefs.setRemoteDns(switch_remote_dns.isChecked());
		prefs.setDnsIpv4(edittext_dns_ipv4.getText().toString());
		prefs.setDnsIpv6(edittext_dns_ipv6.getText().toString());
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
