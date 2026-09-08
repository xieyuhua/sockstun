/*
 ============================================================================
 Name        : ConnActivity.java
 Description : Capture counters and the TCP/UDP connections of proxied apps
 ============================================================================
 */

package hev.sockstun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Collections;
import java.util.Map;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class ConnActivity extends BaseActivity {
	private static final long REFRESH_INTERVAL = 1500;
	private static final int MAX_APPS_SHOWN = 5;

	private Preferences prefs;
	private PackageManager pm;
	private Handler handler;
	private Runnable refresher;
	private TextView textNow;
	private TextView textTotal;
	private TextView textApps;
	private TextView textBlocked;
	private ListView listView;
	private ConnAdapter adapter;

	private static class Conn {
		public String proto;
		public String local;
		public String remote;
		public String state;
		public String pkg;
	}

	private class ConnAdapter extends ArrayAdapter<Conn> {
		public ConnAdapter(Context context) {
			super(context, R.layout.connitem);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			LayoutInflater inflater = (LayoutInflater) getContext()
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View rowView = inflater.inflate(R.layout.connitem, parent, false);

			Conn conn = getItem(position);
			((ImageView) rowView.findViewById(R.id.icon)).setImageDrawable(iconOf(conn.pkg));
			((TextView) rowView.findViewById(R.id.name)).setText(labelOf(conn.pkg));
			((TextView) rowView.findViewById(R.id.detail)).setText(
				conn.proto.toUpperCase() + "  " + conn.local + " → " + conn.remote +
				"  " + stateLabel(conn.state));

			return rowView;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_connections);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				finish();
			}
		});

		prefs = new Preferences(this);
		pm = getPackageManager();

		textNow = (TextView) findViewById(R.id.conn_now);
		textTotal = (TextView) findViewById(R.id.conn_total);
		textApps = (TextView) findViewById(R.id.conn_apps);
		textBlocked = (TextView) findViewById(R.id.conn_blocked);

		adapter = new ConnAdapter(this);
		listView = (ListView) findViewById(R.id.list);
		listView.setAdapter(adapter);
		listView.setEmptyView(findViewById(R.id.empty));

		((MaterialButton) findViewById(R.id.clear)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				prefs.resetConnections();
				refresh();
			}
		});

		handler = new Handler(Looper.getMainLooper());
		refresher = new Runnable() {
			@Override
			public void run() {
				refresh();
				handler.postDelayed(this, REFRESH_INTERVAL);
			}
		};
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
		handler.postDelayed(refresher, REFRESH_INTERVAL);
	}

	@Override
	protected void onPause() {
		super.onPause();
		handler.removeCallbacks(refresher);
	}

	private String labelOf(String pkg) {
		try {
			return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
		} catch (PackageManager.NameNotFoundException e) {
			return pkg;
		}
	}

	private android.graphics.drawable.Drawable iconOf(String pkg) {
		try {
			ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
			return info.loadIcon(pm);
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}

	private static String stateLabel(String state) {
		if ("01".equals(state))
		  return "ESTABLISHED";
		if ("02".equals(state))
		  return "SYN_SENT";
		if ("03".equals(state))
		  return "SYN_RECV";
		if ("04".equals(state))
		  return "FIN_WAIT1";
		if ("05".equals(state))
		  return "FIN_WAIT2";
		if ("06".equals(state))
		  return "TIME_WAIT";
		if ("07".equals(state))
		  return "CLOSE";
		if ("08".equals(state))
		  return "CLOSE_WAIT";
		if ("09".equals(state))
		  return "LAST_ACK";
		if ("0A".equalsIgnoreCase(state))
		  return "LISTEN";
		if ("0B".equalsIgnoreCase(state))
		  return "CLOSING";
		return state;
	}

	private void refresh() {
		prefs = new Preferences(this);

		int tcp = prefs.getConnTcp();
		int udp = prefs.getConnUdp();
		textNow.setText(getString(R.string.conn_now, tcp, udp, tcp + udp));
		textTotal.setText(getString(R.string.conn_total, prefs.getConnTotal()));
		textApps.setText(appSummary(Preferences.parseAppStats(prefs.getConnAppTotal())));
		textBlocked.setVisibility(prefs.getConnUnreadable() ? View.VISIBLE : View.GONE);

		List<Conn> conns = new ArrayList<Conn>();
		String list = prefs.getConnList();
		if (!list.isEmpty()) {
			for (String part : list.split(";")) {
				String[] f = part.split("\\|");
				if (f.length != 5)
				  continue;
				Conn conn = new Conn();
				conn.proto = f[0];
				conn.local = f[1];
				conn.remote = f[2];
				conn.state = f[3];
				conn.pkg = f[4];
				conns.add(conn);
			}
		}

		adapter.setNotifyOnChange(false);
		adapter.clear();
		for (Conn conn : conns)
		  adapter.add(conn);
		adapter.notifyDataSetChanged();
	}

	private String appSummary(Map<String, long[]> totals) {
		if (totals.isEmpty())
		  return "";

		List<Map.Entry<String, long[]>> entries =
			new ArrayList<Map.Entry<String, long[]>>(totals.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, long[]>>() {
			@Override
			public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
				long ca = a.getValue()[0];
				long cb = b.getValue()[0];
				if (ca != cb)
				  return ca < cb ? 1 : -1;
				return 0;
			}
		});

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < entries.size() && i < MAX_APPS_SHOWN; i++) {
			if (sb.length() > 0)
			  sb.append("  ·  ");
			sb.append(labelOf(entries.get(i).getKey()))
			  .append(' ')
			  .append(entries.get(i).getValue()[0]);
		}
		return sb.toString();
	}
}
