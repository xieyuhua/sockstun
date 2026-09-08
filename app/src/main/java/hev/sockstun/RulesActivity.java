/*
 ============================================================================
 Name        : RulesActivity.java
 Description : Routing rules (domain / ip / cidr -> proxy or direct)
 ============================================================================
 */

package hev.sockstun;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class RulesActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
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
		setContentView(R.layout.activity_rules);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				finish();
			}
		});

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

		boolean editable = !prefs.getEnable();
		switch_default_proxy.setEnabled(editable);
		spinner_type.setEnabled(editable);
		spinner_action.setEnabled(editable);
		edit_value.setEnabled(editable);
		findViewById(R.id.rule_add).setEnabled(editable);

		rules = prefs.getRules();
		renderRules();
	}

	@Override
	protected void onPause() {
		super.onPause();
		prefs.setRules(rules);
	}

	@Override
	public void onClick(View view) {
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
