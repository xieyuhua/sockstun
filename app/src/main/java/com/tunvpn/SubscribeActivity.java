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
import android.widget.ProgressBar;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class SubscribeActivity extends BaseActivity {
	/* Spinner position 0 is the default, and "fastest first" is what people
	   want, so latency is index 0 and subscription order index 1. */

	private Preferences prefs;
	private FloatingActionButton fab_test_all;
	private ListView listview;
	private TextView textview_empty;
	private TextView textview_stats;
	/* Progress of a "test all" run: a determinate bar in the card, so the user
	   can see it moving instead of guessing whether the button did anything. */
	private ProgressBar progress_bar;
	private SwitchMaterial switch_auto;
	private TextView textview_auto_hint;
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
	/* subId -> subscription name, so each row can show where it came from. */
	private final java.util.Map<String, String> subNames =
		new java.util.HashMap<String, String>();
	/* Bounded pool for latency tests. "Test all" on a large subscription would
	   otherwise fire one thread - and one socket - per node at once. */
	private final ExecutorService testPool = Executors.newFixedThreadPool(16);

	/* Resolved mihomo proxy names for the current test pass: "server|port|type"
	   -> the real (de-dup'd) proxy name in the running config. Filled lazily
	   from the clash-api so per-node delay tests target the correct node even
	   when mergedConfig renamed duplicates to "name (N)". */
	private volatile java.util.Map<String, String> proxyNameCache = null;
	/* How many testable proxies the core reported, for the logs: it separates
	   "the generated config carries no nodes" from "this node is not in it". */
	private volatile int coreNodeCount = 0;
	/* Nodes this pass could not find in the core (usually because the test
	   core was built from the country-filtered pool). Drives the hint shown
	   after a pass that measured almost nothing. */
	private final java.util.concurrent.atomic.AtomicInteger passNotFound =
		new java.util.concurrent.atomic.AtomicInteger();

	/* Working clash-api host for this test pass. We probe 127.0.0.1 (the
	   loopback the external-controller is bound to) and keep the device IP as a
	   fallback in case the loopback is ever captured by the TUN. */
	private String apiHost = null;

	/* nodes = everything we parsed (source of truth, persisted)
	   shown = what the list displays after filtering + sorting */
	private List<ClashNode> nodes = new ArrayList<ClashNode>();
	private List<ClashNode> shown = new ArrayList<ClashNode>();
	private NodeAdapter adapter;
	private final Handler ui = new Handler(Looper.getMainLooper());

	/* Keys already logged during THIS test pass. A whole-pass condition (no
	   core / no proxy map / dead control API) would otherwise print once per
	   node and bury the interesting lines. Cleared by testAll(). */
	private final java.util.Set<String> passLogged =
		java.util.Collections.newSetFromMap(
			new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/* Log a pass-wide condition at most once per pass. The message is what the
	   user is asked to send back when a node flips to 不可用, so it carries the
	   reason and the node that hit it. */
	private void logOnce(String key, String msg) {
		if (passLogged.add(key))
		  TProxyService.log(msg);
	}

	/* mihomo's built-in adapters plus every proxy-GROUP type: not dialable
	   nodes, so they must never be offered as a delay-test target. Needed
	   because current mihomo no longer reports server/port in /proxies, so the
	   "has an address" test cannot be used to classify entries. */
	private static boolean isBuiltinOrGroup(String name, String type) {
		String t = type == null ? "" : type.toLowerCase();
		if (t.equals("selector") || t.equals("urltest") || t.equals("fallback")
				|| t.equals("loadbalance") || t.equals("relay")
				|| t.equals("direct") || t.equals("reject") || t.equals("rejectdrop")
				|| t.equals("compatible") || t.equals("pass") || t.equals("passrule"))
		  return true;
		String n = name == null ? "" : name.toUpperCase();
		return n.equals("DIRECT") || n.equals("REJECT") || n.equals("REJECT-DROP")
			|| n.equals("GLOBAL") || n.equals("COMPATIBLE") || n.equals("PASS");
	}

	/* Short, single-line form of an arbitrary JSON fragment for the log. */
	private static String shortText(Object o) {
		if (o == null)
		  return "(无)";
		String s = String.valueOf(o).replace('\n', ' ');
		return s.length() > 400 ? s.substring(0, 400) + "…" : s;
	}

	/* Guards the one-off /proxies fetch (see ensureProxyNameCache). */
	private final Object nameCacheLock = new Object();

	/* Latency order by default: fastest first is what people actually want. */
	private int pendingTests = 0;
	/* Size of the current pass (progress bar maximum). */
	private int testTotal = 0;
	/* Set once per "test" pass: the TUN-less test core has been prepared. */
	private volatile boolean corePrepared = false;
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
		progress_bar = (ProgressBar) findViewById(R.id.sub_progress);
		switch_auto = (SwitchMaterial) findViewById(R.id.sub_auto);
		textview_auto_hint = (TextView) findViewById(R.id.sub_auto_hint);

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

		/* Long-press a node to copy it into the manual server list, so a
		   specific node can be pinned/enabled independently of the subscription. */
		listview.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view,
					int position, long id) {
				ClashNode n = adapter.getItem(position);
				if (n != null)
				  addNodeToServers(n);
				return true;
			}
		});

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

	@Override
	protected void onResume() {
		super.onResume();
		/* Settings may have changed "显示不可用节点" (or the test-core switches);
		   re-filter so the list reflects the new choice as soon as we return. */
		applyView();
		/* Preload the TUN-less test core so the first 测速 is instant. This is
		   all the 「未连接时内核测速」 switch does: without it the core is still
		   loaded on demand when 测速 is tapped, just a few seconds later.
		   Off the UI thread - loading a core takes a moment. */
		if (!prefs.getEnable() && prefs.getPreloadCore()) {
			final android.content.Context app = getApplicationContext();
			new Thread(new Runnable() {
				@Override
				public void run() {
					CoreTestHost.ensureReady(app, prefs);
				}
			}, "test-core-preload").start();
		}
	}

	/* Rebuild the visible list from nodes according to filter + sort. */
	private void applyView() {
		refreshSubNames();
		shown.clear();
		/* Default view: only reachable (available) proxies, sorted
		   fastest-first by latency. "显示不可用节点" (Settings → 订阅) also
		   lists the broken (-2) and never-tested (-1) ones, which rank to the
		   bottom. The country chips and protocol spinner still narrow further. */
		boolean showAll = prefs.getShowUnavailable();
		for (ClashNode n : nodes) {
			if (!showAll && !isAvailable(n))
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
		if (pendingTests > 0) {
			int total = Math.max(1, testsDone + pendingTests);
			int pct = (int) (testsDone * 100L / total);
			sb.append("  ·  ").append(getString(R.string.sub_testing, testsDone, total, pct));
		}
		textview_stats.setText(sb.toString());
	}

	private void refreshSubNames() {
		subNames.clear();
		for (Subscription s : prefs.getSubscriptions())
			subNames.put(s.id, s.name);
	}

	/* A stable colour per proxy type so the list reads at a glance. */
	private static int protoColor(String type) {
		if (type == null)
		  return 0xFF757575;
		switch (type.toLowerCase()) {
			case "socks5":     return 0xFF607D8B;
			case "ss": case "shadowsocks": return 0xFF009688;
			case "vmess":      return 0xFFFF9800;
			case "vless":      return 0xFF4CAF50;
			case "trojan":     return 0xFFF44336;
			case "hysteria": case "hysteria2": return 0xFF9C27B0;
			case "tuic":       return 0xFF00BCD4;
			case "wireguard":  return 0xFF3F51B5;
			default:           return 0xFF757575;
		}
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
		Map<String, Integer> avail = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			String cc = countryCode(n);
			Integer c = counts.get(cc);
			counts.put(cc, c == null ? 1 : c + 1);
			/* Only a node that passed a latency test counts as usable. */
			if (n.latency >= 0) {
				Integer a = avail.get(cc);
				avail.put(cc, a == null ? 1 : a + 1);
			}
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
			int total = e.getValue();
			int ok = avail.containsKey(e.getKey()) ? avail.get(e.getKey()) : 0;
			countryChips.addView(makeCountryChip(
				Country.displayWithAvail(e.getKey(), ok, total), e.getKey()));
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
				/* The country IS the test scope now: the test core is rebuilt
				   from the new pool ("测速范围 = 你看到的列表"), so the cached
				   node-name map must be refetched (it is read off the UI thread,
				   hence the volatile fields). */
				proxyNameCache = null;
				coreNodeCount = 0;
				applyView();
				refreshProtoFilterSpinner();
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
	   first entry is "all protocols"; the rest are proxy types with their
	   AVAILABLE count within the currently selected country, busiest first.
	   The count tracks what the node list shows: pick a country chip and the
	   numbers shrink to that country; a latency test repopulates only the
	   reachable nodes. */
	private void refreshProtoFilterSpinner() {
		filterProtoTypes.clear();
		filterProtoLabels.clear();
		filterProtoTypes.add("");
		filterProtoLabels.add(getString(R.string.sub_proto_all));
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			/* Scope to the picked country, exactly like applyView() does. */
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
				continue;
			String t = nodeType(n);
			if (t.isEmpty())
			  continue;
			/* Count what the list shows: with "显示不可用节点" on, unusable
			   nodes are listed too, so they belong in the protocol counts. */
			if (n.latency < 0 && !prefs.getShowUnavailable())
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
		/* Drop the previous pass's progress state FIRST: an early return below
		   must never leave a stale 0% bar on screen with the FAB disabled. */
		pendingTests = 0;
		testsDone = 0;
		testTotal = 0;
		if (nodes.isEmpty()) {
			updateTestProgress();
			return;
		}
		/* Test exactly the pool the list is scoped to: 「全部」 tests every node,
		   a country chip tests that country's nodes. The test core is built from
		   the same pool, so a node in the list is never "missing from the core" -
		   that is why the old 「测速并入全量节点」 switch is gone. */
		List<ClashNode> toTest = new ArrayList<ClashNode>();
		for (ClashNode n : nodes) {
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
			  continue;
			toTest.add(n);
		}
		if (toTest.isEmpty()) {
			/* Staying silent here made the button look dead. */
			updateTestProgress();
			TProxyService.log("测速: 当前筛选（"
				+ (filterCountry.isEmpty() ? "全部" : filterCountry) + "）没有匹配到节点");
			Toast.makeText(this, R.string.sub_test_empty_scope, Toast.LENGTH_SHORT).show();
			return;
		}
		/* The running config may have changed (subscription edited / re-picked);
		   rebuild the node-name map and re-probe the api host from scratch. */
		proxyNameCache = null;
		apiHost = null;
		coreNodeCount = 0;
		passNotFound.set(0);
		corePrepared = false;
		pendingTests = toTest.size();
		testTotal = toTest.size();
		testsDone = 0;
		lastTestUiUpdate = 0;
		passLogged.clear();
		updateTestProgress();
		/* Pass header: enough context to read a run in which a node flipped
		   from 可用 to 不可用 (which core answered, how long we waited, at
		   which URL). */
		TProxyService.log("=== 测速开始：" + toTest.size() + " 个节点 · VPN="
			+ (prefs.getEnable() ? "已连接" : "未连接")
			+ " · 单目标超时 " + prefs.getProxyTestTimeout() + "s · 测速地址 "
			+ prefs.getAutoTestUrl() + " · 内置目标 " + PROXY_TEST_URLS.length + " 个"
			+ " · 国家筛选=" + (prefs.getSubCountryFilter().isEmpty()
				? "全部" : prefs.getSubCountryFilter()) + " ===");
		for (ClashNode n : toTest)
		  testNode(n, true);
		/* Watchdog: a pass that lands NOTHING within a generous window is stuck
		   (core / bridge / hostname resolution), not merely slow. Recorded with
		   the counters so a frozen progress bar is explainable after the fact. */
		final int expected = toTest.size();
		ui.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (pendingTests <= 0 || testsDone > 0)
				  return;                       /* finished, or visibly moving */
				TProxyService.log("测速: 60 秒内没有任何结果（应测 " + expected + "，待完成 "
					+ pendingTests + "）→ 疑似卡在内核/桥/域名解析，详见上面日志");
			}
		}, 60000);
	}

	/* Progress: the bar in the card plus the stats line, and the FAB is simply
	   disabled while a run is in flight (it carries no label of its own). */
	private void updateTestProgress() {
		boolean running = pendingTests > 0;
		fab_test_all.setEnabled(!running);
		if (progress_bar != null) {
			if (running) {
				progress_bar.setMax(Math.max(1, testTotal));
				progress_bar.setProgress(Math.max(0, testTotal - pendingTests));
				progress_bar.setVisibility(View.VISIBLE);
			} else {
				progress_bar.setVisibility(View.GONE);
			}
		}
		updateStats();
	}

	/* Load/refresh the TUN-less test core, so the latency test can use the
	   core's REAL forwarding delay. Returns true when a test core is available
	   in this process. Runs on a pool thread (loading the core takes a moment).
	   Always used - connected or not - because it is the only path whose node
	   pool is rebuilt from the CURRENT country filter: the tunnel core's pool is
	   baked in at connect time, so after switching country chip it would no
	   longer match the list. */
	private boolean prepareTestCore() {
		try {
			boolean ok = CoreTestHost.ensureReady(this, prefs);
			if (ok) {
				logOnce("core-path", "测速: 使用测试内核（跟随当前国家筛选）");
				return true;
			}
			/* Test core unavailable: fall back to the tunnel core over the
			   control API, which at least covers the pool it was started with. */
			if (prefs.getEnable()) {
				logOnce("core-path", "测速: 测试内核不可用 → 回退隧道内核的 /delay（其节点池"
					+ "为连接时的那一份，换过国家筛选可能不全）");
				return false;
			}
			logOnce("core-path", "测速: 测试内核不可用（未连接 VPN，无回退）→ 本次按未测速处理");
			return false;
		} catch (Throwable e) {
			TProxyService.log("测速: 准备测试内核异常 " + e);
			return false;
		}
	}

	/* One serial probe before the worker pool starts, using the first node that
	   actually resolves to a core proxy. It fixes, on a single thread: the
	   bridge payload container, the testDelay parameter shape and the node-name
	   map - three shared one-shot latches whose races are invisible in a single
	   test but break a whole batch. Its result is also the clearest line in the
	   log when someone reports "测速不准/全废". */
	private void delaySelfTest(boolean coreOk) {
		try {
			if (nodes.isEmpty())
			  return;
			ensureProxyNameCache();
			ClashNode pick = null;
			for (ClashNode x : nodes) {
				if (realNodeName(x) != null) {
					pick = x;
					break;
				}
			}
			if (pick == null)
			  pick = nodes.get(0);
			String name = realNodeName(pick);
			if (name == null)
			  name = pick.name;
			if (name == null || name.isEmpty())
			  return;
			/* Keep the self-test short: it only has to prove the plumbing, not
			   measure the slowest node in the list. It runs BEFORE any node is
			   tested, so every extra second here is a second of frozen 0%. */
			int probeMs = Math.min(prefs.getProxyTestTimeout() * 1000, 5000);
			long t0 = System.currentTimeMillis();
			Long d = CoreTestHost.testDelay(name, prefs.getAutoTestUrl(), probeMs);
			TProxyService.log("测速自检：" + (d == null
				? "无判定（看上面 bridge/delay 行，多为参数或内核问题）"
				: (d >= 0 ? ("可用 " + d + "ms") : "该节点不可用(-2)"))
				+ " · 节点 " + name + " · 内核=" + (coreOk ? "测试内核" : "隧道内核(REST)")
				+ " · 可测节点 " + coreNodeCount + " 个 · 耗时 "
				+ (System.currentTimeMillis() - t0) + "ms");
		} catch (Throwable e) {
			TProxyService.log("测速自检异常 " + e);
		}
	}

	/* batch = part of "test all": only refresh + persist once the last one
	   finishes, otherwise we would rewrite the whole cache per node. */
	private void testNode(final ClashNode n, final boolean batch) {
		testPool.execute(new Runnable() {
			@Override
			public void run() {
				/* Make sure a core is available for the real-forwarding test:
			   the TUN-less test core when disconnected (or when the user asked
			   for all-nodes testing), which also refreshes its node set. Only
			   the first task of a pass does the work. */
				if (!corePrepared) {
					synchronized (SubscribeActivity.this) {
						if (!corePrepared) {
							boolean coreOk = prepareTestCore();
							/* Settle the bridge's payload container, the delay
							   action's parameter shape and the node-name map on
							   ONE thread BEFORE the 16-worker pool fans out.
							   All three are shared one-shot latches, and racing
							   them is what made "测速全部" fail while a single
							   test was fine. */
							delaySelfTest(coreOk);
							corePrepared = true;
						}
					}
				}
				/* The verdict comes ONLY from the core pushing a request through
				   THIS node (the isolated "testDelay" probe). That proves the
				   proxy actually tunnels traffic - a bare TCP port-open is NOT
				   enough, so the socket probe was removed. When the real test
				   cannot run (no core, or the node is absent from the loaded
				   config) we leave the node unverified (-1), not "unavailable". */
				Long real = proxyDelayMs(n);
				if (real != null)
				  n.latency = real;        // >=0 usable, -2 could not tunnel
				else
				  n.latency = -1;          // not verifiable -> unknown, not "available"
				/* Per-node verdict. "不可用" is logged for every such node
				   (that is the symptom being chased); "未测速" only once per
				   pass, because it is normally a pass-wide condition (no core)
				   and would otherwise print once per node. */
				if (real != null && real < 0)
				  TProxyService.log("测速结果: " + n.name + " [" + n.server + ":" + n.port
					+ " " + (n.type == null ? "?" : n.type) + "] -> 不可用(-2)");
				else if (real == null)
				  logOnce("untested", "测速结果: 无判定 → 未测速，例如 " + n.name + " ["
					+ n.server + ":" + n.port + "]");
				/* Report the result FIRST, then look up the country. The lookup
				   needs DNS, and a slow resolver used to hold the whole
				   "0% -> nothing" state: the progress counter is only bumped by
				   the post below, so it stayed frozen while a hostname crawled.
				   The country is cosmetic (flag + chips), the latency is not. */
				ui.post(new Runnable() {
					@Override
					public void run() {
						if (isFinishing() || isDestroyed())
						  return;
						/* Progress counts for BOTH a batch and a single re-test, so
						   the bar always reflects the real pass and always hides
						   again when the pass ends. */
						testsDone++;
						if (pendingTests > 0)
						  pendingTests--;
						updateTestProgress();
						if (batch) {
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
						if (batch) {
							int ok = 0;
							int bad = 0;
							int un = 0;
							for (ClashNode x : nodes) {
								if (x.latency >= 0)
								  ok++;
								else if (x.latency == -2)
								  bad++;
								else
								  un++;
							}
							TProxyService.log("=== 测速结束：可用 " + ok + " / 不可用 "
								+ bad + " / 未测速 " + un + " · 详见上面每行「测速:」 ===");
							/* Nothing was measured at all: say so out loud
							   instead of leaving the user with an unchanged
							   list and no reason. */
							int missing = passNotFound.get();
							if (ok == 0 && bad == 0 && un > 0)
							  Toast.makeText(SubscribeActivity.this,
								R.string.sub_test_no_core, Toast.LENGTH_LONG).show();
							else if (missing > 0)
							  Toast.makeText(SubscribeActivity.this,
								getString(R.string.sub_test_missing_nodes, missing),
								Toast.LENGTH_LONG).show();
						}
					}
				});
				/* Country LAST (see above): it needs DNS, and a slow resolver must
				   never freeze the progress. Re-resolved each pass so a mislabeled
				   flag (a stale "RU" on a US IP) self-heals; a failure yields
				   UNKNOWN, which is kept out so a good value is never clobbered. */
				String cc = GeoIp.countryOf(prefs, n.server, true);
				if (cc != null && !cc.isEmpty() && !GeoIp.UNKNOWN.equals(cc)
						&& !cc.equals(n.country)) {
					n.country = cc;
					ui.post(new Runnable() {
						@Override
						public void run() {
							if (isFinishing() || isDestroyed())
							  return;
							/* Repaint the row's flag; persist + refresh the chips
							   only once the pass is over, because the tail may
							   already have saved while this lookup was running. */
							adapter.notifyDataSetChanged();
							if (pendingTests <= 0) {
								refreshCountryChips();
								saveNodes();
							}
						}
					});
				}
			}
		});
	}

	/* Long-press: copy a subscription node into the manual server list. SOCKS5
	   uses the form fields; every other protocol needs the node's original clash
	   proxy block verbatim (the only way to keep uuid / sni / ws-opts / ...),
	   which we recover from the subscription's raw body by node name. */
	private void addNodeToServers(final ClashNode n) {
		List<SocksServer> list = prefs.getSocksServers();
		for (SocksServer s : list) {
			if (n.name.equals(s.name) && n.server.equals(s.addr) && n.port == s.port) {
				Toast.makeText(this, R.string.sub_server_exists, Toast.LENGTH_SHORT).show();
				return;
			}
		}
		boolean socks = "socks5".equals(n.type == null ? "" : n.type);
		String raw = socks ? "" : findRawProxy(n);
		if (!socks && (raw == null || raw.isEmpty())) {
			Toast.makeText(this, R.string.sub_add_to_server_failed, Toast.LENGTH_LONG).show();
			return;
		}
		SocksServer s = new SocksServer(SocksServer.newId(), n.name, n.server, n.port,
			n.username, n.password, socks ? "socks5" : n.type, raw);
		list.add(s);
		prefs.setSocksServers(list);
		Toast.makeText(this, getString(R.string.sub_add_to_server, n.name),
			Toast.LENGTH_LONG).show();
	}

	/* Recover a node's original clash proxy block from its subscription's raw
	   body, matched by name. Returns "" when the subscription body is missing
	   or the node could not be located (e.g. the subscription changed). */
	private String findRawProxy(ClashNode n) {
		if (n.subId == null || n.subId.isEmpty())
		  return "";
		String raw = prefs.getSubRaw(n.subId);
		if (raw == null || raw.isEmpty())
		  return "";
		for (ClashParser.ProxyDef p : ClashParser.extractProxies(raw)) {
			if (n.name != null && n.name.equals(p.name))
			  return p.text;
		}
		return "";
	}

	/* Candidate targets for the real delay test. A node that answers ANY of
	   them is alive; only when every target fails do we call it unusable. A
	   spread of providers (Google / Microsoft / Cloudflare) means one blocked
	   or geo-restricted host cannot false-negative a perfectly good node - the
	   exact "代理本来正常却显示不可用" trap we want to avoid. */
	private static final String[] PROXY_TEST_URLS = {
		"http://www.gstatic.com/generate_204",
		"http://www.google.com/generate_204",
		"http://www.msftconnecttest.com/connecttest.txt",
		"http://cp.cloudflare.com",
	};

	/* Ask mihomo to push a request through THIS node and report the real delay.
	   Like FlClash, the availability verdict comes solely from mihomo's
	   /proxies/{name}/delay endpoint - no separate TCP probe and no "select
	   the node and push a real request" fallback that used to flip the global
	   selector and false-negative working nodes. Returns latency (>=0) when
	   the node tunnels, -2 when it cannot, or null when the test cannot run
	   (tunnel down / node not in the running config). Works for every protocol
	   because mihomo performs the handshake. */
	private Long proxyDelayMs(ClashNode n) {
		/* Works while the tunnel is up (tunnel core) OR when a TUN-less test
		   core has been loaded in this process (CoreTestHost). */
		if (!prefs.getEnable() && !CoreTestHost.isReady()) {
			logOnce("no-core", "测速: 没有可用内核（VPN 未连接 / 测试内核未就绪）→ 未测速");
			return null;
		}
		if (!ensureProxyNameCache()) {
			logOnce("no-proxies", "测速: 取 /proxies 失败，无法把节点映射成内核里的名字 → 未测速");
			return null;
		}
		String name = realNodeName(n);
		if (name == null) {
			/* The /proxies map has no entry for this node - but that does NOT
			   prove the core lacks it: mihomo may report a RESOLVED IP in
			   `server` (breaking the address key) while the name we generate is
			   exactly the subscription name. So try that name directly; the core
			   runs a config WE generated. A wrong guess only costs one failed
			   probe (the action answers "proxy not exist") and leaves the node
			   untested, so there is nothing to lose. */
			if (n.name == null || n.name.isEmpty()) {
				passNotFound.incrementAndGet();
				logOnce("noname:" + n.server + ":" + n.port + ":" + n.type,
					"测速: 节点没有名字且在 /proxies 映射里找不到 → 未测速 [addr="
					+ n.server + ":" + n.port + " " + (n.type == null ? "?" : n.type)
					+ "]（内核报告可测节点 " + coreNodeCount + " 个）");
				return null;
			}
			name = n.name;
			logOnce("guess:" + n.name, "测速: /proxies 映射里没有该节点，改用节点名直试 name="
				+ n.name + " addr=" + n.server + ":" + n.port + "（内核报告可测节点 "
				+ coreNodeCount + " 个）");
		}
		/* The in-process probe needs no HTTP host at all - only the REST route
		   does. Requiring one here turned the whole pass into "未测速" whenever
		   the loopback check happened to fail. */
		String host = resolveApiHost();
		if (host == null && !CoreTestHost.isReady()) {
			logOnce("no-api", "测速: 控制接口不可达（127.0.0.1 探测失败）→ 未测速");
			return null;
		}
		/* User's configured target first, then the built-in fallbacks. */
		List<String> targets = new ArrayList<String>();
		String userUrl = prefs.getAutoTestUrl();
		if (userUrl != null && !userUrl.isEmpty())
		  targets.add(userUrl);
		for (String u : PROXY_TEST_URLS)
		  if (!targets.contains(u))
			targets.add(u);
		int timeoutMs = prefs.getProxyTestTimeout() * 1000;
		/* First pass at the configured timeout. A success on ANY target means
		   available. A genuine failure (504/408: the core really could not
		   complete the probe) is remembered; a malfunction (401/404/...) is not
		   a verdict, so it only ever leaves the node untested. */
		boolean failed = false;
		for (String target : targets) {
			Long d = delayProbe(name, target, timeoutMs);
			if (d != null && d >= 0)
			  return d;
			/* Only failures/malfunctions are logged here, so a node that just
			   works produces no noise while a node about to be flagged leaves
			   a full trace (target + what came back). */
			TProxyService.log("测速: " + name + " @" + target + " -> "
				+ (d == null ? "无判定（测试没跑起来）" : "失败(" + d + ")"));
			if (d != null)
			  failed = true;
		}
		if (!failed)
		  return null;              /* only inconclusive -> untested, NOT 不可用 */
		/* Rescue pass: one longer-timeout probe on the most reliable targets,
		   for slow-but-working nodes that a tight timeout just failed. This is
		   what the old "real request" fallback used to rescue, without touching
		   the global selector; it only runs for nodes about to be flagged. */
		int retryMs = Math.max(timeoutMs, 12000);
		for (String target : RETRY_TEST_URLS) {
			Long d = delayProbe(name, target, retryMs);
			if (d != null && d >= 0) {
				TProxyService.log("测速: " + name + " 救援探测成功 " + d + "ms @"
					+ target + "（首轮 " + prefs.getProxyTestTimeout() + "s 超时偏紧）");
				return d;
			}
			if (d == null) {
				TProxyService.log("测速: " + name + " 救援探测无判定 @" + target + " → 未测速");
				return null;           /* malfunction on retry -> untested */
			}
			TProxyService.log("测速: " + name + " 救援探测失败 @" + target + " -> " + d);
		}
		TProxyService.log("测速: " + name + " → 判定不可用（所有目标与救援探测均失败）");
		return -2L;                   /* genuinely unreachable */
	}

	/* Most reliable targets, used only by the rescue pass. */
	private static final String[] RETRY_TEST_URLS = {
		"http://cp.cloudflare.com",
		"http://www.gstatic.com/generate_204",
	};

	/* One /delay probe. Returns the latency (>=0) on success, -2 when the core
	   reports a genuine probe failure (504/408), or null when the test could not
	   run at all (transport down / auth / route / other status) - which must NOT
	   be reported as "unavailable". */
	private Long delayProbe(String name, String target, int timeoutMs) {
		/* Preferred: the core's own isolated probe through the action bridge.
		   Available whenever a core is loaded in THIS process - i.e. the
		   TUN-less test core - so latency can be measured with the VPN off and
		   without any HTTP. Returns null when no core lives here. */
		Long viaCore = CoreTestHost.testDelay(name, target, timeoutMs);
		if (viaCore != null)
		  return viaCore;
		/* This process holds the TUN-less test core, which IS the authoritative
		   probe. The REST route below would go to the :native TUNNEL core,
		   whose node pool can be a different (country-filtered) set - a miss
		   there says nothing about this node, and in a 45-node batch it costs
		   one extra round trip per probe and floods the log with 502s. Report
		   "no verdict" instead and let the caller judge. */
		if (CoreTestHost.isReady()) {
			TProxyService.log("测速: " + name + " 测试内核无判定 @" + target
				+ "（已有本进程内核，不再回退隧道内核 REST）");
			return null;
		}
		/* Fallback: the REST /delay served by the app's embedded control API
		   (used when only the :native tunnel core is available). */
		try {
			String path = "/proxies/" + encodePath(name)
				+ "/delay?timeout=" + timeoutMs + "&url=" + URLEncoder.encode(target, "UTF-8");
			/* Reach the loopback control API through a VPN-bypassing socket; the
			   app's own sockets would otherwise be captured by the tunnel. */
			/* The reply only arrives after the core's own probe finishes, so
			   the client must wait longer than the probe timeout instead of
			   the default 6s (which silently capped a 30s setting). */
			TProxyService.ApiResult r = TProxyService.bridgeApi("GET", TProxyService.apiBaseHost(),
				MihomoConfig.API_PORT, path, null, prefs.getSecret(), timeoutMs + 6000);
			if (r.code == 200) {
				JSONObject o = new JSONObject(r.body == null ? "{}" : r.body);
				if (!o.has("delay")) {
					/* A 200 with no "delay" is a malformed reply: reporting it
					   as a 0 ms success put a dead node at the top of the list. */
					TProxyService.log("测速: " + name + " REST /delay 200 但没有 delay 字段 -> "
						+ r.body + " @" + target + "（非节点判定 → 未测速）");
					return null;
				}
				long d = o.getLong("delay");
				if (d <= 0) {
					TProxyService.log("测速: " + name + " REST /delay 返回 delay=" + d
						+ " @" + target + "（非节点判定 → 未测速）");
					return null;
				}
				return d;
			}
			if (r.code == 504 || r.code == 408) {
				TProxyService.log("测速: " + name + " REST /delay 内核判定失败 HTTP "
					+ r.code + " @" + target + " timeout=" + timeoutMs + "ms");
				return -2L;             /* genuine: node could not complete it */
			}
			if (r.code == -1) {
				TProxyService.log("测速: " + name + " REST /delay 控制接口不可达 @"
					+ target + " → 未测速");
				return null;            /* controller unreachable -> cannot test */
			}
			TProxyService.log("测速: " + name + " REST /delay HTTP " + r.code
				+ (r.error == null ? "" : (" " + r.error)) + " @" + target
				+ "（非节点判定 → 未测速）");
			return null;                /* auth/route/other: not a node verdict */
		} catch (Exception e) {
			return null;
		}
	}

	/* Lazily fetch the running config's proxy list and index it by
	   server|port|type -> real (de-dup'd) name, so we can target a node by its
	   address instead of its possibly-renamed label. Returns false when the
	   controller is unreachable. */
	private boolean ensureProxyNameCache() {
		if (proxyNameCache != null)
		  return true;
		/* One fetch per pass: the 16 test threads all reach here at once and
		   every fetch goes through the single-callback bridge, so 16
		   overlapping copies would only slow the pass down and log 16 times. */
		synchronized (nameCacheLock) {
			if (proxyNameCache != null)
			  return true;
			return fetchProxyNameCache();
		}
	}

	private boolean fetchProxyNameCache() {
		/* Work with the tunnel core OR the TUN-less test core loaded in this
		   process (CoreTestHost). */
		if (!prefs.getEnable() && !CoreTestHost.isReady())
		  return false;
		String host = resolveApiHost();
		if (host == null)
		  return false;
		try {
			TProxyService.ApiResult r = TProxyService.bridgeApi("GET", TProxyService.apiBaseHost(),
				MihomoConfig.API_PORT, "/proxies", null, prefs.getSecret());
			if (r.code != 200 || r.body == null) {
				logOnce("proxies-code", "测速: /proxies HTTP " + r.code
					+ (r.error == null ? "" : (" " + r.error)) + " → 无法建立节点名映射");
				return false;
			}
			String body = r.body;
			JSONObject o = new JSONObject(body);
			JSONObject proxies = o.optJSONObject("proxies");
			if (proxies == null) {
				logOnce("proxies-shape", "测速: /proxies 响应里没有 proxies 对象 → 无法建立映射，前 200 字："
					+ (body.length() > 200 ? body.substring(0, 200) : body));
				return false;
			}
			StringBuilder topKeys = new StringBuilder();
			java.util.Iterator<String> kk = o.keys();
			while (kk.hasNext()) {
				if (topKeys.length() > 0)
				  topKeys.append(',');
				topKeys.append(kk.next());
			}
			java.util.Map<String, String> map = new java.util.HashMap<String, String>();
			java.util.Iterator<String> it = proxies.keys();
			int total = 0;
			int withAddr = 0;
			StringBuilder sample = new StringBuilder();
			/* Raw samples: they answer "does the core's config really carry the
			   nodes?" (and in which shape) without another round trip. */
			String firstObj = null;
			String firstAddressed = null;
			while (it.hasNext()) {
				String pname = it.next();
				Object pv = proxies.opt(pname);
				if (!(pv instanceof JSONObject)) {
					if (firstObj == null)
					  firstObj = pname + " -> " + pv;
					continue;
				}
				JSONObject p = (JSONObject) pv;
				total++;
				if (firstObj == null)
				  firstObj = pname + " -> " + p;
				String type = p.optString("type", "");
				String server = p.optString("server", "");
				int port = p.optInt("port", 0);
				/* Recent mihomo DROPPED server/port from /proxies - an entry is
				   just {"type","name","alive",...} now - so an address can no
				   longer be used to tell a node from a group. Classify by TYPE
				   instead and index every real node BY NAME (which is exactly
				   the name we wrote into the generated config). Built-ins
				   (DIRECT / REJECT / COMPATIBLE) and the config's own groups are
				   the only things skipped. */
				if (isBuiltinOrGroup(pname, type))
				  continue;
				withAddr++;
				if (firstAddressed == null)
				  firstAddressed = pname + " -> " + p;
				if (!map.containsKey("name:" + pname))
				  map.put("name:" + pname, pname);
				if (!server.isEmpty() && port != 0) {
					String base = server.toLowerCase() + "|" + port;
					/* Address key kept for cores that still report one, and as a
					   fallback when a node arrives from somewhere else (manual
					   server list) with no matching name. */
					if (!map.containsKey(base))
					  map.put(base, pname);
					map.put(base + "|" + type.toLowerCase(), pname);
				}
				if (sample.length() < 300) {
					if (sample.length() > 0)
					  sample.append(", ");
					sample.append(pname).append('/').append(type)
						.append(server.isEmpty() ? "" : ("=" + server + ":" + port));
				}
			}
			proxyNameCache = map;
			coreNodeCount = withAddr;
			/* Logged even on success: "the config has no nodes" and "the node
			   is not in the config" look identical in the UI, and this one line
			   tells them apart at a glance. */
			TProxyService.log("测速: 内核 /proxies 共 " + total + " 项，可测节点 "
				+ withAddr + " 个（映射 " + map.size() + " 键）· 国家筛选="
				+ (prefs.getSubCountryFilter().isEmpty() ? "全部" : prefs.getSubCountryFilter())
				+ (sample.length() == 0 ? "" : (" · 示例 " + sample)));
			/* The two shapes worth seeing: a proxy object as the core really
			   reports it, and the top-level keys it really uses. Together they
			   say whether the config carries the nodes, and whether the fields
			   are named the way we look them up. */
			if (withAddr == 0)
			  TProxyService.log("测速: 内核 /proxies 里没有任何可测节点 → 配置里可能真的没有代理。"
				+ "响应顶层键=" + topKeys + " · 首项 " + shortText(firstObj));
			else
			  TProxyService.log("测速: 内核首个可测节点 " + shortText(firstAddressed));
			return true;
		} catch (Exception e) {
			logOnce("proxies-ex", "测速: 解析 /proxies 失败 " + e);
			return false;
		}
	}

	private String realNodeName(ClashNode n) {
		if (proxyNameCache == null)
		  return null;
		/* 1) By the node's own NAME first: the running core was configured by
		   us, so its proxy names are the subscription names (plus our own
		   de-dup suffix) and this match cannot be broken by the core reporting
		   a resolved IP or a different protocol spelling. */
		if (n.name != null && !n.name.isEmpty()) {
			String byName = proxyNameCache.get("name:" + n.name);
			if (byName != null)
			  return byName;
		}
		String server = n.server == null ? "" : n.server.toLowerCase();
		String base = server + "|" + n.port;
		String name = proxyNameCache.get(base);
		if (name != null)
		  return name;
		String type = n.type == null ? "" : n.type.toLowerCase();
		return proxyNameCache.get(base + "|" + type);
	}

	/* The clash-api is ALWAYS bound to the loopback (external-controller:
	   127.0.0.1), and with allow-lan off mihomo only serves loopback clients -
	   so the device's own IP can never reach it. Falling back to the device IP
	   used to latch onto a host with no listener, which made every /proxies and
	   /delay call fail (i.e. "all speed tests not passing"). Use 127.0.0.1
	   exclusively. */
	private String resolveApiHost() {
		if (apiHost != null)
		  return apiHost;
		if (probeApi("127.0.0.1")) {
			apiHost = "127.0.0.1";
			return apiHost;
		}
		return null;
	}

	private boolean probeApi(String host) {
	/* Reachability is decided by the in-process bridge first, so selector and
	   speed-test logic proceed even when the clash-api HTTP listener (9090)
	   never binds. Fall back to the HTTP /version probe only when the bridge
	   is unavailable (e.g. core not loaded yet). */
	if (TProxyService.isCoreReachable())
	  return true;
	TProxyService.ApiResult r = TProxyService.bridgeApi("GET", host,
		MihomoConfig.API_PORT, "/version", null, prefs.getSecret());
	/* 2xx = healthy. 401 = the listener is up but auth is wrong: still
	   "reachable", so latch onto this host and let the real call surface
	   the 401 instead of falling back to a dead address. */
	return (r.code >= 200 && r.code < 300) || r.code == 401;
	}

	/* First non-loopback, non-TUN IPv4 of the device, kept as a fallback in case
	   the 127.0.0.1 loopback is captured by the TUN. The VPN tunnel interface
	   (tun*) is skipped so we never pick the captured tunnel. Falls back to
	   127.0.0.1. */
	private String deviceHost() {
		try {
			java.util.Enumeration<java.net.NetworkInterface> en =
				java.net.NetworkInterface.getNetworkInterfaces();
			while (en.hasMoreElements()) {
				java.net.NetworkInterface nif = en.nextElement();
				if (nif.isLoopback() || !nif.isUp())
				  continue;
				String n = nif.getName();
				if (n != null && (n.startsWith("tun")
						|| n.startsWith("ppp") || n.contains("tun")))
				  continue;
				java.util.Enumeration<java.net.InetAddress> adds = nif.getInetAddresses();
				while (adds.hasMoreElements()) {
					java.net.InetAddress a = adds.nextElement();
					if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress())
					  return a.getHostAddress();
				}
			}
		} catch (Throwable ignore) { }
		return "127.0.0.1";
	}

	/* Path-safe encoding for clash-api URLs: encode, then turn "+" back into
	   "%20" so a space in a node name is not mistaken for a literal "+". */
	private static String encodePath(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return s;
		}
	}

	private static String readApiBody(HttpURLConnection c) {
		try {
		InputStream in = (c.getResponseCode() >= 400) ? c.getErrorStream() : c.getInputStream();
			if (in == null)
			  return "";
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int n;
			while ((n = in.read(buf)) > 0)
			  bos.write(buf, 0, n);
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private void useNode(final ClashNode n) {
		/* Tapping a node while auto-select is on: the url-test group keeps
		   picking the fastest node, so the tap is only a hint. We used to flip
		   auto-select off here, which silently discarded the user's choice
		   ("I turned it on, came back, it was off"). Auto-select now stays on
		   and persists; the tap is acknowledged with a hint. */
		boolean auto = prefs.getAutoSelect();
		prefs.setSubSelected(n.name);
		/* Picking a subscription node hands the upstream back to the
		   subscription, so any enabled SOCKS5 server is disabled. */
		prefs.setActiveSocksId("");
		String msg;
		if (prefs.getEnable()) {
			startService(new Intent(this, TProxyService.class)
				.setAction(TProxyService.ACTION_SELECT));
			msg = auto
				? getString(R.string.sub_auto_pick, n.name)
				: getString(R.string.sub_switched_restart, n.name);
		} else {
			msg = auto
				? getString(R.string.sub_auto_pick, n.name)
				: getString(R.string.sub_applied_hint, n.name);
		}
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
		updateAutoUi();
	}

	private void updateAutoUi() {
		boolean auto = prefs.getAutoSelect();
		/* setChecked() only fires the listener when the value actually
		   changes, so this cannot recurse. */
		switch_auto.setChecked(auto);
		textview_auto_hint.setText(auto
			? getString(R.string.sub_auto_on_hint)
			: getString(R.string.sub_manual_hint));
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
			TextView source = (TextView) convertView.findViewById(R.id.item_sub);
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
				proto.setBackgroundColor(protoColor(n.type));
				proto.setTextColor(Color.WHITE);
				proto.setVisibility(View.VISIBLE);
			}
			/* Subscription source tag: tells which pool a node came from. */
			if (n.subId != null && subNames.containsKey(n.subId)
					&& subNames.get(n.subId) != null
					&& !subNames.get(n.subId).isEmpty()) {
				source.setText(subNames.get(n.subId));
				source.setVisibility(View.VISIBLE);
			} else {
				source.setVisibility(View.GONE);
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
					corePrepared = false;
					/* Fresh pass keys: a single-node re-test must log its own
					   reason even if the same one was already printed by a
					   previous "测速全部". */
					passLogged.clear();
					/* Show the same progress line/bar (0/1 -> 1/1) so a single
					   re-test is visibly "running" too. */
					testsDone = 0;
					testTotal = 1;
					pendingTests = 1;
					updateTestProgress();
					testNode(n, false);
				}
			});
			return convertView;
		}
	}
}
