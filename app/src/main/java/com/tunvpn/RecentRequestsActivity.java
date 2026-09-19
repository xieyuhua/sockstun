/*
 ============================================================================
 Name        : RecentRequestsActivity.java
 Description : Historical view of connections that have already closed. The
               core only reports what is open *now*, so TProxyService watches
               for connections disappearing from /connections and records them
               here as "what went where". This screen just reads that list.
 ============================================================================
*/

package com.tunvpn;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RecentRequestsActivity extends BaseActivity {
	private ListView listview;
	private TextView textview_summary;
	private TextView textview_empty;
	private RowAdapter adapter;
	private EditText searchBox;
	private final List<Row> allRows = new ArrayList<Row>();
	private final List<Row> rows = new ArrayList<Row>();
	private String query = "";

	/* One closed request, kept as raw fields so the detail dialog can show
	   every piece of information the recorder captured. route()/meta() build
	   the composite strings the list rows display. */
	private static class Row {
		String target;
		String rule;
		String chain;
		String process;
		long up;
		long down;
		long startMs;
		long endMs;

		String route() {
			StringBuilder rb = new StringBuilder();
			if (!rule.isEmpty())
			  rb.append(rule);
			if (!chain.isEmpty()) {
				if (rb.length() > 0)
				  rb.append(" · ");
				rb.append(chain);
			}
			return rb.toString();
		}

		String meta() {
			StringBuilder mb = new StringBuilder();
			if (!process.isEmpty())
			  mb.append(process);
			long duration = Math.max(0, endMs - startMs);
			if (duration > 0) {
				if (mb.length() > 0)
				  mb.append(" · ");
				mb.append("时长 ").append(formatDuration(duration));
			}
			String t = formatClock(startMs);
			if (!t.isEmpty()) {
				if (mb.length() > 0)
				  mb.append(" · ");
				mb.append(t);
			}
			return mb.toString();
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_recent_requests);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		listview = (ListView) findViewById(R.id.req_list);
		textview_summary = (TextView) findViewById(R.id.req_summary);
		textview_empty = (TextView) findViewById(R.id.req_empty);
		adapter = new RowAdapter();
		listview.setAdapter(adapter);

		searchBox = (EditText) findViewById(R.id.req_search);
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

		MaterialButton clearBtn = (MaterialButton) findViewById(R.id.req_clear);
		clearBtn.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				clearAll();
			}
		});

		listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				if (position < 0 || position >= rows.size())
				  return;
				showDetail(rows.get(position));
			}
		});

		load();
	}

	/* The recorder keeps running while the tunnel is up, so refresh on every
	   resume rather than only once. */
	@Override
	protected void onResume() {
		super.onResume();
		load();
	}

	private void load() {
		List<Row> parsed = parse();
		allRows.clear();
		if (parsed != null)
		  allRows.addAll(parsed);
		updateSummary(parsed != null);
		applyFilter();
	}

	private List<Row> parse() {
		String raw = new Preferences(this).getRecentRequests();
		if (raw == null || raw.isEmpty())
		  return null;
		List<Row> out = new ArrayList<Row>();
		try {
			JSONArray arr = new JSONArray(raw);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.optJSONObject(i);
				if (o == null)
				  continue;
				Row r = new Row();
				r.target = o.optString("t", "");
				r.rule = o.optString("r", "");
				r.chain = o.optString("c", "");
				r.process = o.optString("p", "");
				r.up = o.optLong("u", 0);
				r.down = o.optLong("d", 0);
				r.startMs = o.optLong("s", 0);
				r.endMs = o.optLong("e", 0);
				out.add(r);
			}
		} catch (Exception e) {
			return null;
		}
		/* Busiest first: the request the user is usually hunting for is the one
		   that actually moved data. */
		Collections.sort(out, new Comparator<Row>() {
			@Override
			public int compare(Row a, Row b) {
				long sa = a.up + a.down, sb = b.up + b.down;
				if (sa != sb)
				  return sa < sb ? 1 : -1;
				return 0;
			}
		});
		return out;
	}

	private static String formatDuration(long ms) {
		long s = ms / 1000;
		if (s < 60)
		  return s + "s";
		long m = s / 60;
		if (m < 60)
		  return m + "m" + (s % 60) + "s";
		long h = m / 60;
		return h + "h" + (m % 60) + "m";
	}

	/* startMs is recorded as SystemClock.elapsedRealtime() (time since boot),
	   not a wall-clock stamp. Convert it back to a real local time so the user
	   sees "when" the request happened rather than an opaque uptime value. */
	private static String formatClock(long elapsedMs) {
		if (elapsedMs <= 0)
		  return "";
		long nowWall = System.currentTimeMillis();
		long nowElapsed = SystemClock.elapsedRealtime();
		long wallStart = nowWall - (nowElapsed - elapsedMs);
		java.text.DateFormat df = new java.text.SimpleDateFormat(
			"MM-dd HH:mm:ss", java.util.Locale.getDefault());
		return df.format(new java.util.Date(wallStart));
	}

	private void updateSummary(boolean available) {
		if (!available) {
			textview_summary.setText(R.string.recent_requests_unavailable);
			return;
		}
		textview_summary.setText(getString(R.string.recent_requests_summary, allRows.size()));
	}

	private void applyFilter() {
		rows.clear();
		String q = query.trim().toLowerCase();
		if (q.isEmpty()) {
			rows.addAll(allRows);
		} else {
			for (Row r : allRows) {
				if (contains(r.target, q) || contains(r.route(), q) || contains(r.meta(), q))
				  rows.add(r);
			}
		}
		adapter.notifyDataSetChanged();
		if (rows.isEmpty()) {
			textview_empty.setText(query.isEmpty()
				? R.string.recent_requests_empty : R.string.recent_requests_no_match);
			textview_empty.setVisibility(View.VISIBLE);
		} else {
			textview_empty.setVisibility(View.GONE);
		}
	}

	private static boolean contains(String s, String q) {
		return s != null && s.toLowerCase().contains(q);
	}

	/* Wipe the whole recent-requests history. We both clear the persisted store
	   and ask the running service to drop its in-memory list, otherwise the
	   background flush would refill the file on its next tick. */
	private void clearAll() {
		if (allRows.isEmpty()) {
			Toast.makeText(this, R.string.recent_requests_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_recent_requests)
			.setMessage(R.string.recent_requests_clear_confirm)
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					new Preferences(RecentRequestsActivity.this).setRecentRequests("[]");
					TProxyService.clearRecentRequests();
					load();
					Toast.makeText(RecentRequestsActivity.this,
						R.string.recent_requests_cleared, Toast.LENGTH_SHORT).show();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* Tap a row to see everything the recorder captured for that request.
	   Values are selectable so the user can copy a host or rule. */
	private void showDetail(Row r) {
		ScrollView sv = new ScrollView(this);
		LinearLayout ll = new LinearLayout(this);
		ll.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(16);
		ll.setPadding(pad, dp(4), pad, dp(4));
		addDetailRow(ll, R.string.request_detail_target, r.target);
		if (!r.rule.isEmpty())
		  addDetailRow(ll, R.string.request_detail_rule, r.rule);
		if (!r.chain.isEmpty())
		  addDetailRow(ll, R.string.request_detail_chain, r.chain);
		if (!r.process.isEmpty())
		  addDetailRow(ll, R.string.request_detail_process, r.process);
		addDetailRow(ll, R.string.request_detail_upload, TProxyService.formatBytes(r.up));
		addDetailRow(ll, R.string.request_detail_download, TProxyService.formatBytes(r.down));
		addDetailRow(ll, R.string.request_detail_start, formatClock(r.startMs));
		long duration = Math.max(0, r.endMs - r.startMs);
		if (duration > 0)
		  addDetailRow(ll, R.string.request_detail_duration, formatDuration(duration));
		sv.addView(ll);
		new AlertDialog.Builder(this)
			.setTitle(R.string.request_detail_title)
			.setView(sv)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	private void addDetailRow(LinearLayout parent, int labelRes, String value) {
		if (value == null || value.isEmpty())
		  return;
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.VERTICAL);
		int vpad = dp(6);
		row.setPadding(0, vpad, 0, vpad);
		TextView label = new TextView(this);
		label.setText(getString(labelRes));
		label.setTextAppearance(this, android.R.style.TextAppearance_Material_Body2);
		TextView val = new TextView(this);
		val.setText(value);
		val.setTextAppearance(this, android.R.style.TextAppearance_Material_Body1);
		val.setTextIsSelectable(true);
		row.addView(label);
		row.addView(val);
		parent.addView(row);
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
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
				row = LayoutInflater.from(RecentRequestsActivity.this)
					.inflate(R.layout.recent_request_item, parent, false);
			}
			Row r = rows.get(position);
			((TextView) row.findViewById(R.id.req_target)).setText(r.target);
			((TextView) row.findViewById(R.id.req_route)).setText(r.route());
			TextView mv = (TextView) row.findViewById(R.id.req_meta);
			String meta = r.meta();
			if (meta == null || meta.isEmpty()) {
				mv.setVisibility(View.GONE);
			} else {
				mv.setVisibility(View.VISIBLE);
				mv.setText(meta);
			}
			((TextView) row.findViewById(R.id.req_traffic)).setText(
				"↑ " + TProxyService.formatBytes(r.up)
					+ "\n↓ " + TProxyService.formatBytes(r.down));
			return row;
		}
	}
}
