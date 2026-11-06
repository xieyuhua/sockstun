/*
 ============================================================================
 文件名  : ServerEditActivity.java
 说明    : 新增 / 编辑一台手动上游服务器。SOCKS5 沿用原来的表单录入；其它 clash 协议
           （hysteria2 / vmess / vless / trojan / ss）则粘贴一段原始 clash 节点定义，
           由 MihomoConfig 原样发射。
 ============================================================================
 */

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
	private LinearLayout rawActions;
	private TextInputLayout rawLayout;

	/* 第 0 项是 SOCKS5（原来的行为）；其余是用 edit_raw 里的原始 YAML 块录入的
	   clash 代理类型。 */
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
		rawActions = (LinearLayout) findViewById(R.id.server_raw_actions);

		((Button) findViewById(R.id.server_copy_json)).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick(View v) { copyAsJson(); }
			});
		((Button) findViewById(R.id.server_copy_clash)).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick(View v) { copyAsClash(); }
			});
		((Button) findViewById(R.id.server_paste)).setOnClickListener(
			new View.OnClickListener() {
				@Override
				public void onClick(View v) { pasteFromClipboard(); }
			});

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

	/* 在 SOCKS5 表单与"原始块"编辑器之间切换；第一次选择非 SOCKS5 类型时预填一份模板。 */
	private void applyTypeView(String type) {
		boolean socks = "socks5".equals(type);
		socksFields.setVisibility(socks ? View.VISIBLE : View.GONE);
		rawLayout.setVisibility(socks ? View.GONE : View.VISIBLE);
		/* 复制 / 粘贴按钮只在"节点配置"可见时出现（SOCKS5 没有 raw）。 */
		rawActions.setVisibility(socks ? View.GONE : View.VISIBLE);
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

	/* 把当前"节点配置"一键复制成 JSON（clash flow map → JSON 对象）。 */
	private void copyAsJson() {
		String json = NodeFormat.toJson(edit_raw.getText().toString());
		if (json == null) {
			Toast.makeText(this, R.string.server_parse_failed, Toast.LENGTH_LONG).show();
			return;
		}
		copyText("node-json", json);
	}

	/* 把当前"节点配置"一键复制成 clash 格式（已是 clash，规整成单行 flow map）。 */
	private void copyAsClash() {
		String clash = NodeFormat.toClash(edit_raw.getText().toString());
		if (clash == null)
		  clash = edit_raw.getText().toString();
		copyText("node-clash", clash);
	}

	/* 从剪贴板粘贴：JSON 自动转成 clash flow map；clash 原样填入。 */
	private void pasteFromClipboard() {
		ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null || !cm.hasPrimaryClip()) {
			Toast.makeText(this, R.string.server_paste_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
		if (cs == null || cs.toString().trim().isEmpty()) {
			Toast.makeText(this, R.string.server_paste_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		String text = cs.toString().trim();
		if (NodeFormat.looksLikeJson(text)) {
			String clash = NodeFormat.toClash(text);
			if (clash == null) {
				Toast.makeText(this, R.string.server_parse_failed, Toast.LENGTH_LONG).show();
				return;
			}
			edit_raw.setText(clash);
			Toast.makeText(this, R.string.server_paste_json_done, Toast.LENGTH_SHORT).show();
		} else {
			edit_raw.setText(text);
			Toast.makeText(this, R.string.server_paste_clash_done, Toast.LENGTH_SHORT).show();
		}
	}

	private void copyText(String label, String text) {
		ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm != null)
		  cm.setPrimaryClip(ClipData.newPlainText(label, text));
		Toast.makeText(this, R.string.server_copied, Toast.LENGTH_SHORT).show();
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
		int port = 1080;
		try {
			port = Integer.parseInt(edit_port.getText().toString().trim());
		} catch (NumberFormatException e) {
		}
		int[] portHolder = new int[] { port };
		String addr = cleanAddr(edit_addr.getText().toString(), portHolder);
		port = portHolder[0];

		String name = edit_name.getText().toString().trim();
		String user = edit_user.getText().toString().trim();
		String pass = edit_pass.getText().toString();
		String type = TYPES[spinner_type.getSelectedItemPosition()];

		List<SocksServer> list = prefs.getSocksServers();
		/* 编辑的正是**当前启用中**的服务器时，运行中的内核仍在跑旧 config.yaml，
		   必须重建隧道才会用上新的地址/端口/账号（与「启用」时的处理一致）。 */
		SocksServer active = prefs.getActiveSocksServer();
		boolean activeEdited = !id.isEmpty() && active != null && id.equals(active.id);
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
			/* 原始块才是真正的配置（由 MihomoConfig 原样发射）；addr/port/user/pass
			   只是给列表显示用的尽力而为的提示。 */
			put(list, new SocksServer(id.isEmpty() ? SocksServer.newId() : id,
				name, addr, port, user, pass, type, raw));
		}

		prefs.setSocksServers(list);
		if (activeEdited && prefs.getEnable())
		  startService(new Intent(this, TProxyService.class)
			.setAction(TProxyService.ACTION_RECONNECT));
		Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show();
		finish();
	}

	/* 地址栏容忍"整个链接直接粘进来"：socks5://user:pass@host:1080/x、
	   host:1080、纯 IPv6。不处理的话 host:1080 会被当成 server 写进 YAML，
	   生成 `server: host:1080` —— 内核要么报 YAML 错，要么连到一个不存在的主机。
	   内嵌端口优先于表单里的端口（粘贴的完整地址才是用户的真实意图）。 */
	private static String cleanAddr(String raw, int[] portOut) {
		String s = raw == null ? "" : raw.trim();
		int scheme = s.indexOf("://");
		if (scheme >= 0)
		  s = s.substring(scheme + 3);
		int slash = s.indexOf('/');
		if (slash >= 0)
		  s = s.substring(0, slash);
		int at = s.lastIndexOf('@');
		if (at >= 0)
		  s = s.substring(at + 1);
		if (s.startsWith("[")) {
			int end = s.indexOf(']');
			if (end > 0) {
				String host = s.substring(1, end);
				if (s.length() > end + 1 && s.charAt(end + 1) == ':')
				  parsePort(s.substring(end + 2), portOut);
				return host;
			}
		}
		int colon = s.lastIndexOf(':');
		if (colon > 0 && colon == s.indexOf(':')
				&& s.substring(colon + 1).matches("\\d+")) {
			parsePort(s.substring(colon + 1), portOut);
			s = s.substring(0, colon);
		}
		return s;
	}

	private static void parsePort(String p, int[] portOut) {
		try {
			int v = Integer.parseInt(p);
			if (v > 0 && v <= 65535)
			  portOut[0] = v;
		} catch (NumberFormatException ignore) {
		}
	}

	/* 按 id 插入或替换，编辑时不会变成新增一条。 */
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
