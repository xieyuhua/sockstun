/*
 ============================================================================
 文件名  : RecentRequestsActivity.java
 说明    : 已经关闭的连接的历史视图。内核只报告"此刻开着"的连接，所以 TProxyService
           会盯着 /connections，把消失的连接记成一条"谁去过哪里"，存下来；本页只负责
           读这份记录。
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

	/* 一条已关闭的请求，按原始字段保存，这样详情弹窗能展示记录器抓到的每一项信息。
	   route()/meta() 负责拼出列表行展示的组合字符串。 */
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

	/* 隧道运行期间记录器一直在写，所以每次回到本页都要刷新，而不是只刷一次。 */
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
		/* 流量大的排前面：用户要找的通常就是真正传了数据的那条。 */
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

	/* startMs 记录的是 SystemClock.elapsedRealtime()（开机以来的毫秒数），不是墙钟
	   时间戳。这里换算回真实的本地时间，让用户看到请求**发生在什么时候**，而不是一个
	   看不懂的开机时长。 */
	/* 这个方法在每行渲染、以及每次输入过滤时都会被逐行调用，
	   所以格式化器按线程复用一个（SimpleDateFormat 非线程安全）。 */
	private static final ThreadLocal<java.text.SimpleDateFormat> CLOCK_FMT =
		new ThreadLocal<java.text.SimpleDateFormat>() {
			@Override protected java.text.SimpleDateFormat initialValue() {
				return new java.text.SimpleDateFormat(
					"MM-dd HH:mm:ss", java.util.Locale.getDefault());
			}
		};

	private static String formatClock(long elapsedMs) {
		if (elapsedMs <= 0)
		  return "";
		long nowWall = System.currentTimeMillis();
		long nowElapsed = SystemClock.elapsedRealtime();
		long wallStart = nowWall - (nowElapsed - elapsedMs);
		return CLOCK_FMT.get().format(new java.util.Date(wallStart));
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

	/* 清空全部最近请求。既清持久化存储，也要让正在运行的服务丢掉它内存里的列表，
	   否则后台的定期落盘下一次就会把文件重新填满。 */
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

	/* 点某一行查看记录器为这条请求抓到的全部信息。各字段可选中，方便复制主机名或规则名。 */
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
