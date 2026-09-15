/*
 ============================================================================
 Name        : Perferences.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Perferences
 ============================================================================
 */

package com.tunvpn;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONException;
import org.json.JSONObject;

public class Preferences
{
	public static final String PREFS_NAME = "SocksPrefs";
	public static final String SUB_URL = "SubUrl";
	public static final String SUB_NODES = "SubNodes";
	public static final String SUB_RAW = "SubRaw";
	public static final String SUB_SELECTED = "SubSelected";
	public static final String SOCKS_SERVERS = "SocksServers";
	public static final String SOCKS_ACTIVE = "SocksActive";
	public static final String SUBSCRIPTIONS = "Subscriptions";
	public static final String SUB_ACTIVE = "SubActive";
	/* Subscribe-page view state, remembered across runs (sort mode,
	   availability filter, country filter and protocol filter). */
	public static final String SUB_SORT = "SubSort";
	public static final String SUB_FILTER = "SubFilter";
	public static final String SUB_COUNTRY_FILTER = "SubCountryFilter";
	public static final String SUB_PROTO_FILTER = "SubProtoFilter";
	public static final String DNS_IPV4 = "DnsIpv4";
	public static final String DNS_IPV6 = "DnsIpv6";
	public static final String IPV4 = "Ipv4";
	public static final String IPV6 = "Ipv6";
	public static final String GLOBAL = "Global";
	public static final String UDP_IN_TCP = "UdpInTcp";
	public static final String REMOTE_DNS = "RemoteDNS";
	public static final String APPS = "Apps";
	public static final String RULES = "Rules";
	public static final String RULES_DEFAULT_PROXY = "RulesDefaultProxy";
	/* Routing strategy: rule mode applies the user's rules (and MATCH falls back
	   to proxy/direct per RULES_DEFAULT_PROXY); the two global modes route
	   everything one way and ignore the rule list. */
	public static final String RULES_STRATEGY = "RulesStrategy";
	public static final String RULES_STRATEGY_RULES = "rules";
	public static final String RULES_STRATEGY_GLOBAL = "global";
	public static final String RULES_STRATEGY_DIRECT = "direct";
	public static final String ENABLE = "Enable";
	public static final String LAST_ERROR = "LastError";
	public static final String NAME = "Name";
	public static final String PROFILE_COUNT = "ProfileCount";
	public static final String SELECTED = "Selected";
	public static final String LOG_ENABLED = "LogEnabled";
	/* When set, the tunnel loads files/config.yaml as-is instead of rebuilding it
	   from the app's settings on every connect (the file is then hand-edited from
	   the config screen). */
	public static final String CUSTOM_CONFIG = "CustomConfig";
	public static final String STATS_TOTAL_TX = "StatsTotalTx";
	public static final String STATS_TOTAL_RX = "StatsTotalRx";
	public static final String STATS_SESSION_TX = "StatsSessionTx";
	public static final String STATS_SESSION_RX = "StatsSessionRx";
	public static final String STATS_RATE_TX = "StatsRateTx";
	public static final String STATS_RATE_RX = "StatsRateRx";
	/* Counters that cover only traffic which went through a node. The core
	   reports everything it handles, direct traffic included, so these are
	   accumulated separately from its connection list. */
	public static final String PROXY_TOTAL_TX = "ProxyTotalTx";
	public static final String PROXY_TOTAL_RX = "ProxyTotalRx";
	public static final String PROXY_SESSION_TX = "ProxySessionTx";
	public static final String PROXY_SESSION_RX = "ProxySessionRx";
	public static final String PROXY_RATE_TX = "ProxyRateTx";
	public static final String PROXY_RATE_RX = "ProxyRateRx";
	public static final String STATS_APP_BASE = "StatsAppBase";
	public static final String STATS_APP_TOTAL = "StatsAppTotal";
	public static final String THEME = "Theme";
	/* Per-subscription caches (key + subscription id) so several
	   subscriptions can be fetched and merged into one node pool. */
	public static final String SUB_RAW_PREFIX = "SubRaw.";
	public static final String SUB_NODES_PREFIX = "SubNodes.";
	/* Auto-select: the core's url-test group picks the fastest node. */
	public static final String AUTO_SELECT = "AutoSelect";
	public static final String AUTO_SELECT_INTERVAL = "AutoSelectInterval";
	public static final String AUTO_TEST_URL = "AutoTestUrl";
	/* Auto mode only: which country group the top group should default to.
	   Empty / "GLOBAL" means "fastest anywhere". The value is an ISO-3166
	   alpha-2 code, matching the per-country url-test group names built in
	   MihomoConfig. */
	public static final String AUTO_SELECT_COUNTRY = "AutoSelectCountry";
	/* Persisted server -> ISO country-code map, filled by GeoIp resolution so
	   config generation can group nodes offline (no network at tunnel start). */
	public static final String SERVER_COUNTRY_MAP = "ServerCountryMap";
	/* Local proxy port the core listens on, and whether it is exposed to the
	   LAN. */
	public static final String PROXY_PORT = "ProxyPort";
	public static final String ALLOW_LAN = "AllowLan";
	public static final int DEFAULT_PROXY_PORT = 7890;
	public static final int MIN_PROXY_PORT = 1024;
	public static final int MAX_PROXY_PORT = 65535;
	/* mihomo's own default for url-test. */
	public static final int DEFAULT_AUTO_INTERVAL = 300;
	/* Health-check target for url-test: a 204 endpoint is cheap and most
	   networks do not intercept it. */
	public static final String DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204";

