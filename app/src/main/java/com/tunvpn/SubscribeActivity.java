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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
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
	private java.util.Map<String, String> proxyNameCache = null;

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

	/* Rebuild the visible list from nodes according to filter + sort. */
	private void applyView() {
		refreshSubNames();
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
			/* Only a node that passed a latency test is usable. */
			if (n.latency < 0)
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
		/* The running config may have changed (subscription edited / re-picked);
		   rebuild the node-name map and re-probe the api host from scratch. */
		proxyNameCache = null;
		apiHost = null;
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
				/* The verdict comes ONLY from mihomo pushing a request through
				   THIS node (GET /proxies/{name}/delay). That proves the proxy
				   actually tunnels traffic - a bare TCP port-open is NOT enough,
				   so the socket probe was removed. When the real test cannot run
				   (tunnel down, or the node is absent from the running config) we
				   leave the node unverified (-1) rather than claim it is usable. */
				Long real = proxyDelayMs(n);
				if (real != null)
				  n.latency = real;        // >=0 usable, -2 could not tunnel
				else
				  n.latency = -1;          // not verifiable -> unknown, not "available"
				/* (Re)resolve the node's country on every test pass and overwrite
				   the cached value, so a mislabeled flag (e.g. a stale "RU" for a
				   US IP) self-heals instead of being stuck forever. The in-memory
				   cache still dedupes repeated servers within this run. A failed
				   lookup (offline / rate-limited) yields UNKNOWN, which we keep out
				   so a good cached value is never clobbered into "unknown". */
				String cc = GeoIp.countryOf(prefs, n.server, true);
				if (cc != null && !cc.isEmpty() && !GeoIp.UNKNOWN.equals(cc))
				  n.country = cc;
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
	   them is alive; only when every target fails do we call it unusable. This
	   mirrors TProxyService's multi-target reachability so a single blocked
	   target cannot false-negative a working node. */
	private static final String[] PROXY_TEST_URLS = {
		"http://www.gstatic.com/generate_204",
		"http://www.google.com/generate_204",
		"http://www.msftconnecttest.com/connecttest.txt",
	};

	/* Ask mihomo to push a request through THIS node and report the real delay.
	   Returns latency (>=0) when the node actually tunnels, -2 when it cannot,
	   or null when the real test cannot run (tunnel down / node not in the
	   running config). This is the ONLY availability verdict - no TCP fallback.
	   Works for every protocol because mihomo does the handshake. */
	private Long proxyDelayMs(ClashNode n) {
		if (!prefs.getEnable())
		  return null;
		if (!ensureProxyNameCache())
		  return null;
		String name = realNodeName(n);
		if (name == null)
		  return null;
		String host = resolveApiHost();
		if (host == null)
		  return null;
		String base = "http://" + host + ":" + MihomoConfig.API_PORT;
		/* User's configured target first, then the built-in fallbacks. */
		List<String> targets = new ArrayList<String>();
		String userUrl = prefs.getAutoTestUrl();
		if (userUrl != null && !userUrl.isEmpty())
		  targets.add(userUrl);
		for (String u : PROXY_TEST_URLS)
		  if (!targets.contains(u))
			targets.add(u);
		/* Honour the user's latency-test timeout (seconds -> ms). The core
		   measures a real forwarded request, so give it the full budget plus a
		   margin on the HTTP read so the response lands after mihomo's own
		   timer fires. */
		int timeoutMs = prefs.getProxyTestTimeout() * 1000;
		Long failed = null;
		for (String target : targets) {
			try {
				String u = base + "/proxies/" + encodePath(name)
					+ "/delay?timeout=" + timeoutMs + "&url=" + URLEncoder.encode(target, "UTF-8");
				HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection(java.net.Proxy.NO_PROXY);
				MihomoConfig.applyAuth(c, prefs);
				c.setConnectTimeout(5000);
				c.setReadTimeout(timeoutMs + 3000);
				int code = c.getResponseCode();
				if (code == 200) {
					String body = readApiBody(c);
					JSONObject o = new JSONObject(body);
					if (o.has("delay"))
					  return o.getLong("delay");
					return 0L;
				}
				/* Non-200: mihomo could not route through the node for this
				   target; remember it but try the next target before giving up. */
				failed = -2L;
			} catch (Exception e) {
				/* Controller became unreachable mid-run: cannot verify. */
				return null;
			}
		}
		return failed;
	}

	/* Lazily fetch the running config's proxy list and index it by
	   server|port|type -> real (de-dup'd) name, so we can target a node by its
	   address instead of its possibly-renamed label. Returns false when the
	   controller is unreachable. */
	private boolean ensureProxyNameCache() {
		if (proxyNameCache != null)
		  return true;
		if (!prefs.getEnable())
		  return false;
		String host = resolveApiHost();
		if (host == null)
		  return false;
		try {
			String base = "http://" + host + ":" + MihomoConfig.API_PORT;
			HttpURLConnection c = (HttpURLConnection) new URL(base + "/proxies").openConnection(java.net.Proxy.NO_PROXY);
			MihomoConfig.applyAuth(c, prefs);
			c.setConnectTimeout(1500);
			c.setReadTimeout(3000);
			if (c.getResponseCode() != 200) {
				c.disconnect();
				return false;
			}
			String body = readApiBody(c);
			JSONObject o = new JSONObject(body);
			JSONObject proxies = o.optJSONObject("proxies");
			if (proxies == null)
			  return false;
			java.util.Map<String, String> map = new java.util.HashMap<String, String>();
			java.util.Iterator<String> it = proxies.keys();
			while (it.hasNext()) {
				String pname = it.next();
				JSONObject p = proxies.optJSONObject(pname);
				if (p == null)
				  continue;
				String server = p.optString("server", "");
				int port = p.optInt("port", 0);
				String type = p.optString("type", "");
				if (server.isEmpty() || port == 0)
				  continue;
				map.put(server.toLowerCase() + "|" + port + "|" + type, pname);
			}
			proxyNameCache = map;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private String realNodeName(ClashNode n) {
		if (proxyNameCache == null)
		  return null;
		String server = n.server == null ? "" : n.server.toLowerCase();
		String type = n.type == null ? "" : n.type;
		return proxyNameCache.get(server + "|" + n.port + "|" + type);
	}

	/* Pick a reachable clash-api host for this pass. We probe 127.0.0.1 first
	   (the loopback the external-controller is bound to) and fall back to the
	   device's own IP only if the loopback is captured by the TUN. Returns null
	   when neither answers, so the caller keeps the TCP-only check. */
	private String resolveApiHost() {
		if (apiHost != null)
		  return apiHost;
		for (String h : new String[] { "127.0.0.1", deviceHost() }) {
			if (probeApi(h)) {
				apiHost = h;
				return h;
			}
		}
		return null;
	}

	private boolean probeApi(String host) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL("http://" + host + ":"
				+ MihomoConfig.API_PORT + "/version").openConnection(java.net.Proxy.NO_PROXY);
			MihomoConfig.applyAuth(c, prefs);
			c.setConnectTimeout(800);
			c.setReadTimeout(800);
			return c.getResponseCode() >= 200 && c.getResponseCode() < 300;
		} catch (Exception e) {
			return false;
		} finally {
			if (c != null)
			  try { c.disconnect(); } catch (Exception ignore) { }
		}
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
					testNode(n, false);
				}
			});
			return convertView;
		}
	}
}
