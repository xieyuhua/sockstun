/*
 ============================================================================
 Name        : ServerListActivity.java
 Description : Manage the manually configured SOCKS5 servers: add, edit,
               delete and pick which one is enabled. Enabling one makes it
               the upstream, which overrides the subscription.
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public class ServerListActivity extends BaseActivity {
	private Preferences prefs;
	private ListView listview;
	private TextView textview_empty;
	private List<SocksServer> servers = new ArrayList<SocksServer>();
	private ServerAdapter adapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_server_list);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		listview = (ListView) findViewById(R.id.server_list);
		textview_empty = (TextView) findViewById(R.id.server_empty);

		adapter = new ServerAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		((MaterialButton) findViewById(R.id.server_add)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(ServerListActivity.this, ServerEditActivity.class));
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		loadServers();
	}

	private void loadServers() {
		servers.clear();
		servers.addAll(prefs.getSocksServers());
		adapter.notifyDataSetChanged();
	}

	private void enable(final SocksServer s) {
		prefs.setActiveSocksId(s.id);
		Toast.makeText(this, getString(R.string.server_enabled_msg, s.label()),
			Toast.LENGTH_LONG).show();
		adapter.notifyDataSetChanged();
	}

	private void openEditor(final SocksServer s) {
		Intent i = new Intent(this, ServerEditActivity.class);
		i.putExtra(ServerEditActivity.EXTRA_ID, s.id);
		startActivity(i);
	}

	private void confirmDelete(final SocksServer s) {
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.server_delete_confirm, s.label()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					removeServer(s.id);
					loadServers();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* Drop the entry and, if it was the enabled one, hand the upstream back
	   to the subscription. */
	public static void removeServer(Preferences prefs, String id) {
		List<SocksServer> list = prefs.getSocksServers();
		for (int i = 0; i < list.size(); i++) {
			if (id.equals(list.get(i).id)) {
				list.remove(i);
				break;
			}
		}
		prefs.setSocksServers(list);
		if (id.equals(prefs.getActiveSocksId()))
		  prefs.setActiveSocksId("");
	}

	private void removeServer(String id) {
		removeServer(prefs, id);
	}

	private class ServerAdapter extends ArrayAdapter<SocksServer> {
		private final android.view.LayoutInflater inflater;
		private final int colorSelected;

		ServerAdapter() {
			super(ServerListActivity.this, R.layout.serverlistitem, servers);
			inflater = getLayoutInflater();
			colorSelected = MaterialColors.getColor(ServerListActivity.this,
				com.google.android.material.R.attr.colorPrimaryContainer, 0);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null)
				convertView = inflater.inflate(R.layout.serverlistitem, parent, false);
			final SocksServer s = getItem(position);
			MaterialCardView card = (MaterialCardView) convertView;
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			TextView badge = (TextView) convertView.findViewById(R.id.item_badge);
			Button enable = (Button) convertView.findViewById(R.id.item_enable);
			Button edit = (Button) convertView.findViewById(R.id.item_edit);
			Button delete = (Button) convertView.findViewById(R.id.item_delete);

			name.setText(s.label());
			detail.setText(s.addr + ":" + s.port);

			boolean active = s.id.equals(prefs.getActiveSocksId());
			badge.setVisibility(active ? View.VISIBLE : View.GONE);
			card.setCardBackgroundColor(active ? colorSelected : Color.TRANSPARENT);
			enable.setEnabled(!active);

			enable.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					enable(s);
				}
			});
			edit.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					openEditor(s);
				}
			});
			delete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmDelete(s);
				}
			});
			return convertView;
		}
	}
}
