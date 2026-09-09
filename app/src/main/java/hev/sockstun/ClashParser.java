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
		return collect(raw);
	}

	private static List<ClashNode> collect(String raw) {
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
			if (indent <= 0 && !line.trim().startsWith("- "))
			  break; /* reached the next top-level key */
			String trimmed = line.trim();
			if (trimmed.startsWith("- ")) {
				int baseIndent = indent;
				Map<String, String> m = new HashMap<String, String>();
				String first = trimmed.substring(2);
				if (first.trim().startsWith("{"))
				  parseFlowMap(m, first);
				else
				  putKV(m, first);
				i++;
				while (i < lines.length) {
					String l2 = lines[i];
					if (l2.trim().isEmpty()) {
						i++;
						continue;
					}
					if (leadingSpaces(l2) <= baseIndent)
					  break;
					putKV(m, l2.trim());
					i++;
				}
				addIfMatch(m, result);
			} else {
				i++;
			}
		}
		return result;
	}

	private static void addIfMatch(Map<String, String> m, List<ClashNode> out) {
		String type = m.get("type");
		if (type == null)
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

	/* A proxy may be written as a single-line YAML flow mapping, e.g.
	   "- {name: x, server: y, port: 1234, type: vmess, ...}".  Split it into
	   its key: value pairs so addIfMatch() can see type / server / port. */
	private static void parseFlowMap(Map<String, String> m, String s) {
		String inner = s.trim();
		if (inner.startsWith("{"))
		  inner = inner.substring(1);
		if (inner.endsWith("}"))
		  inner = inner.substring(0, inner.length() - 1);
		for (String part : splitTopLevel(inner))
		  putKV(m, part.trim());
	}

	/* Split on commas that are not inside quotes. */
	private static List<String> splitTopLevel(String s) {
		List<String> out = new ArrayList<String>();
		StringBuilder cur = new StringBuilder();
		boolean inQuote = false;
		char quote = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inQuote) {
				cur.append(c);
				if (c == quote)
				  inQuote = false;
				continue;
			}
			/* 34 = double quote, 39 = single quote */
			if (c == 34 || c == 39) {
				inQuote = true;
				quote = c;
				cur.append(c);
				continue;
			}
			if (c == ',') {
				out.add(cur.toString());
				cur.setLength(0);
				continue;
			}
			cur.append(c);
		}
		if (cur.length() > 0)
		  out.add(cur.toString());
		return out;
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

	/* Name of the proxy-group a picked node must be selected in.
	   Subscriptions name it freely ("GLOBAL", "节点选择", ...), so prefer a
	   group called GLOBAL, else the first switchable group (select /
	   url-test / fallback / load-balance), else simply the first group. */
	public static String parseSelectorGroup(String raw) {
		if (raw == null || raw.isEmpty())
		  return "";
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
			if (t.equals("proxy-groups:") || t.startsWith("proxy-groups:")) {
				start = i;
				break;
			}
		}
		if (start < 0)
		  return "";

		String firstSelect = "";
		String firstAny = "";
		int i = start + 1;
		while (i < lines.length) {
			String line = lines[i];
			if (line.trim().isEmpty()) {
				i++;
				continue;
			}
			String trimmed = line.trim();
			int indent = leadingSpaces(line);
			if (indent <= 0 && !trimmed.startsWith("- "))
			  break;
			if (trimmed.startsWith("- ")) {
				int baseIndent = indent;
				Map<String, String> m = new HashMap<String, String>();
				String first = trimmed.substring(2);
				if (first.trim().startsWith("{"))
				  parseFlowMap(m, first);
				else
				  putKV(m, first);
				i++;
				while (i < lines.length) {
					String l2 = lines[i];
					if (l2.trim().isEmpty()) {
						i++;
						continue;
					}
					if (leadingSpaces(l2) <= baseIndent)
					  break;
					putKV(m, l2.trim());
					i++;
				}
				String name = m.get("name");
				String type = m.get("type");
				if (name != null && !name.isEmpty()) {
					if ("GLOBAL".equalsIgnoreCase(name))
					  return name;
					if (firstSelect.isEmpty() && type != null &&
						("select".equalsIgnoreCase(type) ||
						 "url-test".equalsIgnoreCase(type) ||
						 "fallback".equalsIgnoreCase(type) ||
						 "load-balance".equalsIgnoreCase(type)))
					  firstSelect = name;
					if (firstAny.isEmpty())
					  firstAny = name;
				}
			} else {
				i++;
			}
		}
		return !firstSelect.isEmpty() ? firstSelect : firstAny;
	}
}
