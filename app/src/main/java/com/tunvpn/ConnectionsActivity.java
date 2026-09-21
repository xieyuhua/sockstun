/*
 ============================================================================
 文件名  : ConnectionsActivity.java
 说明    : 内核连接表的实时视图：每条请求在跟谁通信、命中哪条规则、走哪个节点、已经
           传了多少流量；另有来源应用、搜索与「断开全部」。
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
	/* 内核只报告"此刻开着"的连接，所以刷新间隔要短，页面才有实时感。请求走的是回环，
	   开销很小。 */
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
	/* 上一次取数失败的**真实原因**，直接显示在界面上而不是笼统的"不可用"，这样
	   "列表莫名空白"才有得排查。上次成功时为空串。 */
	private String lastError = "";
	/* clash-api 令牌来源；这里重新读取，保证 /connections 请求里的 Authorization
	   头与运行中配置里烘焙的 secret 一致。 */
	private Preferences prefs;

	/* 连接表中的一行，只保留值得展示的字段。 */
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
		/* 内核慢的时候跳过这一次，而不是把请求排队堆起来。 */
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

	/* 让列表与顶部摘要跟着搜索框同步。 */
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

	/* 隧道没在运行时返回 null（与"列表为空"是两回事）。 */
	private List<Row> fetch() {
		lastError = "";
		/* 本页跑在**主进程**，而内核 / 动作桥 / 服务实例只存在于 :native 进程 ——
		   所以这里既调不了桥，也用不了 lastConnectionsSnapshot()。唯一的通道是采样线程
		   发布到 SharedPreferences 的精简快照；新建一个 Preferences 实例即可跨进程
		   重新读到它。 */
		Preferences p = new Preferences(this);
		if (!p.getEnable()) {
			lastError = "隧道未运行";
			return null;
		}
		String raw = p.getConnSnapshot();
		if (raw == null || raw.isEmpty()) {
			lastError = "尚未收到连接快照（服务可能刚启动）";
			return null;
		}
		List<Row> out = new ArrayList<Row>();
		try {
			JSONArray arr = new JSONArray(raw);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				Row r = new Row();
				r.target = c.optString("t", "");
				r.route = c.optString("r", "");
				r.process = c.optString("p", "");
				r.time = formatConnStart(c.optString("s", ""));
				r.up = c.optLong("u", 0);
				r.down = c.optLong("d", 0);
				out.add(r);
			}
		} catch (Exception e) {
			return null;
		}
		/* 流量大的排前面：真正值得看的连接就是正在传数据的那些。 */
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

	/* 内核给每条连接打的 "start" 是 RFC3339 字符串，例如
	   "2026-09-11T10:00:00.123456789+08:00" 或 "...Z"。这里把它解析成本地墙钟时间，
	   好让列表显示连接是什么时候建立的。时区**偏移必须保留**（内核报的是节点所在
	   地的本地时间，不是 UTC）；纳秒部分先去掉，因为 SimpleDateFormat 只能到毫秒。 */
	/* SimpleDateFormat 不是线程安全的，而这里是**每行**渲染都会走到的方法。
	   原来每行 new 最多 3 个（200 行的列表每秒整体刷新一次 = 每秒几百个对象），
	   改成每线程各持一份复用 —— 与 TestLog 的时间戳格式化同一思路。 */
	private static final ThreadLocal<java.text.SimpleDateFormat> CONN_IN_XXX =
		new ThreadLocal<java.text.SimpleDateFormat>() {
			@Override protected java.text.SimpleDateFormat initialValue() {
				java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
					"yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
				f.setLenient(false);
				f.setTimeZone(java.util.TimeZone.getDefault());
				return f;
			}
		};
	private static final ThreadLocal<java.text.SimpleDateFormat> CONN_IN_LOCAL =
		new ThreadLocal<java.text.SimpleDateFormat>() {
			@Override protected java.text.SimpleDateFormat initialValue() {
				java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
					"yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
				f.setLenient(false);
				f.setTimeZone(java.util.TimeZone.getDefault());
				return f;
			}
		};
	private static final ThreadLocal<java.text.SimpleDateFormat> CONN_OUT =
		new ThreadLocal<java.text.SimpleDateFormat>() {
			@Override protected java.text.SimpleDateFormat initialValue() {
				return new java.text.SimpleDateFormat(
					"MM-dd HH:mm:ss", java.util.Locale.getDefault());
			}
		};

	private static String formatConnStart(String iso) {
		if (iso == null || iso.isEmpty())
		  return "";
		String s = iso.trim();
		/* 去掉小数秒：".digits" → ""。 */
		int dot = s.indexOf('.');
		if (dot >= 0) {
			int end = dot + 1;
			while (end < s.length() && Character.isDigit(s.charAt(end)))
			  end++;
			s = s.substring(0, dot) + s.substring(end);
		}
		java.util.Date d = null;
		/* "XXX" 既能解析 "Z"（UTC），也能解析数字偏移 "+hh:mm"/"-hh:mm"；
		   第二种格式是"只有本地时间戳、没有时区"时的兜底。 */
		java.text.SimpleDateFormat[] ins = { CONN_IN_XXX.get(), CONN_IN_LOCAL.get() };
		for (java.text.SimpleDateFormat in : ins) {
			try {
				d = in.parse(s);
				break;
			} catch (Exception ignore) {
			}
		}
		if (d == null)
		  return "";
		return CONN_OUT.get().format(d);
	}

	/* DELETE /connections 会断开所有在途连接。因为它是一刀切的，而用户可能只想清掉
	   其中几条，所以先弹确认。 */
	private void closeAll() {
		if (allRows.isEmpty())
		  return;
		new AlertDialog.Builder(this)
			.setTitle(R.string.settings_connections)
			.setMessage(getString(R.string.connections_close_confirm, allRows.size()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					/* 这个 DELETE 走的是**会阻塞**的动作桥，所以绝不能放在 UI 线程：
					   内核慢一点就会触发 ANR。丢到后台线程，下一次轮询自然会刷新。 */
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
