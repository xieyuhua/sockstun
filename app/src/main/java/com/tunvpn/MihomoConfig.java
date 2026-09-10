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
	/* url-test health-check target: a 204 endpoint that is cheap and does not
	   get intercepted in most networks. */
	private static final String TEST_URL = "http://www.gstatic.com/generate_204";
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
				.append("  nameserver:\n")
				.append("    - 223.5.5.5\n")
				.append("    - 119.29.29.29\n")
				.append("  fallback:\n")
				.append("    - https://1.1.1.1/dns-query\n");

		if (!sectionExists(cfg, "log:"))
			cfg.append("log:\n  level: info\n  report: false\n");

		/* TUN is driven by Clash.startTUN (it supplies the fd + protect
		   callback). We only enable it and let mihomo auto-route traffic. */
		if (!sectionExists(cfg, "tun:"))
			cfg.append("tun:\n")
				.append("  enable: true\n")
				.append("  auto-route: true\n")
				.append("  auto-detect-interface: true\n");

		/* The home screen asks an echo service for the public IP through the
		   core's local HTTP port, so make sure such a port is exposed. */
		if (!sectionExists(cfg, "port:") && !sectionExists(cfg, "mixed-port:"))
			cfg.append("mixed-port: 7890\n");

		/* The core loads <homeDir>/config.yaml, so the file name matters —
		   naming it anything else makes quickSetup fail with
		   "stat config.yaml: no such file or directory". */
		File out = new File(context.getFilesDir(), "config.yaml");
		FileOutputStream fos = new FileOutputStream(out, false);
		fos.write(cfg.toString().getBytes("UTF-8"));
		fos.close();
		return out;
	}

	/* Merge every subscription into one node pool and point a single
	   self-built group at it. */
	private static String mergedConfig(Preferences prefs) throws IOException {
		List<String> names = new ArrayList<String>();
		List<String> defs = new ArrayList<String>();

		for (Subscription sub : prefs.getSubscriptions()) {
			List<ClashParser.ProxyDef> proxies =
				ClashParser.extractProxies(prefs.getSubRaw(sub.id));
			for (ClashParser.ProxyDef p : proxies) {
				String name = uniqueName(names, p.name);
				defs.add(name.equals(p.name) ? p.text : renameProxy(p.text, p.name, name));
				names.add(name);
			}
		}
		if (names.isEmpty())
		  throw new IOException("no upstream: add a subscription or enable a SOCKS5 server");

		StringBuilder sb = new StringBuilder();
		sb.append("proxies:\n");
		for (String def : defs)
		  sb.append(def).append('\n');

		String pool = joinQuoted(names);
		sb.append("proxy-groups:\n");
		if (prefs.getAutoSelect()) {
			sb.append("  - {name: \"").append(GROUP).append("\", type: url-test, url: \"")
				.append(TEST_URL).append("\", interval: ")
				.append(clampInterval(prefs.getAutoSelectInterval()))
				.append(", tolerance: 50, proxies: [").append(pool).append("]}\n");
		} else {
			sb.append("  - {name: \"").append(GROUP).append("\", type: select, proxies: [")
				.append(pool).append("]}\n");
		}

		sb.append("rules:\n");
		appendRules(sb, prefs);
		return sb.toString();
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

	/* The app's routing rules, then the catch-all switch. */
	private static void appendRules(StringBuilder sb, Preferences prefs) {
		for (Preferences.Rule r : prefs.getRules())
		  sb.append("  - ").append(clashRule(r)).append('\n');
		sb.append("  - MATCH,")
			.append(prefs.getRulesDefaultProxy() ? GROUP : "DIRECT").append('\n');
	}

	/* One app rule as a clash rule line. A domain rule matches the suffix,
	   which is what people mean by "example.com"; IP and CIDR rules go
	   through IP-CIDR (mihomo may resolve a hostname to test them). */
	private static String clashRule(Preferences.Rule r) {
		String target = r.proxy ? GROUP : "DIRECT";
		String value = r.value == null ? "" : r.value.trim();
		if (r.type == Preferences.Rule.TYPE_DOMAIN)
		  return "DOMAIN-SUFFIX," + value + "," + target;
		if (r.type == Preferences.Rule.TYPE_IP)
		  return "IP-CIDR," + value + "/32," + target;
		return "IP-CIDR," + value + "," + target;
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

	private static String joinQuoted(List<String> names) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			if (i > 0)
			  sb.append(", ");
			sb.append('"').append(escapeYaml(names.get(i))).append('"');
		}
		return sb.toString();
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
