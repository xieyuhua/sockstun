/*
 ============================================================================
 Name        : Perferences.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Perferences
 ============================================================================
 */

package hev.sockstun;

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

public class Preferences
{
	public static final String PREFS_NAME = "SocksPrefs";
	public static final String SOCKS_ADDR = "SocksAddr";
	public static final String SOCKS_UDP_ADDR = "SocksUdpAddr";
	public static final String SOCKS_PORT = "SocksPort";
	public static final String SOCKS_USER = "SocksUser";
	public static final String SOCKS_PASS = "SocksPass";
	public static final String SUB_URL = "SubUrl";
	public static final String SUB_NODES = "SubNodes";
	public static final String SUB_SELECTED = "SubSelected";
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
	public static final String ENABLE = "Enable";
	public static final String NAME = "Name";
	public static final String PROFILE_COUNT = "ProfileCount";
	public static final String SELECTED = "Selected";
	public static final String LOG_ENABLED = "LogEnabled";
	public static final String STATS_TOTAL_TX = "StatsTotalTx";
	public static final String STATS_TOTAL_RX = "StatsTotalRx";
	public static final String STATS_SESSION_TX = "StatsSessionTx";
	public static final String STATS_SESSION_RX = "StatsSessionRx";
	public static final String STATS_RATE_TX = "StatsRateTx";
	public static final String STATS_RATE_RX = "StatsRateRx";
	public static final String STATS_APP_BASE = "StatsAppBase";
	public static final String STATS_APP_TOTAL = "StatsAppTotal";
	public static final String THEME = "Theme";

	public static final int MAX_PROFILES = 13;

	/* One routing rule: "value (domain / ip / cidr) -> proxy or direct". */
	public static class Rule {
		public static final int TYPE_DOMAIN = 0;
		public static final int TYPE_IP = 1;
		public static final int TYPE_CIDR = 2;

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

	public Preferences(Context context) {
		prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
		migrate();
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
		if (prefs.contains(SOCKS_ADDR))
		  editor.putString(key(0, SOCKS_ADDR), prefs.getString(SOCKS_ADDR, ""));
		if (prefs.contains(SOCKS_UDP_ADDR))
		  editor.putString(key(0, SOCKS_UDP_ADDR), prefs.getString(SOCKS_UDP_ADDR, ""));
		if (prefs.contains(SOCKS_PORT))
		  editor.putInt(key(0, SOCKS_PORT), prefs.getInt(SOCKS_PORT, 1080));
		if (prefs.contains(SOCKS_USER))
		  editor.putString(key(0, SOCKS_USER), prefs.getString(SOCKS_USER, ""));
		if (prefs.contains(SOCKS_PASS))
		  editor.putString(key(0, SOCKS_PASS), prefs.getString(SOCKS_PASS, ""));
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
		editor.remove(SOCKS_ADDR);
		editor.remove(SOCKS_UDP_ADDR);
		editor.remove(SOCKS_PORT);
		editor.remove(SOCKS_USER);
		editor.remove(SOCKS_PASS);
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
		editor.putString(key(dst, SOCKS_ADDR), prefs.getString(key(src, SOCKS_ADDR), "127.0.0.1"));
		editor.putString(key(dst, SOCKS_UDP_ADDR), prefs.getString(key(src, SOCKS_UDP_ADDR), ""));
		editor.putInt(key(dst, SOCKS_PORT), prefs.getInt(key(src, SOCKS_PORT), 1080));
		editor.putString(key(dst, SOCKS_USER), prefs.getString(key(src, SOCKS_USER), ""));
		editor.putString(key(dst, SOCKS_PASS), prefs.getString(key(src, SOCKS_PASS), ""));
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
		editor.putString(key(dst, SUB_SELECTED), prefs.getString(key(src, SUB_SELECTED), ""));
	}

	private void removeProfile(SharedPreferences.Editor editor, int profile) {
		editor.remove(key(profile, NAME));
		editor.remove(key(profile, SOCKS_ADDR));
		editor.remove(key(profile, SOCKS_UDP_ADDR));
		editor.remove(key(profile, SOCKS_PORT));
		editor.remove(key(profile, SOCKS_USER));
		editor.remove(key(profile, SOCKS_PASS));
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
		editor.remove(key(profile, SUB_SELECTED));
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

	public String getSocksAddress() {
		return prefs.getString(key(SOCKS_ADDR), "127.0.0.1");
	}

	public void setSocksAddress(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_ADDR), addr);
		editor.commit();
	}

	public String getSocksUdpAddress() {
		return prefs.getString(key(SOCKS_UDP_ADDR), "");
	}

	public void setSocksUdpAddress(String addr) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_UDP_ADDR), addr);
		editor.commit();
	}

	public int getSocksPort() {
		return prefs.getInt(key(SOCKS_PORT), 1080);
	}

	public void setSocksPort(int port) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putInt(key(SOCKS_PORT), port);
		editor.commit();
	}

	public String getSocksUsername() {
		return prefs.getString(key(SOCKS_USER), "");
	}

	public void setSocksUsername(String user) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_USER), user);
		editor.commit();
	}

	public String getSocksPassword() {
		return prefs.getString(key(SOCKS_PASS), "");
	}

	public void setSocksPassword(String pass) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SOCKS_PASS), pass);
		editor.commit();
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

	public String getSubNodes() {
		return prefs.getString(key(SUB_NODES), "");
	}

	public void setSubNodes(String json) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(key(SUB_NODES), json);
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

	public String getMappedDns() {
		return "198.18.0.2";
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

	public boolean getEnable() {
		return prefs.getBoolean(ENABLE, false);
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

	public int getTunnelMtu() {
		return 8500;
	}

	public String getTunnelIpv4Address() {
		return "198.18.0.1";
	}

	public int getTunnelIpv4Prefix() {
		return 32;
	}

	public String getTunnelIpv6Address() {
		return "fc00::1";
	}

	public int getTunnelIpv6Prefix() {
		return 128;
	}

	public int getTaskStackSize() {
		return 81920;
	}
}
