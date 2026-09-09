/*
 ============================================================================
 Name        : SubscribeActivity.java
 Description : Fetch a remote clash.yml subscription, list every proxy node,
               latency-test them (available / unavailable), sort + filter and
               apply a node as the tunnel upstream.
 ============================================================================
 */

package hev.sockstun;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SubscribeActivity extends BaseActivity {
	private static final int SORT_DEFAULT = 0;
	private static final int SORT_LATENCY = 1;
	private static final int FILTER_ALL = 0;
	private static final int FILTER_OK = 1;
	private static final int FILTER_BAD = 2;

	private Preferences prefs;
	private TextView textview_current;
	private MaterialButton button_fetch;
	private MaterialButton button_test_all;
	private ListView listview;
	private TextView textview_empty;
	private TextView textview_stats;
	private Spinner spinner_sort;
	private Spinner spinner_filter;

	/* nodes = everything we parsed (source of truth, persisted)
	   shown = what the list displays after filtering + sorting */
	private List<ClashNode> nodes = new ArrayList<ClashNode>();
	private List<ClashNode> shown = new ArrayList<ClashNode>();
	private NodeAdapter adapter;
	private final Handler ui = new Handler(Looper.getMainLooper());

	private int sortMode = SORT_DEFAULT;
	private int filterMode = FILTER_ALL;
	private int pendingTests = 0;

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

		textview_current = (TextView) findViewById(R.id.sub_current);
		button_fetch = (MaterialButton) findViewById(R.id.sub_fetch);
		button_test_all = (MaterialButton) findViewById(R.id.sub_test_all);
		listview = (ListView) findViewById(R.id.sub_list);
		textview_empty = (TextView) findViewById(R.id.sub_empty);
		textview_stats = (TextView) findViewById(R.id.sub_stats);
		spinner_sort = (Spinner) findViewById(R.id.sub_sort);
		spinner_filter = (Spinner) findViewById(R.id.sub_filter);


		adapter = new NodeAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		setupSpinner(spinner_sort, R.array.sub_sort_options, true);
		setupSpinner(spinner_filter, R.array.sub_filter_options, false);

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
		/* Subscription management and the usage guide live in the toolbar
		   menu, so the page itself only shows the current subscription. */
		toolbar.inflateMenu(R.menu.subscribe_menu);
		toolbar.setOnMenuItemClickListener(new MaterialToolbar.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(android.view.MenuItem item) {
				int id = item.getItemId();
				if (id == R.id.action_subs)
				  startActivity(new Intent(SubscribeActivity.this, SubscribeConfigActivity.class));
				else if (id == R.id.action_help)
				  showHelp();
				return true;
			}
		});

		loadNodes();
		updateCurrent();
	}

	@Override
	protected void onResume() {
		super.onResume();
		updateCurrent();
	}

	/* Which subscription the node list below belongs to. */
	private void updateCurrent() {
		Subscription sub = prefs.getActiveSubscription();
		if (sub != null) {
			textview_current.setText(getString(R.string.subs_current, sub.label()));
			return;
		}
		String url = prefs.getSubUrl();
		if (url == null || url.trim().isEmpty()) {
			textview_current.setText(R.string.subs_none);
			return;
		}
		textview_current.setText(getString(R.string.subs_current, url.trim()));
	}

	private void setupSpinner(Spinner spinner, int arrayRes, final boolean isSort) {
		ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(this,
			arrayRes, android.R.layout.simple_spinner_item);
		a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(a);
		spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (isSort)
				  sortMode = position;
				else
				  filterMode = position;
				applyView();
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
	}

	private void loadNodes() {
		nodes.clear();
		nodes.addAll(ClashNode.decode(prefs.getSubNodes()));
		applyView();
	}

	/* Rebuild the visible list from nodes according to filter + sort. */
	private void applyView() {
		shown.clear();
		for (ClashNode n : nodes) {
			if (filterMode == FILTER_OK && !isAvailable(n))
			  continue;
			if (filterMode == FILTER_BAD && !isBroken(n))
			  continue;
			shown.add(n);
		}
		if (sortMode == SORT_LATENCY)
		  Collections.sort(shown, latencyComparator);
		adapter.notifyDataSetChanged();
		updateStats();
	}

	private static boolean isAvailable(ClashNode n) {
		return n.latency >= 0;
	}

	private static boolean isBroken(ClashNode n) {
		return n.latency == -2;
	}

	/* Available nodes first by latency, then untested, then broken. */
	private static long rank(ClashNode n) {
		if (n.latency >= 0)
		  return n.latency;
		if (n.latency == -2)
		  return Long.MAX_VALUE;
		return Long.MAX_VALUE - 1;
	}

	private final Comparator<ClashNode> latencyComparator = new Comparator<ClashNode>() {
		@Override
		public int compare(ClashNode a, ClashNode b) {
			long ra = rank(a);
			long rb = rank(b);
			if (ra < rb)
			  return -1;
			if (ra > rb)
			  return 1;
			return 0;
		}
	};

	private void updateStats() {
		int ok = 0;
		int bad = 0;
		for (ClashNode n : nodes) {
			if (isAvailable(n))
			  ok++;
			else if (isBroken(n))
			  bad++;
		}
		textview_stats.setText(getString(R.string.sub_stats, ok, bad, nodes.size()));
	}

	private void saveNodes() {
		prefs.setSubNodes(ClashNode.encode(nodes));
	}

	private void fetch() {
		Subscription sub = prefs.getActiveSubscription();
		final String url = (sub != null) ? sub.url : prefs.getSubUrl();
		if (url == null || url.trim().isEmpty()) {
			Toast.makeText(this, R.string.subs_none, Toast.LENGTH_SHORT).show();
			return;
		}
		prefs.setSubUrl(url.trim());
		button_fetch.setEnabled(false);
		button_fetch.setText(R.string.sub_fetching);
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					final String content = download(url);
					/* Keep the raw clash.yml (base64-decoded) for the mihomo
					   core, and also list every proxy for display + testing. */
					final String yaml = ClashParser.decodeRaw(content);
					final List<ClashNode> parsed = ClashParser.parseAll(yaml);
					ui.post(new Runnable() {
						@Override
						public void run() {
							nodes.clear();
							nodes.addAll(parsed);
							prefs.setSubRaw(yaml);
							saveNodes();
							applyView();
							if (parsed.isEmpty())
								Toast.makeText(SubscribeActivity.this,
									R.string.sub_no_nodes, Toast.LENGTH_LONG).show();
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
		if (nodes.isEmpty())
		  return;
		pendingTests = nodes.size();
		for (ClashNode n : nodes)
		  testNode(n, true);
	}

	/* batch = part of "test all": only refresh + persist once the last one
	   finishes, otherwise we would rewrite the whole cache per node. */
	private void testNode(final ClashNode n, final boolean batch) {
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
						if (batch) {
							pendingTests--;
							if (pendingTests > 0) {
								adapter.notifyDataSetChanged();
								return;
							}
						}
						saveNodes();
						applyView();
					}
				});
			}
		}).start();
	}

	private void useNode(final ClashNode n) {
		/* The mihomo core consumes the whole subscription, so "use" just
		   remembers which node the user picked; TProxyService asks mihomo to
		   select it in the subscription's proxy group when the tunnel starts. */
		prefs.setSubSelected(n.name);
		/* Picking a subscription node hands the upstream back to the
		   subscription, so any enabled SOCKS5 server is disabled. */
		prefs.setActiveSocksId("");
		String group = ClashParser.parseSelectorGroup(prefs.getSubRaw());
		String msg;
		if (group == null || group.isEmpty())
			msg = getString(R.string.sub_no_group);
		else if (prefs.getEnable()) {
			startService(new Intent(this, TProxyService.class)
				.setAction(TProxyService.ACTION_SELECT));
			msg = getString(R.string.sub_switched, n.name);
		} else
			msg = getString(R.string.sub_applied_hint, n.name);
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
		adapter.notifyDataSetChanged();
	}

	private void showHelp() {
		new AlertDialog.Builder(this)
			.setTitle(R.string.sub_help_title)
			.setMessage(R.string.sub_help_text)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private class NodeAdapter extends ArrayAdapter<ClashNode> {
		private final android.view.LayoutInflater inflater;
		private final int colorOk;
		private final int colorBad;
		private final int colorIdle;
		private final int colorSelected;

		NodeAdapter() {
			super(SubscribeActivity.this, R.layout.subscribelistitem, shown);
			inflater = getLayoutInflater();
			colorOk = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorPrimary, 0);
			colorBad = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorError, 0);
			colorIdle = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorOutline, 0);
			colorSelected = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorPrimaryContainer, 0);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null)
				convertView = inflater.inflate(R.layout.subscribelistitem, parent, false);
			final ClashNode n = getItem(position);
			MaterialCardView card = (MaterialCardView) convertView;
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			TextView status = (TextView) convertView.findViewById(R.id.item_status);
			TextView badge = (TextView) convertView.findViewById(R.id.item_badge);
			Button use = (Button) convertView.findViewById(R.id.item_use);
			Button test = (Button) convertView.findViewById(R.id.item_test);

			name.setText(n.name);
			detail.setText(n.server + ":" + n.port + "  ·  " + n.type);

			if (n.latency >= 0) {
				status.setText(n.latency + " ms");
				status.setTextColor(colorOk);
			} else if (n.latency == -2) {
				status.setText(getString(R.string.sub_status_fail));
				status.setTextColor(colorBad);
			} else {
				status.setText(getString(R.string.sub_status_untested));
				status.setTextColor(colorIdle);
			}

			boolean selected = n.name.equals(prefs.getSubSelected());
			card.setCardBackgroundColor(selected ? colorSelected : Color.TRANSPARENT);
			badge.setVisibility(selected ? View.VISIBLE : View.GONE);
			badge.setTextColor(colorOk);

			use.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					useNode(n);
				}
			});
			test.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					testNode(n, false);
				}
			});
			return convertView;
		}
	}
}