	public static final int MAX_PROFILES = 13;

	/* One routing rule: "value -> proxy or direct", with an explicit type so
	   the config builder emits exactly the clash rule the user picked. */
	public static class Rule {
		public static final int TYPE_DOMAIN = 0;   /* DOMAIN-SUFFIX */
		public static final int TYPE_IP = 1;       /* single IP -> IP-CIDR /32|/128 */
		public static final int TYPE_CIDR = 2;     /* IP-CIDR / IP-CIDR6 */
		public static final int TYPE_KEYWORD = 3;  /* DOMAIN-KEYWORD */
		public static final int TYPE_GEOIP = 4;    /* GEOIP,<country> */
		public static final int TYPE_PROCESS = 5;  /* PROCESS-NAME */
		/* Added later; the numeric values must stay stable because they are what
		   gets persisted, and the rule_types array order in strings.xml has to
		   match them (the spinner position is the type). */
		public static final int TYPE_DOMAIN_FULL = 6;   /* DOMAIN (exact) */
		public static final int TYPE_GEOSITE = 7;       /* GEOSITE,<name> */
		public static final int TYPE_PROCESS_PATH = 8;  /* PROCESS-PATH */
		public static final int TYPE_DST_PORT = 9;      /* DST-PORT */
		public static final int TYPE_SRC_PORT = 10;     /* SRC-PORT */
		public static final int TYPE_NETWORK = 11;      /* NETWORK,tcp|udp */

		public int type;
		public String value;
		public boolean proxy;

		public Rule(int type, String value, boolean proxy) {
			this.type = type;
			this.value = value;
			this.proxy = proxy;
		}

		public String encode() {
			return type + "|" + value + "|" + (proxy ? "1" : "0");
		}

