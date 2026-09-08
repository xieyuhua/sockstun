/*
 ============================================================================
 Name        : SubscribeActivity.java
 Description : Fetch a remote clash.yml subscription, list its SOCKS5 nodes,
               test latency and apply a node as the tunnel upstream.
 ============================================================================
 */

package hev.sockstun;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SubscribeActivity extends BaseActivity {
	private Preferences prefs;
	private EditText edittext_url;
	private MaterialButton button_fetch;
	private MaterialButton button_test_all;
	private ListView listview;
	private TextView textview_empty;
	private List<ClashNode> nodes = new ArrayList<ClashNode>();
	private NodeAdapter adapter;
	private final Handler ui = new Handler(Looper.getMainLooper());

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_subscribe);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		edittext_url = (EditText) findViewById(R.id.sub_url);
		button_fetch = (MaterialButton) findViewById(R.id.sub_fetch);
		button_test_all = (MaterialButton) findViewById(R.id.sub_test_all);
		listview = (ListView) findViewById(R.id.sub_list);
		textview_empty = (TextView) findViewById(R.id.sub_empty);

		edittext_url.setText(prefs.getSubUrl());

		adapter = new NodeAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		button_fetch.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				fetch();
			}
		});
		button_test_all.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				testAll();
			}
		});

		loadNodes();
	}

	private void loadNodes() {
		nodes.clear();
		nodes.addAll(ClashNode.decode(prefs.getSubNodes()));
		adapter.notifyDataSetChanged();
	}

	private void fetch() {
		final String url = edittext_url.getText().toString().trim();
		if (url.isEmpty()) {
			Toast.makeText(this, R.string.sub_invalid_url, Toast.LENGTH_SHORT).show();
			return;
		}
		prefs.setSubUrl(url);
		button_fetch.setEnabled(false);
		button_fetch.setText(R.string.sub_fetching);
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final String content = download(url);
					final List<ClashNode> parsed = ClashParser.parse(content);
					ui.post(new Runnable() {
						@Override
						public void run() {
							nodes.clear();
							nodes.addAll(parsed);
							adapter.notifyDataSetChanged();
							prefs.setSubNodes(ClashNode.encode(nodes));
							if (parsed.isEmpty())
								Toast.makeText(SubscribeActivity.this,
									R.string.sub_no_socks5, Toast.LENGTH_LONG).show();
							else
								Toast.makeText(SubscribeActivity.this,
									getString(R.string.sub_fetched, parsed.size()),
									Toast.LENGTH_SHORT).show();
						}
					});
				} catch (final Exception e) {
					ui.post(new Runnable() {
						@Override
						public void run() {
							Toast.makeText(SubscribeActivity.this,
								getString(R.string.sub_fetch_failed, e.getMessage()),
								Toast.LENGTH_LONG).show();
						}
					});
				} finally {
					ui.post(new Runnable() {
						@Override
						public void run() {
							button_fetch.setEnabled(true);
							button_fetch.setText(R.string.sub_fetch);
						}
					});
				}
			}
		}).start();
	}

	private String download(String urlStr) throws Exception {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "SocksTun");
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
			  throw new Exception("HTTP " + code);
			InputStream in = conn.getInputStream();
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null)
				sb.append(line).append('\n');
			return sb.toString();
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}

	private void testAll() {
		for (int i = 0; i < nodes.size(); i++)
		  testNode(i);
	}

	private void testNode(final int index) {
		final ClashNode n = nodes.get(index);
		new Thread(new Runnable() {
			@Override
			public void run() {
				long t = System.currentTimeMillis();
				try {
					Socket s = new Socket();
					s.connect(new InetSocketAddress(n.server, n.port), 3000);
					s.close();
					n.latency = System.currentTimeMillis() - t;
				} catch (Exception e) {
					n.latency = -2;
				}
				ui.post(new Runnable() {
					@Override
					public void run() {
						adapter.notifyDataSetChanged();
					}
				});
			}
		}).start();
	}

	private void useNode(final ClashNode n) {
		prefs.setSocksAddress(n.server);
		prefs.setSocksPort(n.port);
		prefs.setSocksUsername(n.username);
		prefs.setSocksPassword(n.password);
		prefs.setSubSelected(n.name);
		Toast.makeText(this, getString(R.string.sub_applied, n.name),
			Toast.LENGTH_SHORT).show();
		adapter.notifyDataSetChanged();
	}

	private class NodeAdapter extends android.widget.ArrayAdapter<ClashNode> {
		private final android.view.LayoutInflater inflater;

		NodeAdapter() {
			super(SubscribeActivity.this, R.layout.subscribelistitem, nodes);
			inflater = getLayoutInflater();
		}

		@Override
		public View getView(int position, View convertView, android.view.ViewGroup parent) {
			if (convertView == null)
				convertView = inflater.inflate(R.layout.subscribelistitem, parent, false);
			final ClashNode n = getItem(position);
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			Button use = (Button) convertView.findViewById(R.id.item_use);
			Button test = (Button) convertView.findViewById(R.id.item_test);

			name.setText(n.name);
			String lat;
			if (n.latency == -1)
			  lat = getString(R.string.sub_latency_untested);
			else if (n.latency == -2)
			  lat = getString(R.string.sub_latency_failed);
			else
			  lat = n.latency + " ms";
			detail.setText(n.server + ":" + n.port + "  ·  " + n.type + "  ·  " + lat);

			boolean selected = n.name.equals(prefs.getSubSelected());
			convertView.setBackgroundColor(selected ?
				MaterialColors.getColor(SubscribeActivity.this,
					com.google.android.material.R.attr.colorPrimaryContainer, 0) : 0);

			use.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					useNode(n);
				}
			});
			test.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					testNode(position);
				}
			});
			return convertView;
		}
	}
}
