/*
 ============================================================================
 Name        : ServerEditActivity.java
 Description : Add or edit one manually configured proxy server. SOCKS5 keeps
               the original form-based entry; other clash protocols
               (hysteria2 / vmess / vless / trojan / ss) are entered as a raw
               clash proxy block, which MihomoConfig emits verbatim.
 ============================================================================
 */

package com.tunvpn;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

public class ServerEditActivity extends BaseActivity {
	public static final String EXTRA_ID = "id";

	private Preferences prefs;
	private String id = "";
	private EditText edit_name;
	private EditText edit_addr;
	private EditText edit_port;
	private EditText edit_user;
	private EditText edit_pass;
	private Spinner spinner_type;
	private EditText edit_raw;
	private LinearLayout socksFields;
	private TextInputLayout rawLayout;

	/* Position 0 is SOCKS5 (the original behaviour); the rest are clash proxy
	   types entered as a raw YAML block in edit_raw. */
	private static final String[] TYPES = {
		"socks5", "hysteria2", "vmess", "vless", "trojan", "ss"
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_server_edit);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		String extra = getIntent().getStringExtra(EXTRA_ID);
		if (extra != null)
		  id = extra;

		edit_name = (EditText) findViewById(R.id.server_name);
		edit_addr = (EditText) findViewById(R.id.server_addr);
		edit_port = (EditText) findViewById(R.id.server_port);
		edit_user = (EditText) findViewById(R.id.server_user);
		edit_pass = (EditText) findViewById(R.id.server_pass);
		spinner_type = (Spinner) findViewById(R.id.server_type);
		edit_raw = (EditText) findViewById(R.id.server_raw);
		socksFields = (LinearLayout) findViewById(R.id.server_socks_fields);
		rawLayout = (TextInputLayout) findViewById(R.id.server_raw_layout);

		ArrayAdapter<String> typeAdapter = new ArrayAdapter<String>(this,
			android.R.layout.simple_spinner_item, TYPES);
		typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner_type.setAdapter(typeAdapter);
		spinner_type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long lid) {
				applyTypeView(TYPES[position]);
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		((MaterialButton) findViewById(R.id.save)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				save();
			}
		});

		if (id.isEmpty()) {
			edit_port.setText("1080");
			spinner_type.setSelection(0);
			applyTypeView("socks5");
		} else {
			load();
		}
	}

	/* Toggle between the SOCKS5 form and the raw-block editor, and pre-fill a
	   template the first time a non-SOCKS5 type is chosen. */
	private void applyTypeView(String type) {
		boolean socks = "socks5".equals(type);
		socksFields.setVisibility(socks ? View.VISIBLE : View.GONE);
		rawLayout.setVisibility(socks ? View.GONE : View.VISIBLE);
		if (!socks && edit_raw.getText().toString().trim().isEmpty())
		  edit_raw.setText(templateFor(type));
	}

	private static String templateFor(String type) {
		if ("hysteria2".equals(type))
			return "- {name: \"\", type: hysteria2, server: \"\", port: 443, "
				+ "auth: \"password\", sni: \"\", alpn: \"h3\", "
				+ "obfs: \"salamander\", obfs-password: \"\", skip-cert-verify: false}";
		if ("vmess".equals(type))
			return "- {name: \"\", type: vmess, server: \"\", port: 443, "
				+ "uuid: \"\", alterId: 0, cipher: \"auto\", tls: true, "
				+ "servername: \"\", network: \"ws\", ws-opts: {path: \"/\"}}";
		if ("vless".equals(type))
			return "- {name: \"\", type: vless, server: \"\", port: 443, "
				+ "uuid: \"\", tls: true, servername: \"\", network: \"ws\", "
				+ "ws-opts: {path: \"/\"}}";
		if ("trojan".equals(type))
			return "- {name: \"\", type: trojan, server: \"\", port: 443, "
				+ "password: \"\", sni: \"\", skip-cert-verify: false}";
		if ("ss".equals(type))
			return "- {name: \"\", type: ss, server: \"\", port: 443, "
				+ "cipher: \"aes-256-gcm\", password: \"\"}";
		return "";
	}

	private void load() {
		for (SocksServer s : prefs.getSocksServers()) {
			if (id.equals(s.id)) {
				edit_name.setText(s.name);
				edit_addr.setText(s.addr);
				edit_port.setText(Integer.toString(s.port));
				edit_user.setText(s.user);
				edit_pass.setText(s.pass);
				int idx = 0;
				for (int i = 0; i < TYPES.length; i++)
					if (TYPES[i].equals(s.type)) { idx = i; break; }
				spinner_type.setSelection(idx);
				edit_raw.setText(s.raw);
				applyTypeView(s.type);
				return;
			}
		}
	}

	private void save() {
		String addr = edit_addr.getText().toString().trim();
		int port = 1080;
		try {
			port = Integer.parseInt(edit_port.getText().toString().trim());
		} catch (NumberFormatException e) {
		}

		String name = edit_name.getText().toString().trim();
		String user = edit_user.getText().toString().trim();
		String pass = edit_pass.getText().toString();
		String type = TYPES[spinner_type.getSelectedItemPosition()];

		List<SocksServer> list = prefs.getSocksServers();
		if ("socks5".equals(type)) {
			if (addr.isEmpty()) {
				Toast.makeText(this, R.string.server_addr_required, Toast.LENGTH_SHORT).show();
				return;
			}
			put(list, new SocksServer(id.isEmpty() ? SocksServer.newId() : id,
				name, addr, port, user, pass, "socks5", ""));
		} else {
			String raw = edit_raw.getText().toString().trim();
			if (raw.isEmpty()) {
				Toast.makeText(this, R.string.server_raw_required, Toast.LENGTH_SHORT).show();
				return;
			}
			/* The raw block is the real config (emitted verbatim by
			   MihomoConfig); addr/port/user/pass are only best-effort hints for
			   the list display. */
			put(list, new SocksServer(id.isEmpty() ? SocksServer.newId() : id,
				name, addr, port, user, pass, type, raw));
		}

		prefs.setSocksServers(list);
		Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show();
		finish();
	}

	/* Insert or replace by id, so editing keeps the same entry. */
	private void put(List<SocksServer> list, SocksServer s) {
		for (int i = 0; i < list.size(); i++) {
			if (s.id.equals(list.get(i).id)) {
				list.set(i, s);
				return;
			}
		}
		list.add(s);
	}
}
