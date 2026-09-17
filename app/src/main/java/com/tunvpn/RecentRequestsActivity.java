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

import android.os.Bundle;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;

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

	/* One closed request, reduced to what is worth showing. */
	private static class Row {
		String target;
		String route;   // matched rule + chain the request ended up on
		String meta;    // originating app + how long it lasted
		long up;
		long down;
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
				String rule = o.optString("r", "");
				String chain = o.optString("c", "");
				StringBuilder rb = new StringBuilder();
				if (!rule.isEmpty())
				  rb.append(rule);
				if (!chain.isEmpty()) {
					if (rb.length() > 0)
					  rb.append(" · ");
					rb.append(chain);
				}
				r.route = rb.toString();

				String process = o.optString("p", "");
				long duration = Math.max(0, o.optLong("e", 0) - o.optLong("s", 0));
				StringBuilder mb = new StringBuilder();
				if (!process.isEmpty())
				  mb.append(process);
				if (duration > 0) {
					if (mb.length() > 0)
					  mb.append(" · ");
					mb.append("时长 ").append(formatDuration(duration));
				}
				r.meta = mb.toString();

				r.up = o.optLong("u", 0);
				r.down = o.optLong("d", 0);
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
				if (contains(r.target, q) || contains(r.route, q) || contains(r.meta, q))
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
			((TextView) row.findViewById(R.id.req_route)).setText(r.route);
			TextView mv = (TextView) row.findViewById(R.id.req_meta);
			if (r.meta == null || r.meta.isEmpty()) {
				mv.setVisibility(View.GONE);
			} else {
				mv.setVisibility(View.VISIBLE);
				mv.setText(r.meta);
			}
			((TextView) row.findViewById(R.id.req_traffic)).setText(
				"↑ " + TProxyService.formatBytes(r.up)
					+ "\n↓ " + TProxyService.formatBytes(r.down));
			return row;
		}
	}
}
