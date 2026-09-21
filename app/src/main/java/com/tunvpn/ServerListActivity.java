/*
 ============================================================================
 文件名  : ServerListActivity.java
 说明    : 管理手动配置的上游服务器：新增 / 编辑 / 删除，以及选择启用哪一台。启用某台
           后它就成为上游，并**覆盖（忽略）订阅**。
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
	/* 有界的测速线程池（每台服务器同一时刻一个探测）。 */
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

	/* 删掉这条记录；如果删掉的正是启用中的那台，就把上游交还给订阅。 */
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

	/* 只把某台服务器的测速结果写回存储（不改动其它字段）。 */
	private void persistLatency(String id, long latency) {
		List<SocksServer> list = prefs.getSocksServers();
		for (SocksServer x : list) {
			if (id.equals(x.id)) {
				x.latency = latency;
				break;
			}
		}
		prefs.setSocksServers(list);
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

			/* 延迟胶囊：与订阅列表的颜色语义一致
			   （-1 未测速，-2 不可达，>=0 为延迟毫秒数）。 */
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

			/* 编辑 / 删除移到长按菜单里，与订阅列表一样保持每行清爽。 */
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

	/* 服务器行的长按菜单：编辑或删除（这两个动作以前是行内按钮）。 */
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

	/* 测这台上游服务器的速度，思路与订阅列表测节点一致：探测可达性并测连接延迟。
	   这些服务器**不在** mihomo 的代理池里（它们本身就是上游），所以用不了
	   clash-api 的 /delay；改为直接对 host:port 建一条 TCP 连接 —— App 自己的流量
	   不经过 VPN 隧道，所以这是对该服务器 socket 的真实端到端探测。成功 → 延迟（ms），
	   失败（含地址为空）→ -2（不可达）。这是唯一的可用性判定，与 ClashNode 的
	   延迟语义保持一致。 */
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
						/* 测速结果持久化，下次进列表不必重测。 */
						persistLatency(s.id, latency);
						adapter.notifyDataSetChanged();
					}
				});
			}
		});
	}
}