		public static Rule decode(String text) {
			String[] f = text.split("\\|");
			if (f.length != 3 || f[1].isEmpty())
			  return null;
			try {
				return new Rule(Integer.parseInt(f[0]), f[1], "1".equals(f[2]));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	private SharedPreferences prefs;
	private Context mContext;

	public Preferences(Context context) {
		mContext = context.getApplicationContext();
		prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
		migrate();
		migrateSubCache();
	}

	/* Application context, used by helpers that need assets / system services
	   (e.g. GeoIp reading the bundled GeoLite2 database). */
	public Context getContext() {
		return mContext;
	}

	/* Older builds kept exactly one fetched subscription in a single cache.
	   Move it onto the active subscription so the merged node list still
	   shows what the user had before upgrading. */
	private void migrateSubCache() {
		String activeId = getActiveSubId();
		if (activeId == null || activeId.isEmpty())
		  return;
		String legacyNodes = prefs.getString(key(SUB_NODES), "");
		String legacyRaw = prefs.getString(key(SUB_RAW), "");
		if (legacyNodes.isEmpty() && legacyRaw.isEmpty())
		  return;
		if (!prefs.getString(key(SUB_NODES_PREFIX + activeId), "").isEmpty())
		  return; /* already on the per-subscription layout */
		SharedPreferences.Editor editor = prefs.edit();
		if (!legacyRaw.isEmpty())
		  editor.putString(key(SUB_RAW_PREFIX + activeId), legacyRaw);
		if (!legacyNodes.isEmpty())
		  editor.putString(key(SUB_NODES_PREFIX + activeId), legacyNodes);
		editor.remove(key(SUB_NODES));
		editor.remove(key(SUB_RAW));
		editor.commit();
	}

	/* Profile-scoped key: all per-server settings live under "P<index>." */
	private static String key(int profile, String name) {
		return "P" + profile + "." + name;
	}

	private String key(String name) {
		return key(getSelected(), name);
	}

	/* One-time migration of legacy flat keys into profile 0. */
	private void migrate() {
		if (prefs.contains(PROFILE_COUNT))
		  return;

		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(0, NAME), "Default");
		if (prefs.contains(DNS_IPV4))
		  editor.putString(key(0, DNS_IPV4), prefs.getString(DNS_IPV4, ""));
		if (prefs.contains(DNS_IPV6))
		  editor.putString(key(0, DNS_IPV6), prefs.getString(DNS_IPV6, ""));
		if (prefs.contains(IPV4))
		  editor.putBoolean(key(0, IPV4), prefs.getBoolean(IPV4, true));
		if (prefs.contains(IPV6))
		  editor.putBoolean(key(0, IPV6), prefs.getBoolean(IPV6, true));
		if (prefs.contains(GLOBAL))
		  editor.putBoolean(key(0, GLOBAL), prefs.getBoolean(GLOBAL, false));
		if (prefs.contains(UDP_IN_TCP))
		  editor.putBoolean(key(0, UDP_IN_TCP), prefs.getBoolean(UDP_IN_TCP, false));
		if (prefs.contains(REMOTE_DNS))
		  editor.putBoolean(key(0, REMOTE_DNS), prefs.getBoolean(REMOTE_DNS, true));
		if (prefs.contains(APPS))
		  editor.putStringSet(key(0, APPS), new HashSet<String>(prefs.getStringSet(APPS, new HashSet<String>())));
		editor.remove(DNS_IPV4);
		editor.remove(DNS_IPV6);
		editor.remove(IPV4);
		editor.remove(IPV6);
		editor.remove(GLOBAL);
		editor.remove(UDP_IN_TCP);
		editor.remove(REMOTE_DNS);
		editor.remove(APPS);
		editor.putInt(PROFILE_COUNT, 1);
		editor.putInt(SELECTED, 0);
		editor.commit();
	}

	/* Copy every per-profile key from profile src to profile dst.
	   Reads always see the committed state, so shifting profiles
	   in ascending order within one editor is safe. */
	private void copyProfile(SharedPreferences.Editor editor, int src, int dst) {
		editor.putString(key(dst, NAME), prefs.getString(key(src, NAME), "Default"));
		editor.putString(key(dst, DNS_IPV4), prefs.getString(key(src, DNS_IPV4), "8.8.8.8"));
		editor.putString(key(dst, DNS_IPV6), prefs.getString(key(src, DNS_IPV6), "2001:4860:4860::8888"));
		editor.putBoolean(key(dst, IPV4), prefs.getBoolean(key(src, IPV4), true));
		editor.putBoolean(key(dst, IPV6), prefs.getBoolean(key(src, IPV6), true));
		editor.putBoolean(key(dst, GLOBAL), prefs.getBoolean(key(src, GLOBAL), false));
		editor.putBoolean(key(dst, UDP_IN_TCP), prefs.getBoolean(key(src, UDP_IN_TCP), false));
		editor.putBoolean(key(dst, REMOTE_DNS), prefs.getBoolean(key(src, REMOTE_DNS), true));
		editor.putStringSet(key(dst, APPS), new HashSet<String>(prefs.getStringSet(key(src, APPS), new HashSet<String>())));
		editor.putString(key(dst, RULES), prefs.getString(key(src, RULES), ""));
		editor.putBoolean(key(dst, RULES_DEFAULT_PROXY), prefs.getBoolean(key(src, RULES_DEFAULT_PROXY), true));
		editor.putString(key(dst, SUB_URL), prefs.getString(key(src, SUB_URL), ""));
		editor.putString(key(dst, SUB_NODES), prefs.getString(key(src, SUB_NODES), ""));
		editor.putString(key(dst, SUB_RAW), prefs.getString(key(src, SUB_RAW), ""));
		editor.putString(key(dst, SUB_SELECTED), prefs.getString(key(src, SUB_SELECTED), ""));
		editor.putString(key(dst, SOCKS_SERVERS), prefs.getString(key(src, SOCKS_SERVERS), ""));
		editor.putString(key(dst, SOCKS_ACTIVE), prefs.getString(key(src, SOCKS_ACTIVE), ""));
		editor.putString(key(dst, SUBSCRIPTIONS), prefs.getString(key(src, SUBSCRIPTIONS), ""));
		editor.putString(key(dst, SUB_ACTIVE), prefs.getString(key(src, SUB_ACTIVE), ""));
		editor.putInt(key(dst, SUB_SORT), prefs.getInt(key(src, SUB_SORT), 0));
		editor.putInt(key(dst, SUB_FILTER), prefs.getInt(key(src, SUB_FILTER), 0));
		editor.putString(key(dst, SUB_COUNTRY_FILTER), prefs.getString(key(src, SUB_COUNTRY_FILTER), ""));
		editor.putString(key(dst, SUB_PROTO_FILTER), prefs.getString(key(src, SUB_PROTO_FILTER), ""));
	}

	private void removeProfile(SharedPreferences.Editor editor, int profile) {
		editor.remove(key(profile, NAME));
		editor.remove(key(profile, DNS_IPV4));
		editor.remove(key(profile, DNS_IPV6));
		editor.remove(key(profile, IPV4));
		editor.remove(key(profile, IPV6));
		editor.remove(key(profile, GLOBAL));
		editor.remove(key(profile, UDP_IN_TCP));
		editor.remove(key(profile, REMOTE_DNS));
		editor.remove(key(profile, APPS));
		editor.remove(key(profile, RULES));
		editor.remove(key(profile, RULES_DEFAULT_PROXY));
		editor.remove(key(profile, SUB_URL));
		editor.remove(key(profile, SUB_NODES));
		editor.remove(key(profile, SUB_RAW));
		editor.remove(key(profile, SUB_SELECTED));
		editor.remove(key(profile, SOCKS_SERVERS));
		editor.remove(key(profile, SOCKS_ACTIVE));
		editor.remove(key(profile, SUBSCRIPTIONS));
		editor.remove(key(profile, SUB_ACTIVE));
		editor.remove(key(profile, SUB_SORT));
		editor.remove(key(profile, SUB_FILTER));
		editor.remove(key(profile, SUB_COUNTRY_FILTER));
		editor.remove(key(profile, SUB_PROTO_FILTER));
	}

	public int getProfileCount() {
		return prefs.getInt(PROFILE_COUNT, 1);
	}

	public int getSelected() {
		return prefs.getInt(SELECTED, 0);
	}

	public void setSelected(int index) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(SELECTED, index);
		editor.commit();
	}

