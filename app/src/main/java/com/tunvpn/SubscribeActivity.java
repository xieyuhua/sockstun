/*
 ============================================================================
 Name        : SubscribeActivity.java
 Description : Fetch a remote clash.yml subscription, list every proxy node,
               latency-test them (available / unavailable), sort + filter and
               apply a node as the tunnel upstream.
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.tunvpn.GeoIp;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscribeActivity extends BaseActivity {
	/* Spinner position 0 is the default, and "fastest first" is what people
	   want, so latency is index 0 and subscription order index 1. */

	/* Bounds for the auto re-test interval, in minutes. */
	private static final int MIN_INTERVAL_MIN = 1;
	private static final int MAX_INTERVAL_MIN = 1440;

	private Preferences prefs;
	private FloatingActionButton fab_test_all;
	private ListView listview;
	private TextView textview_empty;
	private TextView textview_stats;
	private SwitchMaterial switch_auto;
	private TextView textview_auto_hint;
	private TextView textview_test_url;
	/* Country chips: a single row the user swipes sideways; "" = all countries
	   (= every proxy in the pool). The picked country actually narrows the
	   tunnel's node pool (MihomoConfig.mergedConfig), so there is no separate
	   auto-select country picker anymore. */
	/* Country filter for the node list: an extra dropdown that narrows the
	   visible list to a single country, independent of the auto-select target
	   spinner above. "" means "all countries". */
	/* Country chips: a single row the user swipes sideways; "" = all countries
	   (= every proxy in the pool). */
	private ChipGroup countryChips;
	private final List<String> filterCountryCodes = new ArrayList<String>();
	private String filterCountry = "";
	/* Protocol (proxy type, e.g. ss / vmess / trojan) filter for the node list.
	   "" means "all protocols". Persisted so it survives a reopen. */
	private Spinner spinner_proto_filter;
	private ArrayAdapter<String> protoFilterAdapter;
	private final List<String> filterProtoTypes = new ArrayList<String>();
	private final List<String> filterProtoLabels = new ArrayList<String>();
	private String filterProto = "";
	/* Bounded pool for latency tests. "Test all" on a large subscription would
	   otherwise fire one thread - and one socket - per node at once. */
	private final ExecutorService testPool = Executors.newFixedThreadPool(16);

	/* nodes = everything we parsed (source of truth, persisted)
	   shown = what the list displays after filtering + sorting */
	private List<ClashNode> nodes = new ArrayList<ClashNode>();
	private List<ClashNode> shown = new ArrayList<ClashNode>();
	private NodeAdapter adapter;
	private final Handler ui = new Handler(Looper.getMainLooper());

	/* Latency order by default: fastest first is what people actually want. */
	private int pendingTests = 0;
	/* Progress of a "test all" run, and the last time the list was fully
	   re-filtered. Sorting hundreds of rows on every single result would cost
	   more than the wait it saves, so a full refresh is throttled. */
	private int testsDone = 0;
	private long lastTestUiUpdate = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		/* Restore the last view state so the filters do not reset to "all". */
		filterCountry = prefs.getSubCountryFilter();
		filterProto = prefs.getSubProtoFilter();
		setContentView(R.layout.activity_subscribe);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		fab_test_all = (FloatingActionButton) findViewById(R.id.sub_test_all);
		listview = (ListView) findViewById(R.id.sub_list);
		textview_empty = (TextView) findViewById(R.id.sub_empty);
		textview_stats = (TextView) findViewById(R.id.sub_stats);
		switch_auto = (SwitchMaterial) findViewById(R.id.sub_auto);
		textview_auto_hint = (TextView) findViewById(R.id.sub_auto_hint);
		textview_test_url = (TextView) findViewById(R.id.sub_test_url);

		countryChips = (ChipGroup) findViewById(R.id.sub_country_chips);
		spinner_proto_filter = (Spinner) findViewById(R.id.sub_proto_filter);
		protoFilterAdapter = new ArrayAdapter<String>(this,
			R.layout.spinner_item_small, filterProtoLabels);
		protoFilterAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_small);
		spinner_proto_filter.setAdapter(protoFilterAdapter);
		spinner_proto_filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (position < 0 || position >= filterProtoTypes.size())
				  return;
				filterProto = filterProtoTypes.get(position);
				prefs.setSubProtoFilter(filterProto);
				applyView();
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});


		adapter = new NodeAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		/* Read the persisted choices BEFORE the adapters are attached: attaching
		   an adapter immediately fires onItemSelected(0), which would persist 0
		   over the saved value. Setting the selection afterwards then re-fires
		   the listeners with the right position - and that is what applies it. */


		fab_test_all.setOnClickListener(new View.OnClickListener() {
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
				if (id == R.id.action_help)
				  showHelp();
				return true;
			}
		});

		setupAutoSelect();
		loadNodes();
	}

	@Override
	protected void onDestroy() {
		/* Drop queued tests: they hold this activity and would otherwise keep
		   running (and posting) after it is gone. */
		testPool.shutdownNow();
		super.onDestroy();
	}



	/* One merged list, built from every subscription's own cache. */
	private void loadNodes() {
		nodes.clear();
		for (Subscription sub : prefs.getSubscriptions()) {
			/* Disabled subscriptions are not merged into the tunnel, so keep
			   them out of the pooled node list as well. */
			if (!sub.enabled)
			  continue;
			for (ClashNode n : ClashNode.decode(prefs.getSubNodes(sub.id))) {
				/* Caches written before the merge carry no owner yet. */
				if (n.subId == null || n.subId.isEmpty())
				  n.subId = sub.id;
				nodes.add(n);
			}
		}
		applyView();
		refreshCountryChips();
		refreshProtoFilterSpinner();
	}

	/* Rebuild the visible list from nodes according to filter + sort. */
	private void applyView() {
		shown.clear();
		/* Default view: only reachable (available) proxies, sorted fastest-first
		   by latency. The country chips and protocol spinner still narrow the
		   list down further. */
		for (ClashNode n : nodes) {
			if (!isAvailable(n))
			  continue;
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
			  continue;
			if (!filterProto.isEmpty() && !filterProto.equals(nodeType(n)))
			  continue;
			shown.add(n);
		}
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

	private static boolean matchesCountry(ClashNode n, String cc) {
		String nodeCc = (n.country == null || n.country.isEmpty()) ? GeoIp.UNKNOWN : n.country;
		return nodeCc.equals(cc);
	}

	/* The node's proxy type (ss / vmess / trojan / ...), "" when unknown. */
	private static String nodeType(ClashNode n) {
		return n.type == null ? "" : n.type;
	}

	/* The node's country code, or GeoIp.UNKNOWN when it has not been resolved. */
	private static String countryCode(ClashNode n) {
		return (n.country == null || n.country.isEmpty()) ? GeoIp.UNKNOWN : n.country;
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
		StringBuilder sb = new StringBuilder(
			getString(R.string.sub_stats, ok, bad, nodes.size()));
		if (shown.size() != nodes.size())
		  sb.append("  ·  ").append(getString(R.string.sub_visible, shown.size()));
		if (pendingTests > 0)
		  sb.append("  ·  ").append(getString(R.string.sub_testing, testsDone,
			testsDone + pendingTests));
		textview_stats.setText(sb.toString());
	}



	/* Rebuild the country chip row: "全部" first (that is the whole proxy pool),
	   then one chip per country with its node count, busiest first. The row
	   scrolls sideways, so a long list of countries never squeezes the layout. */
	private void refreshCountryChips() {
		filterCountryCodes.clear();
		filterCountryCodes.add("");
		countryChips.removeAllViews();
		countryChips.addView(makeCountryChip(getString(R.string.sub_country_all), ""));

		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			String cc = countryCode(n);
			Integer c = counts.get(cc);
			counts.put(cc, c == null ? 1 : c + 1);
		}
		List<Map.Entry<String, Integer>> entries =
			new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue().compareTo(a.getValue());
			}
		});
		for (Map.Entry<String, Integer> e : entries) {
			filterCountryCodes.add(e.getKey());
			countryChips.addView(makeCountryChip(
				Country.displayWithCount(e.getKey(), e.getValue()), e.getKey()));
		}

		int idx = filterCountryCodes.indexOf(filterCountry);
		if (idx < 0) {
			/* The remembered country is gone (subscription changed): fall back to
			   "all" instead of leaving the list empty. */
			filterCountry = "";
			idx = 0;
		}
		Chip chip = (Chip) countryChips.getChildAt(idx);
		if (chip != null)
		  chip.setChecked(true);
	}

	private Chip makeCountryChip(String label, final String code) {
		Chip chip = new Chip(this);
		chip.setText(label);
		chip.setCheckable(true);
		chip.setClickable(true);
		chip.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				filterCountry = code;
				prefs.setSubCountryFilter(filterCountry);
				applyView();
				/* The tunnel's node pool is baked in at startup
				   (MihomoConfig.mergedConfig), so a running tunnel keeps the
				   old country until it is reconnected. */
				if (prefs.getEnable())
				  Toast.makeText(SubscribeActivity.this,
					  R.string.sub_country_filter_reconnect, Toast.LENGTH_LONG).show();
			}
		});
		return chip;
	}

	/* Rebuild the protocol filter dropdown from the node types we parsed. The
	   first entry is "all protocols"; the rest are proxy types with counts,
	   busiest first. */
	private void refreshProtoFilterSpinner() {
		filterProtoTypes.clear();
		filterProtoLabels.clear();
		filterProtoTypes.add("");
		filterProtoLabels.add(getString(R.string.sub_proto_all));
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			String t = nodeType(n);
			if (t.isEmpty())
			  continue;
			Integer c = counts.get(t);
			counts.put(t, c == null ? 1 : c + 1);
		}
		List<Map.Entry<String, Integer>> entries =
			new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue().compareTo(a.getValue());
			}
		});
		for (Map.Entry<String, Integer> e : entries) {
			filterProtoTypes.add(e.getKey());
			filterProtoLabels.add(e.getKey() + " (" + e.getValue() + ")");
		}
		protoFilterAdapter.notifyDataSetChanged();
		int idx = filterProtoTypes.indexOf(filterProto);
		if (idx < 0)
		  idx = 0;
		spinner_proto_filter.setSelection(idx);
	}



	/* Latency results belong to the subscription a node came from, so write
	   each group back to its own cache. */
	private void saveNodes() {
		for (Subscription sub : prefs.getSubscriptions()) {
			List<ClashNode> mine = new ArrayList<ClashNode>();
			for (ClashNode n : nodes) {
				if (sub.id.equals(n.subId))
				  mine.add(n);
			}
			prefs.setSubNodes(sub.id, ClashNode.encode(mine));
		}
	}

	/* Fetch every subscription, so the list really is the merged pool the
	   tunnel will run on. One failing provider does not stop the others. */


	private void testAll() {
		if (nodes.isEmpty())
		  return;
		pendingTests = nodes.size();
		testsDone = 0;
		lastTestUiUpdate = 0;
		updateTestProgress();
		for (ClashNode n : nodes)
		  testNode(n, true);
	}

	/* The FAB carries no label, so progress goes into the stats line and the
	   FAB is simply disabled while a run is in flight. */
	private void updateTestProgress() {
		fab_test_all.setEnabled(pendingTests <= 0);
		updateStats();
	}

	/* batch = part of "test all": only refresh + persist once the last one
	   finishes, otherwise we would rewrite the whole cache per node. */
	private void testNode(final ClashNode n, final boolean batch) {
		testPool.execute(new Runnable() {
			@Override
			public void run() {
				long t = System.currentTimeMillis();
				/* try-with-resources: a failed connect() used to leave the
				   socket - and its fd - behind. */
				try (Socket s = new Socket()) {
					s.connect(new InetSocketAddress(n.server, n.port), 3000);
					n.latency = System.currentTimeMillis() - t;
				} catch (Exception e) {
					n.latency = -2;
				}
				/* Resolve the node's country as part of the same test pass
				   (offline cache first, so repeats cost nothing). This makes a
				   single "测速" both measure speed and tag the country. */
				if (n.country == null || n.country.isEmpty() || n.country.equals(GeoIp.UNKNOWN))
				  n.country = GeoIp.countryOf(prefs, n.server);
				ui.post(new Runnable() {
					@Override
					public void run() {
						if (isFinishing() || isDestroyed())
						  return;
						if (batch) {
							testsDone++;
							pendingTests--;
							updateTestProgress();
							/* Show each result as it lands; a full re-filter (with
							   sorting) is throttled. */
							long now = System.currentTimeMillis();
							if (now - lastTestUiUpdate >= 400) {
								lastTestUiUpdate = now;
								applyView();
							} else {
								adapter.notifyDataSetChanged();
								updateStats();
							}
							if (pendingTests > 0)
							  return;
						}
						saveNodes();
						refreshCountryChips();
						refreshProtoFilterSpinner();
						applyView();
						updateTestProgress();
					}
				});
			}
		});
	}

	private void useNode(final ClashNode n) {
		/* Tapping a node means the user wants exactly that one, so auto-select
		   has to go off - otherwise the url-test group would switch away from
		   it on the next health check. */
		boolean wasAuto = prefs.getAutoSelect();
		if (wasAuto) {
			prefs.setAutoSelect(false);
			updateAutoUi();
		}
		/* The app owns the routing, so "use" just remembers the pick;
		   TProxyService selects it in our own group when the tunnel starts. */
		prefs.setSubSelected(n.name);
		/* Picking a subscription node hands the upstream back to the
		   subscription, so any enabled SOCKS5 server is disabled. */
		prefs.setActiveSocksId("");
		String msg;
		if (prefs.getEnable()) {
			startService(new Intent(this, TProxyService.class)
				.setAction(TProxyService.ACTION_SELECT));
			/* The running tunnel still has the url-test group and that group
			   re-tests on a timer, so a hand-picked node is only guaranteed to
			   stick after a restart switches the group to select. */
			msg = wasAuto
				? getString(R.string.sub_switched_restart, n.name)
				: getString(R.string.sub_switched, n.name);
		} else
			msg = getString(R.string.sub_applied_hint, n.name);
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
		adapter.notifyDataSetChanged();
	}

	/* Auto-select: mihomo's url-test group picks the fastest node and
	   re-tests it every `interval` seconds. Off means "use exactly the node
	   tapped in the list". */
	private void setupAutoSelect() {
		switch_auto.setChecked(prefs.getAutoSelect());
		switch_auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton button, boolean checked) {
				prefs.setAutoSelect(checked);
				updateAutoUi();

				/* The group type is baked in when the tunnel starts, so a
				   running tunnel keeps its old behaviour until reconnected. */
				if (prefs.getEnable())
				  Toast.makeText(SubscribeActivity.this,
					R.string.sub_auto_restart, Toast.LENGTH_LONG).show();
			}
		});
		textview_auto_hint.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				editInterval();
			}
		});
		textview_test_url.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				editTestUrl();
			}
		});
		updateAutoUi();
	}

	private void updateAutoUi() {
		boolean auto = prefs.getAutoSelect();
		/* setChecked() only fires the listener when the value actually
		   changes, so this cannot recurse. */
		switch_auto.setChecked(auto);
		textview_auto_hint.setEnabled(auto);
		textview_auto_hint.setText(auto
			? getString(R.string.sub_auto_hint, intervalLabel())
			: getString(R.string.sub_manual_hint));
		/* The check target only matters while url-test is in charge. */
		textview_test_url.setVisibility(auto ? View.VISIBLE : View.GONE);
		textview_test_url.setText(getString(R.string.sub_test_url,
			prefs.getAutoTestUrl()));

	}

	private int intervalMinutes() {
		return Math.max(MIN_INTERVAL_MIN,
			Math.round(prefs.getAutoSelectInterval() / 60f));
	}

	private String intervalLabel() {
		return getString(R.string.sub_minutes, intervalMinutes());
	}

	/* Presets cover what people actually use; "custom" falls through to a
	   free-form field for everything else. */
	private void editInterval() {
		if (!prefs.getAutoSelect())
		  return;

		final int[] presets = { 1, 5, 10, 30, 60 };
		final int current = intervalMinutes();
		int checked = presets.length;
		final String[] labels = new String[presets.length + 1];
		for (int i = 0; i < presets.length; i++) {
			labels[i] = getString(R.string.sub_minutes, presets[i]);
			if (presets[i] == current)
			  checked = i;
		}
		labels[presets.length] = getString(R.string.sub_interval_custom, current);

		new AlertDialog.Builder(this)
			.setTitle(R.string.sub_interval_title)
			.setSingleChoiceItems(labels, checked, null)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
					if (sel < 0)
					  return;
					if (sel >= presets.length) {
						askCustomInterval();
						return;
					}
					prefs.setAutoSelectInterval(presets[sel] * 60);
					afterAutoSelectChange();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* Free-form fallback. The value is clamped instead of rejected, so a
	   stray keypress cannot silently throw the setting away. */
	private void askCustomInterval() {
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setHint(R.string.sub_interval_hint);
		input.setText(Integer.toString(intervalMinutes()));
		input.setSelection(input.getText().length());
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		input.setPadding(pad, pad / 2, pad, 0);

		new AlertDialog.Builder(this)
			.setTitle(R.string.sub_interval_custom_title)
			.setMessage(R.string.sub_interval_hint)
			.setView(input)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					int minutes;
					try {
						minutes = Integer.parseInt(input.getText().toString().trim());
					} catch (NumberFormatException e) {
						minutes = intervalMinutes();
					}
					int clamped = Math.max(MIN_INTERVAL_MIN,
						Math.min(MAX_INTERVAL_MIN, minutes));
					if (clamped != minutes)
					  Toast.makeText(SubscribeActivity.this,
						getString(R.string.sub_interval_clamped, clamped),
						Toast.LENGTH_SHORT).show();
					prefs.setAutoSelectInterval(clamped * 60);
					afterAutoSelectChange();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* Shared tail of every auto-select edit: the group is written when the
	   tunnel starts, so a running tunnel keeps the old setting. */
	private void afterAutoSelectChange() {
		updateAutoUi();
		if (prefs.getEnable())
		  Toast.makeText(SubscribeActivity.this,
			R.string.sub_auto_restart, Toast.LENGTH_LONG).show();
	}

	/* The URL url-test measures against. Worth exposing: if a provider treats
	   this particular host badly, every node would score poorly even though
	   normal traffic is fine. */
	private void editTestUrl() {
		if (!prefs.getAutoSelect())
		  return;
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
		input.setSingleLine(true);
		input.setHint(R.string.sub_test_url_title);
		input.setText(prefs.getAutoTestUrl());
		input.setSelection(input.getText().length());
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		input.setPadding(pad, pad / 2, pad, 0);

		new AlertDialog.Builder(this)
			.setTitle(R.string.sub_test_url_title)
			.setMessage(R.string.sub_test_url_hint)
			.setView(input)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					String url = input.getText().toString().trim();
					if (!url.isEmpty() && !isHttpUrl(url)) {
						Toast.makeText(SubscribeActivity.this,
							R.string.sub_test_url_invalid, Toast.LENGTH_LONG).show();
						return;
					}
					prefs.setAutoTestUrl(url);
					afterAutoSelectChange();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private static boolean isHttpUrl(String url) {
		String u = url.toLowerCase();
		return u.startsWith("http://") || u.startsWith("https://");
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
			TextView flag = (TextView) convertView.findViewById(R.id.item_flag);
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			TextView proto = (TextView) convertView.findViewById(R.id.item_proto);
			TextView status = (TextView) convertView.findViewById(R.id.item_status);
			TextView badge = (TextView) convertView.findViewById(R.id.item_badge);
			Button use = (Button) convertView.findViewById(R.id.item_use);
			Button test = (Button) convertView.findViewById(R.id.item_test);

			String cc = countryCode(n);
			flag.setText(Country.flag(cc));
			name.setText(n.name);
			/* "国家 · host:port": the country leads when it is known, and the
			   address is what ellipsizes (in the middle, so the port survives). */
			String detailText = n.server + ":" + n.port;
			if (!GeoIp.UNKNOWN.equals(cc))
			  detailText = Country.name(cc) + "  ·  " + detailText;
			detail.setText(detailText);
			/* The protocol gets its own tag: sharing a line with the address
			   meant a long host name would ellipsize it away. */
			if (n.type == null || n.type.isEmpty()) {
				proto.setVisibility(View.GONE);
			} else {
				proto.setText(n.type);
				proto.setVisibility(View.VISIBLE);
			}

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
