/*
 ============================================================================
 Name        : ServerEditActivity.java
 Description : Add or edit one manually configured SOCKS5 server.
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

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

		((MaterialButton) findViewById(R.id.save)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				save();
			}
		});
		MaterialButton delete = (MaterialButton) findViewById(R.id.server_delete);
		delete.setVisibility(id.isEmpty() ? View.GONE : View.VISIBLE);
		delete.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				confirmDelete();
			}
		});

		if (id.isEmpty())
		  edit_port.setText("1080");
		else
		  load();
	}

	private void load() {
		for (SocksServer s : prefs.getSocksServers()) {
			if (id.equals(s.id)) {
				edit_name.setText(s.name);
				edit_addr.setText(s.addr);
				edit_port.setText(Integer.toString(s.port));
				edit_user.setText(s.user);
				edit_pass.setText(s.pass);
				return;
			}
		}
	}

	private void save() {
		String addr = edit_addr.getText().toString().trim();
		if (addr.isEmpty()) {
			Toast.makeText(this, R.string.server_addr_required, Toast.LENGTH_SHORT).show();
			return;
		}
		int port = 1080;
		try {
			port = Integer.parseInt(edit_port.getText().toString().trim());
		} catch (NumberFormatException e) {
		}

		String name = edit_name.getText().toString().trim();
		String user = edit_user.getText().toString().trim();
		String pass = edit_pass.getText().toString();
		List<SocksServer> list = prefs.getSocksServers();

		if (id.isEmpty()) {
			list.add(new SocksServer(SocksServer.newId(), name, addr, port, user, pass));
		} else {
			boolean found = false;
			for (int i = 0; i < list.size(); i++) {
				if (id.equals(list.get(i).id)) {
					list.set(i, new SocksServer(id, name, addr, port, user, pass));
					found = true;
					break;
				}
			}
			if (!found)
			  list.add(new SocksServer(id, name, addr, port, user, pass));
		}

		prefs.setSocksServers(list);
		Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show();
		finish();
	}

	private void confirmDelete() {
		new AlertDialog.Builder(this)
			.setMessage(R.string.server_delete_confirm_short)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					ServerListActivity.removeServer(prefs, id);
					finish();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}
}
