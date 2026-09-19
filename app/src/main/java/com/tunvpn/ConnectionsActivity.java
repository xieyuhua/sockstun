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

import com.tunvpn.Preferences;

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
	/* clash-api bearer token source; re-read here so the Authorization header
	   on /connections matches the secret baked into the running config. */
	private Preferences prefs;

	/* One row of the connection table, reduced to what is worth showing. */
	private static class Row {
		String target;
		String route;
		String process;
		String time;
		long up;
		long down;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
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
				if (contains(r.target, q) || contains(r.route, q)
						|| contains(r.process, q) || contains(r.time, q))
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
		/* Read the snapshot the background traffic poll already captured. The
		   native action bridge supports only ONE in-flight call, and that poll
		   runs every 1s; issuing our own overlapping call made the earlier
		   result get dropped, so this screen stayed empty while the stats saw
		   connections. Reusing the poll's snapshot avoids the contention and
		   always shows exactly what the working poll produced. */
		String body = TProxyService.lastConnectionsSnapshot();
		boolean fromBridge = false;
		if (body == null) {
			/* Tunnel just started (no poll yet) or not running: fall back to a
			   one-off bridge call. This can race the poll, so only when empty. */
			body = TProxyService.apiAction("getConnections", null);
			fromBridge = true;
		}
		android.util.Log.d("ConnectionsActivity", "fetch body="
			+ (body == null ? "null" : ("len=" + body.length()))
			+ (fromBridge ? " (bridge)" : " (snapshot)"));
		if (body == null) {
			if (lastError.isEmpty())
			  lastError = "in-process 桥未返回（核心可能未就绪 / 未运行）";
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
				r.time = formatConnStart(c.optString("start", ""));
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

	/* The core stamps each connection with "start" as an RFC3339 string, e.g.
	   "2026-09-11T10:00:00.123456789+08:00" or "...Z". Parse it to a local
	   wall-clock time so the list can show when the connection began. The
	   timezone OFFSET must be honoured (the core reports the node's local time,
	   not UTC); the nanosecond fraction is dropped first because
	   SimpleDateFormat only reaches millis. */
	private static String formatConnStart(String iso) {
		if (iso == null || iso.isEmpty())
		  return "";
		String s = iso.trim();
		/* Drop the fractional seconds: ".digits" -> "". */
		int dot = s.indexOf('.');
		if (dot >= 0) {
			int end = dot + 1;
			while (end < s.length() && Character.isDigit(s.charAt(end)))
			  end++;
			s = s.substring(0, dot) + s.substring(end);
		}
		java.util.Date d = null;
		/* "XXX" parses both "Z" (UTC) and a numeric "+hh:mm"/"-hh:mm" offset;
		   the second pattern is a fallback for a bare local timestamp. */
		String[] patterns = { "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss" };
		for (String pattern : patterns) {
			try {
				java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
					pattern, java.util.Locale.US);
				in.setLenient(false);
				in.setTimeZone(java.util.TimeZone.getDefault());
				d = in.parse(s);
				break;
			} catch (Exception ignore) {
			}
		}
		if (d == null)
		  return "";
		java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
			"MM-dd HH:mm:ss", java.util.Locale.getDefault());
		return out.format(d);
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
					/* The DELETE goes through the (blocking) bridge, so it must
					   not run on the UI thread - a slow core would otherwise
					   trigger an ANR. Fire it off and let the next poll refresh. */
					new Thread(new Runnable() {
						@Override
						public void run() {
							httpDelete("http://127.0.0.1:" + MihomoConfig.API_PORT + "/connections");
							ui.post(new Runnable() {
								@Override
								public void run() {
									if (isFinishing() || isDestroyed())
									  return;
									reload();
									Toast.makeText(ConnectionsActivity.this,
										R.string.connections_close_toast, Toast.LENGTH_SHORT).show();
								}
							});
						}
					}).start();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void httpDelete(String url) {
		String path = url.indexOf('/', 7) >= 0 ? url.substring(url.indexOf('/', 7)) : "/";
		TProxyService.bridgeApi("DELETE", TProxyService.apiBaseHost(),
			MihomoConfig.API_PORT, path, null, prefs.getSecret());
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
			TextView tv = (TextView) row.findViewById(R.id.conn_time);
			if (r.time == null || r.time.isEmpty()) {
				tv.setVisibility(View.GONE);
			} else {
				tv.setVisibility(View.VISIBLE);
				tv.setText(r.time);
			}
			((TextView) row.findViewById(R.id.conn_traffic)).setText(
				"↑ " + TProxyService.formatBytes(r.up)
					+ "\n↓ " + TProxyService.formatBytes(r.down));
			return row;
		}
	}
}
