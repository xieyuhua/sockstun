/*
 ============================================================================
 Name        : ClashParser.java
 Description : Minimal clash.yml parser. Decodes base64 subscriptions and
               extracts proxy nodes. We take every proxy (vmess / trojan /
               ss / socks5 / vless / hysteria2 / tuic / ...) so the UI can
               list and latency-test the whole multi-protocol subscription;
               the embedded mihomo core does the actual protocol handling.
 ============================================================================
 */

package hev.sockstun;

import android.util.Base64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClashParser {

	/* Decode a subscription body to clash YAML. Providers may ship the YAML as
	   a base64 blob; return the decoded form, or the original if it already
	   looks like clash config. */
	public static String decodeRaw(String raw) {
		if (raw == null || raw.isEmpty())
		  return raw;
		if (containsProxies(raw))
		  return raw;
		String dec = tryBase64(raw);
		if (dec != null && containsProxies(dec))
		  return dec;
		return raw;
	}

	/* Every proxy node, regardless of protocol. */
	public static List<ClashNode> parseAll(String raw) {
		return collect(raw, false);
	}

	/* Backward-compatible: SOCKS5-capable nodes only. */
	public static List<ClashNode> parse(String raw) {
		return collect(raw, true);
	}

	private static List<ClashNode> collect(String raw, boolean socksOnly) {
		List<ClashNode> result = new ArrayList<ClashNode>();
		if (raw == null || raw.isEmpty())
		  return result;

		String text = raw;
		if (!containsProxies(text)) {
			String dec = tryBase64(text);
			if (dec != null && containsProxies(dec))
			  text = dec;
		}

		String[] lines = text.split("\\r?\\n");
		int start = -1;
		for (int i = 0; i < lines.length; i++) {
			if (leadingSpaces(lines[i]) != 0)
			  continue;
			String t = lines[i].trim();
			if (t.equals("proxies:") || t.startsWith("proxies:")) {
				start = i;
				break;
			}
		}
		if (start < 0)
		  return result;

		int i = start + 1;
		while (i < lines.length) {
			String line = lines[i];
			if (line.trim().isEmpty()) {
				i++;
				continue;
			}
			int indent = leadingSpaces(line);
			if (indent <= 0)
			  break; /* reached the next top-level key */
			String trimmed = line.trim();
			if (indent == 2 && trimmed.startsWith("- ")) {
				Map<String, String> m = new HashMap<String, String>();
				putKV(m, trimmed.substring(2));
				i++;
				while (i < lines.length) {
					String l2 = lines[i];
					if (l2.trim().isEmpty()) {
						i++;
						continue;
					}
					if (leadingSpaces(l2) <= 2)
					  break;
					putKV(m, l2.trim());
					i++;
				}
				addIfMatch(m, result, socksOnly);
			} else {
				i++;
			}
		}
		return result;
	}

	private static void addIfMatch(Map<String, String> m, List<ClashNode> out, boolean socksOnly) {
		String type = m.get("type");
		if (type == null)
		  return;
		if (socksOnly && !type.equalsIgnoreCase("socks5") && !type.equalsIgnoreCase("socks"))
		  return;
		String name = m.get("name");
		String server = m.get("server");
		String portStr = m.get("port");
		if (name == null || server == null || portStr == null)
		  return;
		int port;
		try {
			port = Integer.parseInt(portStr.trim());
		} catch (NumberFormatException e) {
			return;
		}
		out.add(new ClashNode(name, type, server, port,
			m.get("username") == null ? "" : m.get("username"),
			m.get("password") == null ? "" : m.get("password")));
	}

	private static void putKV(Map<String, String> m, String s) {
		int idx = s.indexOf(':');
		if (idx < 0)
		  return;
		String k = s.substring(0, idx).trim();
		String v = s.substring(idx + 1).trim();
		if (v.length() >= 2 &&
			((v.startsWith("\"") && v.endsWith("\"")) ||
			 (v.startsWith("'") && v.endsWith("'"))))
			v = v.substring(1, v.length() - 1);
		m.put(k, v);
	}

	private static int leadingSpaces(String line) {
		int n = 0;
		while (n < line.length() && line.charAt(n) == ' ')
		  n++;
		return n;
	}

	private static boolean containsProxies(String s) {
		return s != null && s.contains("proxies:");
	}

	private static String tryBase64(String s) {
		try {
			String t = s.trim();
			if (t.matches("[A-Za-z0-9+/=\\s]+") && t.length() > 20)
			  return new String(Base64.decode(t, Base64.DEFAULT));
		} catch (Exception e) {
		}
		return null;
	}
}
