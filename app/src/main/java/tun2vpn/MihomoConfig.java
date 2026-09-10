/*
 ============================================================================
 Name        : MihomoConfig.java
 Description : Build a mihomo (clash.meta) configuration file from the stored
               raw subscription plus the TUN/DNS/log sections mihomo needs on
               Android. The raw subscription already carries proxies /
               proxy-groups / rules / rule-providers, so we keep it intact and
               only append what is required for mihomo to own the VPN tunnel.
 ============================================================================
 */

package tun2vpn;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MihomoConfig {
	/* Write the final clash config into the app's files dir and return the
	   file. Throws if no subscription has been fetched yet. */
	public static File build(Context context, Preferences prefs) throws IOException {
		/* An enabled SOCKS5 server wins; otherwise the subscription is used. */
		SocksServer server = prefs.getActiveSocksServer();
		boolean useSub = (server == null);

		String raw = prefs.getSubRaw();
		if (useSub && (raw == null || raw.trim().isEmpty()))
		  throw new IOException("no upstream: add a subscription or enable a SOCKS5 server");

		StringBuilder cfg = new StringBuilder(useSub ? raw : manualSocksConfig(server));
		if (!cfg.toString().endsWith("\n"))
		  cfg.append('\n');

		/* DNS: subscriptions usually ship a dns: block; only add our own
		   fake-ip resolver when one is missing. */
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
		   callback). We only enable it and let mihomo auto-route traffic.
		   Guarded in case the subscription already declares a tun: block. */
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

	/* Minimal clash config that uses one manually configured SOCKS5 server as
	   the only upstream. */
	private static String manualSocksConfig(SocksServer s) throws IOException {
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
		sb.append("  - {name: \"PROXY\", type: select, proxies: [\"socks5\"]}\n");
		sb.append("rules:\n");
		sb.append("  - MATCH,PROXY\n");
		return sb.toString();
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
