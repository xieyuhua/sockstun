/*
 ============================================================================
 Name        : TrafficActivity.java
 Description : Traffic statistics of the tunnel and of every proxied app
 ============================================================================
 */

package hev.sockstun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
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
import com.google.android.material.color.DynamicColors;

public class TrafficActivity extends AppCompatActivity {
	private static final long REFRESH_INTERVAL = 1500;

	private Preferences prefs;
	private PackageManager pm;
	private Handler handler;
	private Runnable refresher;
	private TextView textRealtime;
	private TextView textSession;
	private TextView textTotal;
	private ListView listView;
	private TrafficAdapter adapter;
	private final List<Entry> apps = new ArrayList<Entry>();

	private static class Entry {
		public String pkg;
		public String label;
		public ApplicationInfo info;
		public long tx;
		public long rx;
	}

	private class TrafficAdapter extends ArrayAdapter<Entry> {
		public TrafficAdapter(Context context) {
			super(context, R.layout.trafficitem);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			LayoutInflater inflater = (LayoutInflater) getContext()
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View rowView = inflater.inflate(R.layout.trafficitem, parent, false);

			Entry entry = getItem(position);
			((ImageView) rowView.findViewById(R.id.icon))
				.setImageDrawable(entry.info.loadIcon(pm));
			((TextView) rowView.findViewById(R.id.name)).setText(entry.label);
			((TextView) rowView.findViewById(R.id.package_name)).setText(entry.pkg);
			((TextView) rowView.findViewById(R.id.up))
				.setText("↑ " + TProxyService.formatBytes(entry.tx));
			((TextView) rowView.findViewById(R.id.down))
				.setText("↓ " + TProxyService.formatBytes(entry.rx));

			return rowView;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DynamicColors.applyToActivityIfAvailable(this);
		setContentView(R.layout.activity_traffic);

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

		textRealtime = (TextView) findViewById(R.id.stats_realtime);
		textSession = (TextView) findViewById(R.id.stats_session);
		textTotal = (TextView) findViewById(R.id.stats_total);

		adapter = new TrafficAdapter(this);
		listView = (ListView) findViewById(R.id.list);
		listView.setAdapter(adapter);
		listView.setEmptyView(findViewById(R.id.empty));

		((MaterialButton) findViewById(R.id.reset)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				prefs.resetStats();
				refresh();
			}
		});

		loadApps();

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

	/* Resolve the routed packages once, so refreshing only reads counters. */
	private void loadApps() {
		apps.clear();
		Set<String> routed = prefs.getRoutedApps(this);
		for (String pkg : routed) {
			try {
				ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
				Entry entry = new Entry();
				entry.pkg = pkg;
				entry.info = info;
				entry.label = info.loadLabel(pm).toString();
				apps.add(entry);
			} catch (PackageManager.NameNotFoundException e) {
			}
		}
		Collections.sort(apps, new Comparator<Entry>() {
			@Override
			public int compare(Entry a, Entry b) {
				return a.label.compareToIgnoreCase(b.label);
			}
		});
	}

	private String line(int labelId, String up, String down) {
		return getString(R.string.stats_line, getString(labelId), up, down);
	}

	private void refresh() {
		prefs = new Preferences(this);

		textRealtime.setText(line(R.string.stats_realtime,
			TProxyService.formatRate(prefs.getRateTx()),
			TProxyService.formatRate(prefs.getRateRx())));
		textSession.setText(line(R.string.stats_session,
			TProxyService.formatBytes(prefs.getSessionTx()),
			TProxyService.formatBytes(prefs.getSessionRx())));
		textTotal.setText(line(R.string.stats_total,
			TProxyService.formatBytes(prefs.getTotalTx()),
			TProxyService.formatBytes(prefs.getTotalRx())));

		boolean connected = prefs.getEnable();
		Map<String, long[]> base = Preferences.parseAppStats(prefs.getAppBase());
		Map<String, long[]> total = Preferences.parseAppStats(prefs.getAppTotal());

		List<Entry> shown = new ArrayList<Entry>();
		for (Entry entry : apps) {
			long[] t = total.get(entry.pkg);
			long tx = t != null ? t[0] : 0;
			long rx = t != null ? t[1] : 0;

			if (connected) {
				long[] b = base.get(entry.pkg);
				if (b != null) {
					tx += Math.max(TrafficStats.getUidTxBytes(entry.info.uid) - b[0], 0);
					rx += Math.max(TrafficStats.getUidRxBytes(entry.info.uid) - b[1], 0);
				}
			}

			if (tx + rx <= 0)
			  continue;
			entry.tx = tx;
			entry.rx = rx;
			shown.add(entry);
		}

		Collections.sort(shown, new Comparator<Entry>() {
			@Override
			public int compare(Entry a, Entry b) {
				long sa = a.tx + a.rx;
				long sb = b.tx + b.rx;
				if (sa != sb)
				  return sa < sb ? 1 : -1;
				return a.label.compareToIgnoreCase(b.label);
			}
		});

		adapter.setNotifyOnChange(false);
		adapter.clear();
		for (Entry entry : shown)
		  adapter.add(entry);
		adapter.notifyDataSetChanged();
	}
}
