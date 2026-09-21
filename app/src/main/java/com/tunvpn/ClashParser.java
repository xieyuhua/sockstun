/*
 ============================================================================
 文件名  : ClashParser.java
 说明    : 极简 clash.yml 解析器：解 base64 订阅、抽取代理节点。我们接收所有协议
           （vmess / trojan / ss / socks5 / vless / hysteria2 / tuic …），这样界面
           就能列出并给整份多协议订阅测速；真正的协议处理交给内嵌的 mihomo 内核。
 ============================================================================
*/

package com.tunvpn;

import android.util.Base64;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClashParser {

	/* 行分隔符，只编译一次：extractProxies() 会对**每个节点**重新切分它那段原始文本
	   （几百个节点、每次启动要生成多次配置），而 String.split() 每次调用都会重新编译
	   正则。 */
	private static final java.util.regex.Pattern LINE_BREAK =
		java.util.regex.Pattern.compile("\\r?\\n");

	/* 把订阅体解码成 clash YAML。服务商可能把 YAML 以 base64 形式下发；解出来就返回
	   解码结果，本来就像 clash 配置则原样返回。 */
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

	/* 全部代理节点，不区分协议。 */
	public static List<ClashNode> parseAll(String raw) {
		return collect(raw);
	}

	/* "proxies:" 下面的一条记录，保留其**原始 YAML 文本**：这样把它重新发射进合并后的
	   配置时，不会丢掉我们不认识的字段（uuid / ws-opts / sni / fingerprint …）。 */
	public static class ProxyDef {
		public String name;
		public String type;
		public String text;   /* the "- ..." block, indented by 2 spaces */
		public String server; /* proxy host, used to resolve its country */

		ProxyDef(String name, String type, String text, String server) {
			this.name = name;
			this.type = type;
			this.text = text;
			this.server = server == null ? "" : server;
		}
	}

	/* 一份订阅里的全部代理定义，按文件顺序。 */
	public static List<ProxyDef> extractProxies(String raw) {
		List<ProxyDef> out = new ArrayList<ProxyDef>();
		if (raw == null || raw.isEmpty())
		  return out;
		String text = decodeRaw(raw);
		if (text == null || !containsProxies(text))
		  return out;

		String[] lines = LINE_BREAK.split(text, -1);
		int start = -1;
		for (int i = 0; i < lines.length; i++) {
			if (leadingSpaces(lines[i]) != 0)
			  continue;
			if (lines[i].trim().startsWith("proxies:")) {
				start = i;
				break;
			}
		}
		if (start < 0)
		  return out;

		int i = start + 1;
		while (i < lines.length) {
			String line = lines[i];
			if (line.trim().isEmpty()) {
				i++;
				continue;
			}
			int indent = leadingSpaces(line);
			if (indent <= 0 && !line.trim().startsWith("- "))
			  break; /* 遇到下一个顶层 key，说明 proxies 段结束了 */
			if (!line.trim().startsWith("- ")) {
				i++;
				continue;
			}
			int baseIndent = indent;
			StringBuilder block = new StringBuilder("  ").append(line.trim());
			i++;
			while (i < lines.length) {
				String l2 = lines[i];
				if (l2.trim().isEmpty()) {
					i++;
					continue;
				}
				int inner = leadingSpaces(l2) - baseIndent;
				if (inner <= 0)
				  break;
				/* 按相对 "- " 的缩进重排，而不是相对第 0 列。把每一层续行都压成单层会
				   毁掉嵌套映射（ws-opts / headers / reality-opts …），内核会因此丢掉
				   整个节点。 */
				block.append('\n').append("  ").append(spaces(inner)).append(l2.trim());
				i++;
			}
			/* 用与 parseAll() 相同的键值解析工具再读一遍这段块，保证这里的
			   name/type 与节点列表里的完全一致。 */
			Map<String, String> m = new HashMap<String, String>();
			String[] blockLines = LINE_BREAK.split(block.toString());
			String head = blockLines[0].trim().substring(2);
			if (head.trim().startsWith("{"))
			  parseFlowMap(m, head);
			else
			  putKV(m, head);
			for (int k = 1; k < blockLines.length; k++)
			  putKV(m, blockLines[k].trim());
			String name = m.get("name");
			String type = m.get("type");
			if (name != null && !name.isEmpty())
			  out.add(new ProxyDef(name, type == null ? "" : type, block.toString(),
				m.get("server")));
		}
		return out;
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

		String[] lines = LINE_BREAK.split(text);
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
			  break; /* 遇到下一个顶层 key */
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

	/* 节点也可能写成单行 YAML 流式映射，例如
	   "- {name: x, server: y, port: 1234, type: vmess, ...}"。这里把它拆成
	   键值对，好让 addIfMatch() 能看到 type / server / port。 */
	private static void parseFlowMap(Map<String, String> m, String s) {
		String inner = s.trim();
		if (inner.startsWith("{"))
		  inner = inner.substring(1);
		if (inner.endsWith("}"))
		  inner = inner.substring(0, inner.length() - 1);
		for (String part : splitTopLevel(inner))
		  putKV(m, part.trim());
	}

	/* 只按"不在引号内"的逗号切分。 */
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
			/* 34 = 双引号，39 = 单引号 */
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

	private static String spaces(int n) {
		if (n <= 0)
		  return "";
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++)
		  sb.append(' ');
		return sb.toString();
	}

	private static boolean containsProxies(String s) {
		return s != null && s.contains("proxies:");
	}

	/* 判断内容是否"像 base64"（只含字母/数字/+/= 与空白）。用逐字符扫描替代
	   String.matches()：后者**每次调用**都要编译正则，而订阅体有几百 KB。扫描遇到
	   第一个 ':' 或其它 YAML 字符就返回 false，所以真正的 clash.yml 几乎立刻被排除。 */
	private static boolean isBase64ish(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
				|| (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='
				|| c == '\n' || c == '\r' || c == '\t' || c == ' ';
			if (!ok)
			  return false;
		}
		return true;
	}

	private static String tryBase64(String s) {
		try {
			String t = s.trim();
			if (t.length() > 20 && isBase64ish(t))
			  return new String(Base64.decode(t, Base64.DEFAULT));
		} catch (Exception e) {
		}
		return null;
	}

}
