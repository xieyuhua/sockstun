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

package hev.sockstun;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MihomoConfig {
	/* Write the final clash config into the app's files dir and return the
	   file. Throws if no subscription has been fetched yet. */
	public static File build(Context context, Preferences prefs) throws IOException {
		String raw = prefs.getSubRaw();
		if (raw == null || raw.trim().isEmpty())
		  throw new IOException("no subscription configured");

		StringBuilder cfg = new StringBuilder(raw);
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

		File out = new File(context.getFilesDir(), "mihomo.yaml");
		FileOutputStream fos = new FileOutputStream(out, false);
		fos.write(cfg.toString().getBytes("UTF-8"));
		fos.close();
		return out;
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
