/*
 ============================================================================
 Name        : MihomoConfig.java
 Description : Build a mihomo (clash.meta) configuration file from what the app
               owns, plus the TUN/DNS/log sections mihomo needs on Android.

               Routing is owned by the app, not by the subscriptions: every
               subscription contributes proxy nodes only, the nodes are merged
               into one pool, and a single self-built group points at that pool
               (url-test = auto-pick the fastest, select = use exactly the node
               the user chose). Subscriptions' own proxy-groups and rules are
               deliberately ignored - they cannot be merged across several
               sources anyway - and the app's "routing rules" page is what
               decides proxy vs direct.
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MihomoConfig {
	/* The proxy-group we build ourselves. The app's rules and the manual node
	   pick both resolve through this name. */
	public static final String GROUP = "tunvpn";
	/* The url-test group that spans every node (the "fastest anywhere" choice
	   in auto mode). */
	public static final String GLOBAL_GROUP = GROUP + "-global";

	/* Name of the per-country url-test group for an ISO-3166 alpha-2 code. */
	public static String countryGroup(String cc) {
		return GROUP + "-" + cc;
	}
	/* mihomo's RESTful API. Bound to loopback only: it exposes every
	   connection's target and must never be reachable from the LAN, which is
	   also why it ignores the allow-lan setting. */
	public static final int API_PORT = 9090;
	/* Bail-out threshold for the auto re-test interval. */
	private static final int MIN_INTERVAL = 30;
	private static final int MAX_INTERVAL = 86400;

	/* Write the final clash config into the app's files dir and return the
	   file. Throws if no upstream has been configured yet. */
	public static File build(Context context, Preferences prefs) throws IOException {
		/* An enabled SOCKS5 server wins; otherwise every subscription is
		   merged into one node pool. */
		SocksServer server = prefs.getActiveSocksServer();
		StringBuilder cfg = new StringBuilder(
			server != null ? manualSocksConfig(prefs, server) : mergedConfig(prefs));
		if (!cfg.toString().endsWith("\n"))
		  cfg.append('\n');

		/* DNS: without a subscription to inherit one from, set up the fake-ip
		   resolver ourselves. */
		if (!sectionExists(cfg, "dns:"))
			cfg.append("dns:\n")
				.append("  enable: true\n")
				.append("  enhanced-mode: fake-ip\n")
				.append("  fake-ip-range: 198.18.0.1/16\n")
				/* No DoH fallback: 1.1.1.1 is unreachable from a lot of
				   networks, and an unreachable fallback makes every lookup
				   wait for its timeout. With fake-ip the real hostname is
				   handed to the proxy anyway, so foreign names do not need a
				   local resolver at all. */
				.append("  nameserver:\n")
				.append("    - 223.5.5.5\n")
				.append("    - 119.29.29.29\n");

		if (!sectionExists(cfg, "log:"))
			cfg.append("log:\n  level: info\n  report: false\n");

		/* TUN is driven by Clash.startTUN (it supplies the fd + protect
		   callback). We only enable it and let mihomo auto-route traffic. */
		if (!sectionExists(cfg, "tun:"))
			cfg.append("tun:\n")
				.append("  enable: true\n")
				.append("  auto-route: true\n")
				.append("  auto-detect-interface: true\n")
				/* Without the hijack, DNS lookups skip the core's fake-ip
				   resolver and hit the system resolver directly. */
				.append("  dns-hijack:\n")
				.append("    - any:53\n");

		/* Loopback-only API used to tell proxied traffic apart from direct
		   traffic (the point of the home screen's counters). */
		if (!sectionExists(cfg, "external-controller:"))
			cfg.append("external-controller: 127.0.0.1:").append(API_PORT).append('\n');

		/* The home screen asks an echo service for the public IP through the
		   core's local HTTP port, and the "allow LAN" setting exposes it to the
		   network, so the app owns this port outright. The config is built from
		   scratch (subscription port settings are not carried over), hence the
		   value is always written - never inherited, never omitted. */
		cfg.append("mixed-port: ").append(prefs.getProxyPort()).append('\n');
		/* allow-lan stays off unless asked for: an open proxy on a shared
		   network lets anyone on it use (and pay for) the tunnel. */
		if (prefs.getAllowLan()) {
			cfg.append("allow-lan: true\n")
				.append("bind-address: \"*\"\n");
		}

		/* The core loads <homeDir>/config.yaml, so the file name matters —
		   naming it anything else makes quickSetup fail with
		   "stat config.yaml: no such file or directory". */
		File out = new File(context.getFilesDir(), "config.yaml");
		try (FileOutputStream fos = new FileOutputStream(out, false)) {
			fos.write(cfg.toString().getBytes("UTF-8"));
		}
		return out;
	}

	/* One-line summary of what the config contains, for the log: an empty
	   merged pool is the kind of thing that stays invisible until every
	   connection times out. */
	public static String describe(Preferences prefs) {
		int nodes = 0;
		for (Subscription sub : prefs.getSubscriptions())
		  if (sub.enabled)
			nodes += ClashParser.extractProxies(prefs.getSubRaw(sub.id)).size();
		/* The catch-all target is the single most useful thing to log: if it
		   says DIRECT, everything is bypassing the proxy no matter how healthy
		   the node pool looks. */
		return nodes + " node(s), " + prefs.getRules().size() + " rule(s), "
			+ strategyLabel(prefs) + ", "
			+ (prefs.getAutoSelect()
				? ("auto url-test " + prefs.getAutoSelectInterval() + "s" + autoCountryLabel(prefs))
				: "manual select");
	}

	/* One-line description of the routing strategy for the log. */
	private static String strategyLabel(Preferences prefs) {
		String s = prefs.getRulesStrategy();
		if (Preferences.RULES_STRATEGY_GLOBAL.equals(s))
		  return "strategy=global-proxy";
		if (Preferences.RULES_STRATEGY_DIRECT.equals(s))
		  return "strategy=global-direct";
		return "strategy=rules(default="
			+ (prefs.getRulesDefaultProxy() ? GROUP : "DIRECT") + ")";
	}

	/* One-line description of which pool auto mode picks the fastest node from.
	   The pool is already narrowed to the chosen country by mergedConfig. */
	private static String autoCountryLabel(Preferences prefs) {
		String c = prefs.getSubCountryFilter();
		if (c == null || c.isEmpty())
		  return " (global)";
		return " (country=" + c + ")";
	}

	/* Merge every subscription into one node pool and point a single
	   self-built group at it. */
	private static String mergedConfig(Preferences prefs) throws IOException {
		List<String> taken = new ArrayList<String>();
		StringBuilder proxies = new StringBuilder();

		/* The subscribe page's country chip narrows the whole tunnel to one
		   country's proxies, so "auto fastest" and a manual pick both stay
		   inside that country. An empty filter means "all countries". */
		String cc = prefs.getSubCountryFilter();
		boolean ccSet = (cc != null && !cc.isEmpty());

		for (Subscription sub : prefs.getSubscriptions()) {
			/* A disabled subscription is kept but not merged into the pool. */
			if (!sub.enabled)
			  continue;
			List<ClashParser.ProxyDef> list =
				ClashParser.extractProxies(prefs.getSubRaw(sub.id));
			for (ClashParser.ProxyDef p : list) {
				if (ccSet) {
					String pc = prefs.getServerCountry(p.server);
					boolean match = cc.equals(pc);
					/* "Unknown" also covers nodes whose country has not been
					   resolved yet (empty server->country map). */
					if (!match && GeoIp.UNKNOWN.equals(cc)
							&& (pc == null || pc.isEmpty()))
					  match = true;
					if (!match)
					  continue;
				}
				String name = uniqueName(taken, p.name);
				taken.add(name);
				proxies.append(name.equals(p.name) ? p.text : renameProxy(p.text, p.name, name))
					.append('\n');
			}
		}
		if (taken.isEmpty())
		  throw new IOException(ccSet
			  ? ("no upstream in country " + cc)
			  : "no upstream: add a subscription or enable a SOCKS5 server");

		/* Group off what the proxies: section *really* contains, not off the
		   names we intended. A de-dup rename or a quoting difference can leave
		   a name that was never emitted, and mihomo aborts the entire config
		   load when a group references a missing member
		   ("proxy group[0] ... not found"). Parsing our own output back makes
		   the two agree by construction - a node we cannot re-read is simply
		   left out of the groups instead of poisoning the whole config. */
		String proxiesText = "proxies:\n" + proxies;
		List<ClashParser.ProxyDef> proxys = ClashParser.extractProxies(proxiesText);
		if (proxys.isEmpty())
		  throw new IOException("no usable proxy in the merged pool");

		/* Block style rather than a flow mapping: with a few hundred nodes the
		   single line would run into tens of kilobytes, and a parse failure
		   there silently leaves MATCH pointing at a group that does not
		   exist - which shows up as "connected but nothing goes through the
		   proxy". One node per line cannot blow up that way. */
		StringBuilder sb = new StringBuilder(proxiesText);
		sb.append("proxy-groups:\n");
		if (prefs.getAutoSelect())
		  appendAutoGroups(sb, proxys, prefs);
		else {
			String sel = prefs.getSubSelected();
			sb.append("  - name: \"").append(GROUP).append("\"\n");
			sb.append("    type: select\n");
			sb.append("    proxies:\n");
			/* mihomo defaults a select group to its FIRST member, and the app's
			   own switch is applied afterwards through the control API - which
			   is exactly the step that fails when 9090 is unreachable. Listing
			   the picked node first makes the choice hold by itself, instead of
			   silently falling back to whatever node happens to be first. */
			if (sel != null && !sel.isEmpty()) {
				for (ClashParser.ProxyDef p : proxys) {
					if (sel.equals(p.name)) {
						sb.append("      - \"").append(escapeYaml(p.name)).append("\"\n");
						break;
					}
				}
			}
			for (ClashParser.ProxyDef p : proxys) {
				if (sel != null && sel.equals(p.name))
				  continue;
				sb.append("      - \"").append(escapeYaml(p.name)).append("\"\n");
			}
		}

		sb.append("rules:\n");
		appendRules(sb, prefs);
		return sb.toString();
	}

	/* Auto mode: one url-test group per country (fastest node *within* that
	   country) plus a global url-test group (fastest anywhere), all gathered
	   under a top-level select group. The default sub-group is:
	     - "AUTO"  -> the country whose nodes have the lowest measured latency
	     - a code  -> that country's url-test group
	     - "GLOBAL"/empty -> the global url-test group (fastest anywhere). */
	private static void appendAutoGroups(StringBuilder sb,
			List<ClashParser.ProxyDef> proxys, Preferences prefs) {
		/* Country code -> member node names, preserving first-seen order. */
		java.util.LinkedHashMap<String, List<String>> byCountry =
			new java.util.LinkedHashMap<String, List<String>>();
		List<String> global = new ArrayList<String>();
		for (ClashParser.ProxyDef p : proxys) {
			String cc = prefs.getServerCountry(p.server);
			if (cc == null || cc.isEmpty())
			  cc = GeoIp.UNKNOWN;
			List<String> list = byCountry.get(cc);
			if (list == null) {
				list = new ArrayList<String>();
				byCountry.put(cc, list);
			}
			list.add(p.name);
			global.add(p.name);
		}

		/* The country pool is already narrowed by the subscribe page's filter
		   (MihomoConfig.mergedConfig), so "auto fastest" simply means the
		   global url-test group inside that pool. */
		String defaultSub = GLOBAL_GROUP;

		/* mihomo resolves a proxy-group's members in definition order: a group
		   may only reference proxies/groups declared BEFORE it. The per-country
		   url-test groups (and the global one) therefore have to be emitted
		   first; the top-level select group that points at them comes last.
		   Getting this order wrong makes quickSetup fail with
		   "proxy group[0] tunvpn: proxy '...' not found", which also takes the
		   external-controller down - the symptoms are an unreachable node
		   picker, an empty connection list and zero proxy counters. */
		for (java.util.Map.Entry<String, List<String>> e : byCountry.entrySet())
		  appendUrlTestGroup(sb, countryGroup(e.getKey()), e.getValue(), prefs);
		appendUrlTestGroup(sb, GLOBAL_GROUP, global, prefs);

		/* The top select group: mihomo defaults it to its FIRST member, so the
		   chosen sub-group is listed first, then the remaining country groups,
		   then the global group, then a direct escape hatch. */
		sb.append("  - name: \"").append(GROUP).append("\"\n");
		sb.append("    type: select\n");
		sb.append("    proxies:\n");
		sb.append("      - \"").append(escapeYaml(defaultSub)).append("\"\n");
		for (String cc : byCountry.keySet()) {
			String g = countryGroup(cc);
			if (g.equals(defaultSub))
			  continue;
			sb.append("      - \"").append(escapeYaml(g)).append("\"\n");
		}
		if (!GLOBAL_GROUP.equals(defaultSub))
		  sb.append("      - \"").append(escapeYaml(GLOBAL_GROUP)).append("\"\n");
		sb.append("      - DIRECT\n");
	}

	/* When auto-best is selected, return the ISO code of the country whose nodes
	   have the lowest measured latency (from the app's earlier TCP tests, cached
	   per subscription). Null when no usable latency exists, so the caller falls
	   back to the global group. UNKNOWN nodes are skipped - we cannot optimise a
	   country we could not identify. */
	private static String bestCountryForAuto(Preferences prefs,
			java.util.LinkedHashMap<String, List<String>> byCountry,
			List<ClashParser.ProxyDef> proxys) {
		/* Proxy name -> server, then server -> best latency seen in the caches. */
		java.util.HashMap<String, String> nameToServer = new java.util.HashMap<String, String>();
		for (ClashParser.ProxyDef p : proxys)
		  nameToServer.put(p.name, p.server);

		java.util.HashMap<String, Long> serverLat = new java.util.HashMap<String, Long>();
		for (Subscription sub : prefs.getSubscriptions()) {
			if (!sub.enabled)
			  continue;
			for (ClashNode n : ClashNode.decode(prefs.getSubNodes(sub.id))) {
				if (n.latency >= 0) {
					Long prev = serverLat.get(n.server);
					if (prev == null || n.latency < prev)
					  serverLat.put(n.server, n.latency);
				}
			}
		}

		String best = null;
		long bestLat = Long.MAX_VALUE;
		for (java.util.Map.Entry<String, List<String>> e : byCountry.entrySet()) {
			String cc = e.getKey();
			if (GeoIp.UNKNOWN.equals(cc))
			  continue;
			long min = Long.MAX_VALUE;
			for (String name : e.getValue()) {
				String srv = nameToServer.get(name);
				Long lat = srv == null ? null : serverLat.get(srv);
				if (lat != null && lat < min)
				  min = lat;
			}
			if (min < bestLat) {
				bestLat = min;
				best = cc;
			}
		}
		return best;
	}

	/* A url-test group: mihomo measures every member against the test URL and
	   routes through the lowest-latency one, re-checking on `interval`. */
	private static void appendUrlTestGroup(StringBuilder sb, String name,
			List<String> members, Preferences prefs) {
		sb.append("  - name: \"").append(escapeYaml(name)).append("\"\n");
		sb.append("    type: url-test\n");
		sb.append("    url: \"").append(escapeYaml(prefs.getAutoTestUrl())).append("\"\n");
		sb.append("    interval: ").append(clampInterval(prefs.getAutoSelectInterval()))
			.append('\n')
			.append("    tolerance: 50\n");
		sb.append("    proxies:\n");
		for (String m : members)
		  sb.append("      - \"").append(escapeYaml(m)).append("\"\n");
	}

	/* Minimal upstream for a manually configured SOCKS5 server. The rules page
	   still applies, so manual mode routes the same way as subscriptions do. */
	private static String manualSocksConfig(Preferences prefs, SocksServer s) throws IOException {
		String addr = s.addr == null ? "" : s.addr.trim();
		if (addr.isEmpty())
		  throw new IOException("SOCKS5 server address is empty");

		StringBuilder sb = new StringBuilder();
		sb.append("proxies:\n");
		sb.append("  - {name: \"socks5\", type: socks5, server: ").append(addr)
			.append(", port: ").append(s.port)
			.append(", udp: true");
		if (s.user != null && !s.user.isEmpty())
			sb.append(", username: \"").append(s.user).append("\"");
		if (s.pass != null && !s.pass.isEmpty())
			sb.append(", password: \"").append(s.pass).append("\"");
		sb.append("}\n");
		sb.append("proxy-groups:\n");
		sb.append("  - {name: \"").append(GROUP).append("\", type: select, proxies: [\"socks5\"]}\n");
		sb.append("rules:\n");
		appendRules(sb, prefs);
		return sb.toString();
	}

	/* The app's routing rules, then the catch-all switch. The strategy decides
	   whether the user's rules even run: the two global modes route everything
	   one way and skip the list. */
	private static void appendRules(StringBuilder sb, Preferences prefs) {
		String strategy = prefs.getRulesStrategy();
		if (Preferences.RULES_STRATEGY_GLOBAL.equals(strategy)) {
			/* Everything through the proxy; the rule list is ignored. */
			sb.append("  - MATCH,").append(GROUP).append('\n');
			return;
		}
		if (Preferences.RULES_STRATEGY_DIRECT.equals(strategy)) {
			/* Everything direct; the rule list is ignored. */
			sb.append("  - MATCH,DIRECT\n");
			return;
		}
		/* Rule mode: the user's rules first, then the catch-all (itself proxy or
		   direct per the switch below). */
		for (Preferences.Rule r : prefs.getRules()) {
			String line = clashRule(r);
			if (line != null)
			  sb.append("  - ").append(line).append('\n');
		}
		sb.append("  - MATCH,")
			.append(prefs.getRulesDefaultProxy() ? GROUP : "DIRECT").append('\n');
	}

	/* One app rule as a clash rule line, honouring the type the user actually
	   picked (an earlier build re-derived the type from the value and ignored
	   the choice). Returns null when the rule cannot be expressed. */
	private static String clashRule(Preferences.Rule r) {
		String target = r.proxy ? GROUP : "DIRECT";
		String value = r.value == null ? "" : r.value.trim();
		if (value.isEmpty())
		  return null;
		switch (r.type) {
			case Preferences.Rule.TYPE_KEYWORD:
				return "DOMAIN-KEYWORD," + value + "," + target;
			case Preferences.Rule.TYPE_GEOIP:
				/* value is an ISO-3166 alpha-2 code; GEOIP needs the core's
				   geoip database, which mihomo fetches on first use. */
				if (!value.matches("^[A-Za-z]{2}$"))
				  return null;
				return "GEOIP," + value.toUpperCase() + "," + target;
			case Preferences.Rule.TYPE_PROCESS:
				return "PROCESS-NAME," + value + "," + target;
			case Preferences.Rule.TYPE_CIDR:
				if (!value.contains("/"))
				  return null;
				/* IPv6 needs its own rule type, otherwise mihomo rejects it. */
				return (value.contains(":") ? "IP-CIDR6," : "IP-CIDR,") + value + "," + target;
			case Preferences.Rule.TYPE_IP:
				if (isIpv4(value))
				  return "IP-CIDR," + value + "/32," + target;
				if (value.contains(":"))
				  return "IP-CIDR6," + value + "/128," + target;
				return null;
			case Preferences.Rule.TYPE_DOMAIN_FULL:
				return "DOMAIN," + value + "," + target;
			case Preferences.Rule.TYPE_GEOSITE:
				return "GEOSITE," + value + "," + target;
			case Preferences.Rule.TYPE_PROCESS_PATH:
				return "PROCESS-PATH," + value + "," + target;
			case Preferences.Rule.TYPE_DST_PORT:
				return "DST-PORT," + value + "," + target;
			case Preferences.Rule.TYPE_SRC_PORT:
				return "SRC-PORT," + value + "," + target;
			case Preferences.Rule.TYPE_NETWORK:
				return "NETWORK," + value.toLowerCase() + "," + target;
			case Preferences.Rule.TYPE_DOMAIN:
			default:
				return "DOMAIN-SUFFIX," + value + "," + target;
		}
	}

	private static boolean isIpv4(String s) {
		String[] parts = s.split("\\.");
		if (parts.length != 4)
		  return false;
		for (String p : parts) {
			try {
				int v = Integer.parseInt(p);
				if (v < 0 || v > 255)
				  return false;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

	/* The merged pool has to be addressable by name, and two subscriptions may
	   well ship the same label, so make it unique. */
	private static String uniqueName(List<String> taken, String name) {
		if (!taken.contains(name))
		  return name;
		for (int i = 2; ; i++) {
			String candidate = name + " (" + i + ")";
			if (!taken.contains(candidate))
			  return candidate;
		}
	}

	/* Rewrite the name: value of one proxy block - only that key, so a server
	   address that happens to contain the same text is left alone. */
	private static String renameProxy(String text, String oldName, String newName) {
		try {
			Pattern p = Pattern.compile("(name\\s*:\\s*)[\"']?" + Pattern.quote(oldName)
				+ "[\"']?(?![\\w\\-])");
			Matcher m = p.matcher(text);
			if (m.find())
			  return m.replaceFirst("$1\"" + Matcher.quoteReplacement(newName) + "\"");
		} catch (Exception e) {
		}
		return text;
	}

	private static String escapeYaml(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/* A too-small interval hammers every node; a huge one never re-tests. */
	private static int clampInterval(int seconds) {
		if (seconds < MIN_INTERVAL)
		  return MIN_INTERVAL;
		if (seconds > MAX_INTERVAL)
		  return MAX_INTERVAL;
		return seconds;
	}

	/* True when "key:" appears as a top-level YAML key (column 0). */
	private static boolean sectionExists(StringBuilder sb, String key) {
		String s = sb.toString();
		int idx = 0;
		while ((idx = s.indexOf(key, idx)) >= 0) {
			int lineStart = s.lastIndexOf('\n', idx) + 1;
			if (idx - lineStart == 0)
			  return true;
			idx += key.length();
		}
		return false;
	}
}
