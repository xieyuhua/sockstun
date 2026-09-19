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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerListActivity extends BaseActivity {
	private Preferences prefs;
	private ListView listview;
	private TextView textview_empty;
	private List<SocksServer> servers = new ArrayList<SocksServer>();
	private ServerAdapter adapter;
	/* Bounded pool for latency tests (one socket per server at once). */
	private final ExecutorService testPool = Executors.newFixedThreadPool(8);

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

	@Override
	protected void onDestroy() {
		testPool.shutdownNow();
		super.onDestroy();
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
		private final int colorOk;
		private final int colorBad;
		private final int colorIdle;
		private final int colorSelected;

		ServerAdapter() {
			super(ServerListActivity.this, R.layout.serverlistitem, servers);
			inflater = getLayoutInflater();
			colorOk = MaterialColors.getColor(ServerListActivity.this,
				com.google.android.material.R.attr.colorPrimary, 0);
			colorBad = MaterialColors.getColor(ServerListActivity.this,
				com.google.android.material.R.attr.colorError, 0);
			colorIdle = MaterialColors.getColor(ServerListActivity.this,
				com.google.android.material.R.attr.colorOutline, 0);
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
			TextView status = (TextView) convertView.findViewById(R.id.item_status);
			Button test = (Button) convertView.findViewById(R.id.item_test);
			Button enable = (Button) convertView.findViewById(R.id.item_enable);

			name.setText(s.label());
			detail.setText(s.summary());

			boolean active = s.id.equals(prefs.getActiveSocksId());
			badge.setVisibility(active ? View.VISIBLE : View.GONE);
			card.setCardBackgroundColor(active ? colorSelected : Color.TRANSPARENT);

			/* Latency pill: mirror the subscription list's colour semantics
			   (-1 untested, -2 unreachable, >=0 latency in ms). */
			if (s.latency >= 0) {
				status.setText(s.latency + " ms");
				status.setTextColor(colorOk);
			} else if (s.latency == -2) {
				status.setText(getString(R.string.sub_status_fail));
				status.setTextColor(colorBad);
			} else {
				status.setText(getString(R.string.sub_status_untested));
				status.setTextColor(colorIdle);
			}

			enable.setText(active ? R.string.server_enabled : R.string.server_enable);
			enable.setEnabled(!active);
			enable.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					enable(s);
				}
			});
			test.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					testServer(s);
				}
			});

			/* Edit / delete move to a long-press menu, matching the
			   subscription list's uncluttered per-row layout. */
			card.setOnLongClickListener(new View.OnLongClickListener() {
				@Override
				public boolean onLongClick(View v) {
					showServerMenu(s);
					return true;
				}
			});
			return convertView;
		}
	}

	/* Long-press menu for a server row: edit or delete (the two actions that
	   used to be inline buttons). */
	private void showServerMenu(final SocksServer s) {
		new AlertDialog.Builder(this)
			.setTitle(s.label())
			.setItems(new CharSequence[] {
				getString(R.string.server_edit),
				getString(R.string.server_delete)
			}, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					if (which == 0) openEditor(s);
					else confirmDelete(s);
				}
			})
			.show();
	}

	/* Speed-test a SOCKS5 (or raw) upstream the same way the subscription list
	   tests a node: probe reachability and measure connect latency. These
	   servers are NOT part of mihomo's proxy pool (they ARE the upstream), so a
	   clash-api /delay is unavailable; instead we open a direct TCP connection
	   to host:port - the app's own traffic bypasses the VPN tunnel, so this is
	   a true end-to-end probe of the server's socket. Success => latency (ms),
	   failure (incl. an empty host) => -2 (unreachable). This is the only
	   availability verdict, matching ClashNode's latency semantics. */
	private void testServer(final SocksServer s) {
		testPool.execute(new Runnable() {
			@Override
			public void run() {
				long result = -2;
				if (s.addr != null && !s.addr.trim().isEmpty()) {
					int timeout = prefs.getProxyTestTimeout() * 1000;
					long start = System.currentTimeMillis();
					try {
						Socket sock = new Socket();
						sock.connect(new InetSocketAddress(s.addr.trim(), s.port), timeout);
						result = System.currentTimeMillis() - start;
						sock.close();
					} catch (Exception e) {
						result = -2;
					}
				}
				final long latency = result;
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						s.latency = latency;
						adapter.notifyDataSetChanged();
					}
				});
			}
		});
	}
}