	public String getProfileName() {
		return prefs.getString(key(NAME), "Default");
	}

	public void setProfileName(String name) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(NAME), name);
		editor.commit();
	}

	/* Create a new profile as a copy of the current one and select it. */
	public boolean addProfile(String name) {
		int count = getProfileCount();
		if (count >= MAX_PROFILES)
		  return false;

		SharedPreferences.Editor editor = prefs.edit();
		copyProfile(editor, getSelected(), count);
		editor.putString(key(count, NAME), name);
		editor.putInt(PROFILE_COUNT, count + 1);
		editor.putInt(SELECTED, count);
		editor.commit();
		return true;
	}

	/* Delete the current profile, shifting the following ones down. */
	public boolean deleteProfile() {
		int count = getProfileCount();
		if (count <= 1)
		  return false;

		int selected = getSelected();
		SharedPreferences.Editor editor = prefs.edit();
		for (int i = selected + 1; i < count; i++)
		  copyProfile(editor, i, i - 1);
		removeProfile(editor, count - 1);
		editor.putInt(PROFILE_COUNT, count - 1);
		if (selected >= count - 1)
		  editor.putInt(SELECTED, count - 2);
		editor.commit();
		return true;
	}

	/* The stored clash.yml subscriptions, and which one is enabled. The
	   enabled one is what the subscribe page fetches. */
	public List<Subscription> getSubscriptions() {
		return Subscription.decode(prefs.getString(key(SUBSCRIPTIONS), ""));
	}

	public void setSubscriptions(List<Subscription> list) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUBSCRIPTIONS), Subscription.encode(list));
		/* There is no "default subscription" any more: every subscription is
		   fetched and merged. Keep the first one recorded as the active id so
		   the few callers that ask for "the" subscription still work. */
		String active = getActiveSubId();
		boolean present = false;
		for (Subscription s : list) {
			if (s != null && s.id != null && s.id.equals(active)) {
				present = true;
				break;
			}
		}
		if (!present)
		  editor.putString(key(SUB_ACTIVE),
			(list.isEmpty() || list.get(0).id == null) ? "" : list.get(0).id);
		editor.commit();
	}

	public String getActiveSubId() {
		return prefs.getString(key(SUB_ACTIVE), "");
	}

	public void setActiveSubId(String id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_ACTIVE), id == null ? "" : id);
		editor.commit();
	}

	public Subscription getActiveSubscription() {
		String id = getActiveSubId();
		if (id == null || id.isEmpty())
		  return null;
		for (Subscription s : getSubscriptions()) {
			if (id.equals(s.id))
			  return s;
		}
		return null;
	}

	/* Remote clash.yml subscription (per profile). */
	public String getSubUrl() {
		return prefs.getString(key(SUB_URL), "");
	}

	public void setSubUrl(String url) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_URL), url);
		editor.commit();
	}

	public String getSubSelected() {
		return prefs.getString(key(SUB_SELECTED), "");
	}

	public void setSubSelected(String name) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_SELECTED), name);
		editor.commit();
	}

	public boolean hasSubscription() {
		String raw = getSubRaw();
		if (raw != null && !raw.trim().isEmpty())
		  return true;
		/* With no "default" subscription recorded, fall back to scanning every
		   subscription's own cache. */
		for (Subscription s : getSubscriptions()) {
			String r = getSubRaw(s.id);
			if (r != null && !r.trim().isEmpty())
			  return true;
		}
		return false;
	}

	/* The manually configured SOCKS5 servers, plus which one is enabled.
	   An enabled server takes over from the subscription. */
	public List<SocksServer> getSocksServers() {
		return SocksServer.decode(prefs.getString(key(SOCKS_SERVERS), ""));
	}

	public void setSocksServers(List<SocksServer> list) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_SERVERS), SocksServer.encode(list));
		editor.commit();
	}

	public String getActiveSocksId() {
		return prefs.getString(key(SOCKS_ACTIVE), "");
	}

	public void setActiveSocksId(String id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_ACTIVE), id == null ? "" : id);
		editor.commit();
	}

	/* The enabled server, or null when the subscription is in charge. */
	public SocksServer getActiveSocksServer() {
		String id = getActiveSocksId();
		if (id == null || id.isEmpty())
		  return null;
		for (SocksServer s : getSocksServers()) {
			if (id.equals(s.id))
			  return s;
		}
		return null;
	}

	/* Which node the tunnel is meant to use. Empty means "let the
	   subscription decide for itself". */
	public String getCurrentNode() {
		SocksServer s = getActiveSocksServer();
		if (s != null)
		  return socks5Label(s);
		String sel = getSubSelected();
		if (sel != null && !sel.isEmpty())
		  return sel;
		return "";
	}

	private String socks5Label(SocksServer s) {
		String addr = s.addr == null ? "" : s.addr.trim();
		if (addr.isEmpty())
		  return "";
		return "socks5://" + addr + ":" + s.port;
	}

	/* Raw clash.yml text of the active subscription (already base64-decoded
	   if the provider shipped it that way). */
	public String getSubRaw() {
		Subscription sub = getActiveSubscription();
		if (sub != null)
		  return getSubRaw(sub.id);
		return prefs.getString(key(SUB_RAW), "");
	}

	/* Per-subscription cache: each subscription keeps its own fetched YAML
	   and node list, so several can be pulled and merged into one pool. */
	public String getSubRaw(String id) {
		if (id == null || id.isEmpty())
		  return "";
		return prefs.getString(key(SUB_RAW_PREFIX + id), "");
	}

	public void setSubRaw(String id, String yaml) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_RAW_PREFIX + id), yaml == null ? "" : yaml);
		editor.commit();
	}

	public String getSubNodes(String id) {
		if (id == null || id.isEmpty())
		  return "";
		return prefs.getString(key(SUB_NODES_PREFIX + id), "");
	}

	public void setSubNodes(String id, String json) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_NODES_PREFIX + id), json == null ? "" : json);
		editor.commit();
	}

	public void clearSubCache(String id) {
		if (id == null || id.isEmpty())
		  return;
		SharedPreferences.Editor editor = prefs.edit();
		editor.remove(key(SUB_RAW_PREFIX + id));
		editor.remove(key(SUB_NODES_PREFIX + id));
		editor.commit();
	}

	/* Auto-select: the core's url-test group picks the fastest node by
	   itself, re-testing every `interval` seconds. Off means "use exactly
	   the node the user tapped". */
	public boolean getAutoSelect() {
		return prefs.getBoolean(key(AUTO_SELECT), true);
	}

	public void setAutoSelect(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(AUTO_SELECT), enable);
		editor.commit();
	}

	public int getAutoSelectInterval() {
		return prefs.getInt(key(AUTO_SELECT_INTERVAL), DEFAULT_AUTO_INTERVAL);
	}

	public void setAutoSelectInterval(int seconds) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(AUTO_SELECT_INTERVAL), seconds);
		editor.commit();
	}

	/* url-test health-check target. Empty means "use the default". */
	public String getAutoTestUrl() {
		String url = prefs.getString(key(AUTO_TEST_URL), "");
		if (url == null || url.trim().isEmpty())
		  return DEFAULT_TEST_URL;
		return url.trim();
	}

	public void setAutoTestUrl(String url) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(AUTO_TEST_URL), url == null ? "" : url.trim());
		editor.commit();
	}

	/* Auto mode: the country whose url-test group the top group defaults to.
	   "" or "GLOBAL" => fastest node anywhere. */
	public String getAutoSelectCountry() {
		return prefs.getString(key(AUTO_SELECT_COUNTRY), "");
	}

	public void setAutoSelectCountry(String cc) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(AUTO_SELECT_COUNTRY), cc == null ? "" : cc);
		editor.commit();
	}

	/* Subscribe-page view state (sort / availability / country / protocol),
	   so reopening the page keeps the user's last choice instead of resetting
	   to "all". */
	public int getSubSort() {
		return prefs.getInt(key(SUB_SORT), 0);
	}

	public void setSubSort(int mode) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(SUB_SORT), mode);
		editor.commit();
	}

	public int getSubFilter() {
		return prefs.getInt(key(SUB_FILTER), 0);
	}

	public void setSubFilter(int mode) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(SUB_FILTER), mode);
		editor.commit();
	}

	public String getSubCountryFilter() {
		return prefs.getString(key(SUB_COUNTRY_FILTER), "");
	}

	public void setSubCountryFilter(String cc) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_COUNTRY_FILTER), cc == null ? "" : cc);
		editor.commit();
	}

	public String getSubProtoFilter() {
		return prefs.getString(key(SUB_PROTO_FILTER), "");
	}

	public void setSubProtoFilter(String type) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_PROTO_FILTER), type == null ? "" : type);
		editor.commit();
	}

	/* Country of a proxy's server, resolved earlier by GeoIp. "" means not yet
	   known (treated as "OTHER" at config build time). */
	public String getServerCountry(String server) {
		JSONObject map = loadCountryMap();
		return map.optString(server == null ? "" : server, "");
	}

	public void setServerCountry(String server, String cc) {
		if (server == null || server.isEmpty() || cc == null || cc.isEmpty())
		  return;
		JSONObject map = loadCountryMap();
		try {
			map.put(server, cc);
		} catch (JSONException e) {
			return;
		}
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SERVER_COUNTRY_MAP), map.toString());
		editor.commit();
	}

	private JSONObject loadCountryMap() {
		String s = prefs.getString(key(SERVER_COUNTRY_MAP), "");
		if (s == null || s.isEmpty())
		  return new JSONObject();
		try {
			return new JSONObject(s);
		} catch (JSONException e) {
			return new JSONObject();
		}
	}

	/* The core's local HTTP/SOCKS port. Out of range means "never set". */
	public int getProxyPort() {
		int port = prefs.getInt(key(PROXY_PORT), DEFAULT_PROXY_PORT);
		if (port < MIN_PROXY_PORT || port > MAX_PROXY_PORT)
		  return DEFAULT_PROXY_PORT;
		return port;
	}

	public void setProxyPort(int port) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(PROXY_PORT), port);
		editor.commit();
	}

	/* Expose the port to the whole LAN (mihomo's allow-lan). Off by default:
	   an open proxy on a shared Wi-Fi lets anyone on it use the tunnel. */
	public boolean getAllowLan() {
		return prefs.getBoolean(key(ALLOW_LAN), false);
	}

	public void setAllowLan(boolean allow) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(ALLOW_LAN), allow);
		editor.commit();
	}

	public String getDnsIpv4() {
		return prefs.getString(key(DNS_IPV4), "8.8.8.8");
	}

	public void setDnsIpv4(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(DNS_IPV4), addr);
		editor.commit();
	}

	public String getDnsIpv6() {
		return prefs.getString(key(DNS_IPV6), "2001:4860:4860::8888");
	}

	public void setDnsIpv6(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(DNS_IPV6), addr);
		editor.commit();
	}

	public boolean getUdpInTcp() {
		return prefs.getBoolean(key(UDP_IN_TCP), false);
	}

	public void setUdpInTcp(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(UDP_IN_TCP), enable);
		editor.commit();
	}

	public boolean getRemoteDns() {
		return prefs.getBoolean(key(REMOTE_DNS), true);
	}

	public void setRemoteDns(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(REMOTE_DNS), enable);
		editor.commit();
	}

	public boolean getIpv4() {
		return prefs.getBoolean(key(IPV4), true);
	}

	public void setIpv4(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(IPV4), enable);
		editor.commit();
	}

	public boolean getIpv6() {
		return prefs.getBoolean(key(IPV6), true);
	}

	public void setIpv6(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(IPV6), enable);
		editor.commit();
	}

	public boolean getGlobal() {
		return prefs.getBoolean(key(GLOBAL), false);
	}

	public void setGlobal(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(GLOBAL), enable);
		editor.commit();
	}

	public Set<String> getApps() {
		return prefs.getStringSet(key(APPS), new HashSet<String>());
	}

	public void setApps(Set<String> apps) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putStringSet(key(APPS), apps);
		editor.commit();
	}

	/* Routing rules, in the order they were added (first match wins). */
	public List<Rule> getRules() {
		List<Rule> rules = new ArrayList<Rule>();
		String value = prefs.getString(key(RULES), "");
		if (value.isEmpty())
		  return rules;
		for (String part : value.split(";")) {
			Rule rule = Rule.decode(part);
			if (rule != null)
			  rules.add(rule);
		}
		return rules;
	}

	public void setRules(List<Rule> rules) {
		StringBuilder sb = new StringBuilder();
		for (Rule rule : rules) {
			if (sb.length() > 0)
			  sb.append(';');
			sb.append(rule.encode());
		}
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(RULES), sb.toString());
		editor.commit();
	}

	/* What happens to traffic that matches no rule. */
	public boolean getRulesDefaultProxy() {
		return prefs.getBoolean(key(RULES_DEFAULT_PROXY), true);
	}

	public void setRulesDefaultProxy(boolean proxy) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(key(RULES_DEFAULT_PROXY), proxy);
		editor.commit();
	}

	public String getRulesStrategy() {
		return prefs.getString(key(RULES_STRATEGY), RULES_STRATEGY_RULES);
	}

	public void setRulesStrategy(String s) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(RULES_STRATEGY),
			s == null ? RULES_STRATEGY_RULES : s);
		editor.commit();
	}

	public boolean getEnable() {
		return prefs.getBoolean(ENABLE, false);
	}

	/* Why the last start attempt failed. TProxyService writes it together with
	   Enable=false, so the UI can tell "never started" from "started and died"
	   instead of showing a stuck "connected". */
	public String getLastError() {
		return prefs.getString(LAST_ERROR, "");
	}

	public void setLastError(String error) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(LAST_ERROR, error == null ? "" : error);
		editor.commit();
	}

	public void clearLastError() {
		setLastError("");
	}

	public void setEnable(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(ENABLE, enable);
		editor.commit();
	}

	public boolean getLogEnabled() {
		return prefs.getBoolean(LOG_ENABLED, true);
	}

	public void setLogEnabled(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(LOG_ENABLED, enable);
		editor.commit();
	}

	/* Use the on-disk config.yaml verbatim instead of regenerating it from the
	   current settings on every connect. */
	public boolean getCustomConfig() {
		return prefs.getBoolean(CUSTOM_CONFIG, false);
	}

	public void setCustomConfig(boolean enable) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(CUSTOM_CONFIG, enable);
		editor.commit();
	}

	/* Traffic accumulated over every session (global, not per-profile).
	   Written with commit() because the writer runs in the :native process
	   and MODE_MULTI_PROCESS only re-reads on commit. */
	public long getTotalTx() {
		return prefs.getLong(STATS_TOTAL_TX, 0);
	}

	public long getTotalRx() {
		return prefs.getLong(STATS_TOTAL_RX, 0);
	}

	public void setTotalTraffic(long tx, long rx) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putLong(STATS_TOTAL_TX, tx);
		editor.putLong(STATS_TOTAL_RX, rx);
		editor.commit();
	}

	public void resetTotalTraffic() {
		setTotalTraffic(0, 0);
	}

	/* Everything the :native service knows, written in one commit. */
	public long getSessionTx() {
		return prefs.getLong(STATS_SESSION_TX, 0);
	}

	public long getSessionRx() {
		return prefs.getLong(STATS_SESSION_RX, 0);
	}

	public long getRateTx() {
		return prefs.getLong(STATS_RATE_TX, 0);
	}

	public long getRateRx() {
		return prefs.getLong(STATS_RATE_RX, 0);
	}

	public long getProxyTotalTx() {
		return prefs.getLong(PROXY_TOTAL_TX, 0);
	}

	public long getProxyTotalRx() {
		return prefs.getLong(PROXY_TOTAL_RX, 0);
	}

	public long getProxySessionTx() {
		return prefs.getLong(PROXY_SESSION_TX, 0);
	}

	public long getProxySessionRx() {
		return prefs.getLong(PROXY_SESSION_RX, 0);
	}

	public long getProxyRateTx() {
		return prefs.getLong(PROXY_RATE_TX, 0);
	}

	public long getProxyRateRx() {
		return prefs.getLong(PROXY_RATE_RX, 0);
	}

	public void setProxyStats(long totalTx, long totalRx, long sessionTx, long sessionRx,
			long rateTx, long rateRx) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putLong(PROXY_TOTAL_TX, totalTx);
		editor.putLong(PROXY_TOTAL_RX, totalRx);
		editor.putLong(PROXY_SESSION_TX, sessionTx);
		editor.putLong(PROXY_SESSION_RX, sessionRx);
		editor.putLong(PROXY_RATE_TX, rateTx);
		editor.putLong(PROXY_RATE_RX, rateRx);
		editor.commit();
	}

	public void resetProxyStats() {
		setProxyStats(0, 0, 0, 0, 0, 0);
	}

	public void setStats(long totalTx, long totalRx, long sessionTx, long sessionRx,
			long rateTx, long rateRx) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putLong(STATS_TOTAL_TX, totalTx);
		editor.putLong(STATS_TOTAL_RX, totalRx);
		editor.putLong(STATS_SESSION_TX, sessionTx);
		editor.putLong(STATS_SESSION_RX, sessionRx);
		editor.putLong(STATS_RATE_TX, rateTx);
		editor.putLong(STATS_RATE_RX, rateRx);
		editor.commit();
	}

	/* Per-app snapshots: "pkg:tx:rx;pkg:tx:rx;...". */
	public String getAppBase() {
		return prefs.getString(STATS_APP_BASE, "");
	}

	public String getAppTotal() {
		return prefs.getString(STATS_APP_TOTAL, "");
	}

	public void setAppStats(String base, String total) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(STATS_APP_BASE, base);
		editor.putString(STATS_APP_TOTAL, total);
		editor.commit();
	}

	public void resetStats() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putLong(STATS_TOTAL_TX, 0);
		editor.putLong(STATS_TOTAL_RX, 0);
		editor.putLong(STATS_SESSION_TX, 0);
		editor.putLong(STATS_SESSION_RX, 0);
		editor.putLong(STATS_RATE_TX, 0);
		editor.putLong(STATS_RATE_RX, 0);
		editor.putString(STATS_APP_BASE, "");
		editor.putString(STATS_APP_TOTAL, "");
		editor.commit();
	}

	public static Map<String, long[]> parseAppStats(String value) {
		Map<String, long[]> map = new HashMap<String, long[]>();
		if (value == null || value.isEmpty())
		  return map;
		for (String part : value.split(";")) {
			String[] f = part.split(":");
			if (f.length != 3)
			  continue;
			try {
				map.put(f[0], new long[] { Long.parseLong(f[1]), Long.parseLong(f[2]) });
			} catch (NumberFormatException e) {
			}
		}
		return map;
	}

	public static String formatAppStats(Map<String, long[]> map) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, long[]> e : map.entrySet()) {
			if (sb.length() > 0)
			  sb.append(';');
			long[] v = e.getValue();
			sb.append(e.getKey()).append(':').append(v[0]).append(':').append(v[1]);
		}
		return sb.toString();
	}

	/* The set of packages whose traffic goes through the tunnel:
	   the selected apps, or every app with INTERNET when no allow-list is
	   set. An empty allow-list means "everything but this app", which is
	   exactly what VpnService does, so it must be expanded here too. */
	public Set<String> getRoutedApps(Context context) {
		Set<String> apps = new HashSet<String>();
		if (!getGlobal()) {
			apps.addAll(getApps());
			if (!apps.isEmpty())
			  return apps;
		}

		PackageManager pm = context.getPackageManager();
		for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
			if (info.packageName.equals(context.getPackageName()))
			  continue;
			if (info.requestedPermissions == null)
			  continue;
			if (!Arrays.asList(info.requestedPermissions).contains(android.Manifest.permission.INTERNET))
			  continue;
			apps.add(info.packageName);
		}
		return apps;
	}

	public void registerOnChange(SharedPreferences.OnSharedPreferenceChangeListener listener) {
		prefs.registerOnSharedPreferenceChangeListener(listener);
	}

	public void unregisterOnChange(SharedPreferences.OnSharedPreferenceChangeListener listener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener);
	}

	/* Which palette to use (ThemeManager.*). */
	public static int getTheme(Context context) {
		SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
		return sp.getInt(THEME, ThemeManager.NEON);
	}

	public int getTheme() {
		return prefs.getInt(THEME, ThemeManager.NEON);
	}

	public void setTheme(int id) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(THEME, id);
		editor.commit();
	}

	/* VPN interface MTU. This used to be 8500 - a jumbo-frame value - which
	   is far above the 1500 the physical link actually carries. Small packets
	   (the TCP handshake, DNS) still get through, so the tunnel looks
	   connected, while every real data transfer is silently dropped. */
	public int getTunnelMtu() {
		return 1500;
	}
}
