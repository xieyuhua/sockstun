/*
 ============================================================================
 Name        : ConnectionsActivity.java
 Description : Live view of the core's connection table - what each request is
               talking to, which rule matched it, which node carries it, how
               much it has moved, plus an app filter, a process column and a
               "close everything" action.
 ============================================================================
*/

package com.tunvpn;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ConnectionsActivity extends BaseActivity {
	/* The core reports what is open right now, so a short interval is what
	   makes this screen feel live. It is a loopback request, so it is cheap. */
	private static final long REFRESH_INTERVAL = 1500;

	private ListView listview;
	private TextView textview_summary;
	private TextView textview_empty;
	private RowAdapter adapter;
	private EditText searchBox;
	private final Handler ui = new Handler(Looper.getMainLooper());
	private Handler timer;
	private Runnable task;
	private final List<Row> allRows = new ArrayList<Row>();
	private final List<Row> rows = new ArrayList<Row>();
	private String query = "";
	private boolean loading = false;
	/* The real reason the last fetch failed, surfaced on screen instead of a
	   generic "unavailable" so a silent empty list is actually diagnosable.
	   Empty when the last fetch succeeded. */
	private String lastError = "";

	/* One row of the connection table, reduced to what is worth showing. */
	private static class Row {
		String target;
		String route;
		String process;
		long up;
		long down;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_connections);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		listview = (ListView) findViewById(R.id.conn_list);
		textview_summary = (TextView) findViewById(R.id.conn_summary);
		textview_empty = (TextView) findViewById(R.id.conn_empty);
		adapter = new RowAdapter();
		listview.setAdapter(adapter);

		searchBox = (EditText) findViewById(R.id.conn_search);
		searchBox.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				query = s != null ? s.toString() : "";
				applyFilter();
			}

			@Override
			public void afterTextChanged(android.text.Editable s) {
			}
		});

		MaterialButton closeAll = (MaterialButton) findViewById(R.id.conn_close_all);
		closeAll.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				closeAll();
			}
		});

		timer = new Handler(Looper.getMainLooper());
		task = new Runnable() {
			@Override
			public void run() {
				reload();
				timer.postDelayed(this, REFRESH_INTERVAL);
			}
		};
	}

	@Override
	protected void onStart() {
		super.onStart();
		timer.removeCallbacks(task);
		timer.post(task);
	}

	@Override
	protected void onStop() {
		super.onStop();
		timer.removeCallbacks(task);
	}

	private void reload() {
		/* Skip a tick rather than queueing requests when the core is slow. */
		if (loading)
		  return;
		loading = true;
		new Thread(new Runnable() {
			@Override
			public void run() {
				final List<Row> parsed = fetch();
				ui.post(new Runnable() {
					@Override
					public void run() {
						loading = false;
						if (isFinishing() || isDestroyed())
						  return;
						allRows.clear();
						if (parsed != null)
						  allRows.addAll(parsed);
						updateSummary(parsed != null);
						applyFilter();
					}
				});
			}
		}).start();
	}

	private void updateSummary(boolean available) {
		if (!available) {
			String msg = getString(R.string.connections_unavailable);
			if (!lastError.isEmpty())
			  msg += "（" + lastError + "）";
			textview_summary.setText(msg);
			return;
		}
		long up = 0;
		long down = 0;
		for (Row r : allRows) {
			up += r.up;
			down += r.down;
		}
		textview_summary.setText(getString(R.string.connections_summary, allRows.size(),
			TProxyService.formatBytes(up), TProxyService.formatBytes(down)));
	}

	/* Keep the list and the summary in sync with the search box. */
	private void applyFilter() {
		rows.clear();
		String q = query.trim().toLowerCase();
		if (q.isEmpty()) {
			rows.addAll(allRows);
		} else {
			for (Row r : allRows) {
				if (contains(r.target, q) || contains(r.route, q) || contains(r.process, q))
				  rows.add(r);
			}
		}
		adapter.notifyDataSetChanged();
		if (rows.isEmpty()) {
			textview_empty.setText(q.isEmpty() ? R.string.connections_empty : R.string.connections_no_match);
			textview_empty.setVisibility(View.VISIBLE);
		} else {
			textview_empty.setVisibility(View.GONE);
		}
	}

	private static boolean contains(String s, String q) {
		return s != null && s.toLowerCase().contains(q);
	}

	/* null when the core could not be asked at all (most likely it is not
	   running), as opposed to an empty list. */
	private List<Row> fetch() {
		lastError = "";
		String body = httpGet("http://127.0.0.1:" + MihomoConfig.API_PORT + "/connections");
		if (body == null) {
			if (lastError.isEmpty())
			  lastError = "无响应（核心可能未运行 / 端口未监听）";
			return null;
		}
		List<Row> out = new ArrayList<Row>();
		try {
			JSONArray arr = new JSONObject(body).optJSONArray("connections");
			if (arr == null)
			  return out;
			for (int i = 0; i < arr.length(); i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				Row r = new Row();
				JSONObject meta = c.optJSONObject("metadata");
				r.target = target(meta);
				r.route = route(c);
				r.process = process(meta);
				r.up = c.optLong("upload");
				r.down = c.optLong("download");
				out.add(r);
			}
		} catch (Exception e) {
			return null;
		}
		/* Busiest first: the connections worth looking at are the ones actually
		   moving data. */
		Collections.sort(out, new Comparator<Row>() {
			@Override
			public int compare(Row a, Row b) {
				long sa = a.up + a.down;
				long sb = b.up + b.down;
				if (sa != sb)
				  return sa < sb ? 1 : -1;
				return 0;
			}
		});
		return out;
	}

	private static String target(JSONObject meta) {
		if (meta == null)
		  return "";
		String host = meta.optString("host");
		if (host.isEmpty())
		  host = meta.optString("destinationIP");
		String port = meta.optString("destinationPort");
		if (host.isEmpty())
		  return port;
		return port.isEmpty() ? host : host + ":" + port;
	}

	/* The app that opened the socket, when the core could resolve it. Empty on
	   platforms where process tracking is unavailable, in which case the row
	   simply hides this line. */
	private static String process(JSONObject meta) {
		if (meta == null)
		  return "";
		return meta.optString("process");
	}

	/* "<rule> · <node chain>", so one line says both why the request went the
	   way it did and where it ended up. */
	private static String route(JSONObject c) {
		StringBuilder sb = new StringBuilder();
		String rule = c.optString("rule");
		if (!rule.isEmpty()) {
			String payload = c.optString("rulePayload");
			sb.append(rule);
			if (!payload.isEmpty())
			  sb.append('(').append(payload).append(')');
		}
		JSONArray chains = c.optJSONArray("chains");
		if (chains != null && chains.length() > 0) {
			if (sb.length() > 0)
			  sb.append(" · ");
			for (int i = 0; i < chains.length(); i++) {
				if (i > 0)
				  sb.append(" → ");
				sb.append(chains.optString(i));
			}
		}
		return sb.toString();
	}

	/* DELETE /connections drops every live connection. We confirm first because
	   it is a blunt instrument and the user may only mean to clear a few. */
	private void closeAll() {
		if (allRows.isEmpty())
		  return;
		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_connections)
			.setMessage(getString(R.string.connections_close_confirm, allRows.size()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					httpDelete("http://127.0.0.1:" + MihomoConfig.API_PORT + "/connections");
					reload();
					Toast.makeText(ConnectionsActivity.this,
						R.string.connections_close_toast, Toast.LENGTH_SHORT).show();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private String httpGet(String url) {
		lastError = "";
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				lastError = "HTTP " + code;
				return null;
			}
			StringBuilder sb = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null)
				  sb.append(line);
			}
			return sb.toString();
		} catch (Exception e) {
			lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
			return null;
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}

	private void httpDelete(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestMethod("DELETE");
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);
			conn.getResponseCode();
		} catch (Exception ignored) {
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}

	private class RowAdapter extends BaseAdapter {
		@Override
		public int getCount() {
			return rows.size();
		}

		@Override
		public Object getItem(int position) {
			return rows.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = convertView;
			if (row == null) {
				row = LayoutInflater.from(ConnectionsActivity.this)
					.inflate(R.layout.connection_item, parent, false);
			}
			Row r = rows.get(position);
			((TextView) row.findViewById(R.id.conn_target)).setText(r.target);
			((TextView) row.findViewById(R.id.conn_route)).setText(r.route);
			TextView pv = (TextView) row.findViewById(R.id.conn_process);
			if (r.process == null || r.process.isEmpty()) {
				pv.setVisibility(View.GONE);
			} else {
				pv.setVisibility(View.VISIBLE);
				pv.setText(r.process);
			}
			((TextView) row.findViewById(R.id.conn_traffic)).setText(
				"↑ " + TProxyService.formatBytes(r.up)
					+ "\n↓ " + TProxyService.formatBytes(r.down));
			return row;
		}
	}
}
