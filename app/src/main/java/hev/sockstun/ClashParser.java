/*
 ============================================================================
 Name        : ClashParser.java
 Description : Minimal clash.yml parser: extracts SOCKS5 nodes from the
               top-level "proxies:" list. No external YAML dependency.
 ============================================================================
 */

package hev.sockstun;

import android.util.Base64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClashParser {

	/* Parse raw subscription text and return only SOCKS5-capable nodes. */
	public static List<ClashNode> parse(String raw) {
		List<ClashNode> result = new ArrayList<ClashNode>();
		if (raw == null || raw.isEmpty())
		  return result;

		String text = raw;
		/* Some providers ship the YAML as a base64 blob. */
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
				addIfSocks(m, result);
			} else {
				i++;
			}
		}
		return result;
	}

	private static void addIfSocks(Map<String, String> m, List<ClashNode> out) {
		String type = m.get("type");
		if (type == null)
		  return;
		if (!type.equalsIgnoreCase("socks5") && !type.equalsIgnoreCase("socks"))
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
