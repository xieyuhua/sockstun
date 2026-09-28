/*
 ============================================================================
 文件名  : NodeFormat.java
 说明    : 单条 clash 节点在「clash flow map」与「JSON」两种写法之间互转。
           服务器编辑页的"节点配置"用 clash flow map（如 `- {name:..., type:...}`），
           这里让用户既能一键复制成 JSON、也能复制成 clash，粘贴时两种格式都能吃
           （JSON 自动转成 clash）。
 ============================================================================
*/

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodeFormat {

	/* 文本是否"看起来像 JSON"（去空白后以 { 或 [ 开头）。 */
	public static boolean looksLikeJson(String s) {
		if (s == null)
		  return false;
		String t = s.trim();
		return !t.isEmpty() && (t.startsWith("{") || t.startsWith("["));
	}

	/* 把任意节点文本（JSON 或 clash）归一成 clash 单行 flow map（带前导 "- "）。
	   解析失败返回 null。 */
	public static String toClash(String text) {
		Map<String, Object> m = toMap(text);
		if (m == null)
		  return null;
		return "- " + mapToFlow(m);
	}

	/* 把任意节点文本归一成 JSON 对象（单行）。解析失败返回 null。 */
	public static String toJson(String text) {
		Map<String, Object> m = toMap(text);
		if (m == null)
		  return null;
		return mapToJson(m).toString();
	}

	/* 把任意节点文本解析成单节点 Map（兼容 JSON / clash flow map / 整段 proxies:）。 */
	public static Map<String, Object> toMap(String text) {
		String t = text == null ? "" : text.trim();
		if (t.isEmpty())
		  return null;

		/* 整段 "proxies:" 包裹：取第一段节点块。 */
		int idx = t.indexOf("proxies:");
		if (idx >= 0)
		  t = t.substring(idx + "proxies:".length()).trim();
		/* 去掉开头的列表短横线。**只对 flow**（- { / - [）这么做：块风格要按 "- "
		   的列宽折算续行缩进（见 parseBlockNode），这里先剥掉会破坏缩进关系。 */
		if (t.startsWith("- {") || t.startsWith("- ["))
		  t = t.substring(2).trim();

		/* 我们的存储格式是 clash flow map（未加引号的键），但也要兼容用户粘贴的 JSON。
		   flow map 解析器本就能吃带引号的 JSON 写法，所以优先用它；解析出来的
		   `{"proxies":[...]}` 也顺手展开成单条节点。 */
		if (t.startsWith("{")) {
			try {
				Map<String, Object> fm = parseFlowMap(t);
				if (fm != null && !fm.isEmpty()) {
					Object p = fm.get("proxies");
					if (p instanceof List && !((List<?>) p).isEmpty())
					  return (Map<String, Object>) ((List<?>) p).get(0);
					return fm;
				}
			} catch (Exception ignore) {
			}
			return null;
		}
		if (t.startsWith("[")) {
			try {
				List<Object> seq = parseFlowSeq(t);
				if (seq != null && !seq.isEmpty())
				  return (Map<String, Object>) seq.get(0);
			} catch (Exception ignore) {
			}
			return null;
		}
		/* 块风格（多行、非 flow）的 clash 节点："- name: x" + 缩进续行。
		   从订阅里长按收藏的节点保存的就是这种原文。 */
		if (t.indexOf('\n') >= 0) {
			try {
				Map<String, Object> m = parseBlockNode(t);
				if (m != null && !m.isEmpty())
				  return m;
			} catch (Exception ignore) {
			}
		}
		return null;
		}

		/* ---------- YAML 块风格节点 -> Map ---------- */
		/* 解析形如
		- name: x
		type: vmess
		ws-opts:
		path: /p
		headers:
		Host: a.b
		的块风格节点（也接受没有 "- " 前缀的裸块）。支持嵌套映射、块序列（alpn 等）
		与行内 flow（{...} / [...]）。解析失败返回 null。 */
		private static Map<String, Object> parseBlockNode(String text) {
		String[] rawLines = text.split("\\r?\\n");
		List<Integer> indents = new ArrayList<Integer>();
		List<String> texts = new ArrayList<String>();
		int strip = -1; /* 第一行 "- " 前缀的宽度；后续行的缩进按它折算成相对值 */
		for (String ln : rawLines) {
			if (ln.trim().isEmpty())
			  continue;
			int ind = 0;
			while (ind < ln.length() && ln.charAt(ind) == ' ')
			  ind++;
			String t2 = ln.trim();
			if (indents.isEmpty()) {
				if (t2.startsWith("- ")) {
					strip = ind + 2;
					t2 = t2.substring(2).trim();
					ind = ind + 2;
				}
			} else if (strip >= 0) {
				ind -= strip;
				if (ind < 0)
				  ind = 0;
			}
			indents.add(ind);
			texts.add(t2);
		}
		if (texts.isEmpty())
		  return null;
		int[] pos = new int[] { 0 };
		Object o = parseBlockEl(indents, texts, pos, indents.get(0));
		return (o instanceof Map) ? (Map<String, Object>) o : null;
		}

		/* 递归下降：从 pos[0] 起解析一个缩进层级上的块映射或块序列。
		节点块里出现过的形态都覆盖：标量值、嵌套映射、块序列（标量项）、
		行内 flow、以及 | / > 块标量。 */
		private static Object parseBlockEl(List<Integer> indents, List<String> texts,
			int[] pos, int indent) {
		/* 序列：同一缩进上的若干 "- item"。 */
		if (texts.get(pos[0]).startsWith("- ")) {
			List<Object> out = new ArrayList<Object>();
			while (pos[0] < texts.size()
					&& indents.get(pos[0]) == indent
					&& texts.get(pos[0]).startsWith("- ")) {
				String item = texts.get(pos[0]).substring(2).trim();
				pos[0]++;
				if (item.isEmpty()) {
					/* "- " 后跟下一行的嵌套块。 */
					if (pos[0] < texts.size() && indents.get(pos[0]) > indent)
					  out.add(parseBlockEl(indents, texts, pos, indents.get(pos[0])));
					else
					  out.add("");
				} else if (item.startsWith("{")) {
					out.add(parseFlowMap(item));
				} else if (item.startsWith("[")) {
					out.add(parseFlowSeq(item));
				} else if (item.indexOf(':') > 0 && !isQuoted(item)) {
					/* "- key: value" 形式的行内小映射（订阅里偶尔见到）。 */
					int c = item.indexOf(':');
					Map<String, Object> m = new LinkedHashMap<String, Object>();
					m.put(unquote(item.substring(0, c).trim()),
						scalarToObj(item.substring(c + 1).trim()));
					out.add(m);
				} else {
					out.add(scalarToObj(item));
				}
			}
			return out;
		}
		Map<String, Object> m = new LinkedHashMap<String, Object>();
		while (pos[0] < texts.size()) {
			int d = indents.get(pos[0]);
			String text = texts.get(pos[0]);
			if (d < indent)
			  break;
			if (text.startsWith("- ")) {
				/* 序列项：要么是外层的（比当前映射浅/同级），交还上层；要么是不认识的
				   更深层，跳过。 */
				if (d > indent)
				  pos[0]++;
				else
				  break;
				continue;
			}
			if (d > indent) {
				pos[0]++;
				continue;
			}
			int c = text.indexOf(':');
			if (c <= 0) {
				pos[0]++;
				continue;
			}
			String key = unquote(text.substring(0, c).trim());
			String val = text.substring(c + 1).trim();
			pos[0]++;
			if (val.isEmpty()) {
				if (pos[0] < texts.size() && indents.get(pos[0]) > indent)
				  m.put(key, parseBlockEl(indents, texts, pos, indents.get(pos[0])));
				else
				  m.put(key, "");
			} else if (val.startsWith("{")) {
				m.put(key, parseFlowMap(val));
			} else if (val.startsWith("[")) {
				m.put(key, parseFlowSeq(val));
			} else if (val.equals("|") || val.equals(">") || val.equals("|-")
					|| val.equals(">-") || val.equals("|+") || val.equals(">+")) {
				/* 块标量：把后续更深缩进的行拼回一个字符串。 */
				StringBuilder bs = new StringBuilder();
				while (pos[0] < texts.size() && indents.get(pos[0]) > indent) {
					if (bs.length() > 0)
					  bs.append(val.startsWith("|") ? "\n" : " ");
					bs.append(texts.get(pos[0]));
					pos[0]++;
				}
				m.put(key, bs.toString());
			} else {
				m.put(key, scalarToObj(val));
			}
		}
		return m;
		}

		/* 字符串以成对引号包住时为 true（用来区分 "- key: value" 行内映射与
		"- \"a: b\"" 这种带引号的纯标量）。 */
		private static boolean isQuoted(String s) {
		return s.length() >= 2
			&& ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
			 || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''));
		}

	/* ---------- JSON -> Map ---------- */
	private static Object parseJson(String s) throws JSONException {
		s = s.trim();
		if (s.startsWith("[")) {
			JSONArray arr = new JSONArray(s);
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < arr.length(); i++)
				list.add(jsonToObj(arr.get(i)));
			return list;
		}
		JSONObject obj = new JSONObject(s);
		return jsonToObj(obj);
	}

	private static Object jsonToObj(Object v) {
		if (v instanceof JSONObject)
		  return jsonToObj((JSONObject) v);
		if (v instanceof JSONArray) {
			JSONArray a = (JSONArray) v;
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < a.length(); i++) {
				try {
					list.add(jsonToObj(a.get(i)));
				} catch (JSONException e) {
				}
			}
			return list;
		}
		return v;
	}

	private static Map<String, Object> jsonToObj(JSONObject o) {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		java.util.Iterator<String> it = o.keys();
		while (it.hasNext()) {
			String k = it.next();
			try {
				map.put(k, jsonToObj(o.get(k)));
			} catch (JSONException e) {
			}
		}
		return map;
	}

	/* ---------- YAML flow map -> Map ---------- */
	/* 解析以 '{' 开头的 flow map（支持嵌套 {} 与 []、带引号字符串）。 */
	private static Map<String, Object> parseFlowMap(String s) {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		int i = 0;
		int n = s.length();
		if (i < n && s.charAt(i) == '{')
		  i++;
		while (i < n) {
			while (i < n && isSpace(s.charAt(i)))
				i++;
			if (i < n && s.charAt(i) == '}')
			  break;
			/* 读键名（引号内的 ':' 不算分隔）。 */
			int keyStart = i;
			int colon = -1;
			boolean inQ = false;
			char q = 0;
			while (i < n) {
				char c = s.charAt(i);
				if (inQ) {
					if (c == q)
					  inQ = false;
				} else {
					if (c == '"' || c == '\'') {
						inQ = true;
						q = c;
					} else if (c == ':') {
						colon = i;
						break;
					}
				}
				i++;
			}
			if (colon < 0)
			  break;
			String key = unquote(s.substring(keyStart, colon).trim());
			i = colon + 1;
			while (i < n && isSpace(s.charAt(i)))
				i++;
			/* 读值。 */
			Object value;
			char c0 = s.charAt(i);
			if (c0 == '{') {
				int depth = 0;
				int start = i;
				do {
					char c = s.charAt(i);
					if (c == '{')
					  depth++;
					else if (c == '}')
					  depth--;
					i++;
				} while (i < n && depth > 0);
				value = parseFlowMap(s.substring(start, i));
			} else if (c0 == '[') {
				int depth = 0;
				int start = i;
				do {
					char c = s.charAt(i);
					if (c == '[')
					  depth++;
					else if (c == ']')
					  depth--;
					i++;
				} while (i < n && depth > 0);
				value = parseFlowSeq(s.substring(start, i));
			} else {
				int start = i;
				while (i < n) {
					char c = s.charAt(i);
					if (c == ',' || c == '}' || c == ']' || c == '\n' || c == '\r')
					  break;
					if (c == '"' || c == '\'') {
						char qc = c;
						i++;
						while (i < n && s.charAt(i) != qc)
							i++;
						if (i < n)
						  i++;
					} else {
						i++;
					}
				}
				value = scalarToObj(s.substring(start, i).trim());
			}
			map.put(key, value);
		}
		return map;
	}

	private static List<Object> parseFlowSeq(String s) {
		List<Object> list = new ArrayList<Object>();
		int i = 1;
		int n = s.length();
		while (i < n - 1) {
			while (i < n && (isSpace(s.charAt(i)) || s.charAt(i) == ','))
				i++;
			if (i >= n - 1)
			  break;
			char c0 = s.charAt(i);
			if (c0 == '{') {
				int depth = 0;
				int start = i;
				do {
					char c = s.charAt(i);
					if (c == '{')
					  depth++;
					else if (c == '}')
					  depth--;
					i++;
				} while (i < n && depth > 0);
				list.add(parseFlowMap(s.substring(start, i)));
			} else {
				int start = i;
				while (i < n) {
					char c = s.charAt(i);
					if (c == ',' || c == ']' || c == '}' || c == '\n' || c == '\r')
					  break;
					if (c == '"' || c == '\'') {
						char qc = c;
						i++;
						while (i < n && s.charAt(i) != qc)
							i++;
						if (i < n)
						  i++;
					} else {
						i++;
					}
				}
				list.add(scalarToObj(s.substring(start, i).trim()));
			}
		}
		return list;
	}

	/* ---------- Map -> YAML flow map ---------- */
	public static String mapToFlow(Map<String, Object> m) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Object> e : m.entrySet()) {
			if (!first)
			  sb.append(", ");
			first = false;
			sb.append(e.getKey()).append(": ").append(valueToFlow(e.getValue()));
		}
		sb.append("}");
		return sb.toString();
	}

	private static String valueToFlow(Object v) {
		if (v instanceof Map)
		  return mapToFlow((Map<String, Object>) v);
		if (v instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object o : (List<?>) v) {
				if (!first)
				  sb.append(", ");
				first = false;
				sb.append(valueToFlow(o));
			}
			sb.append("]");
			return sb.toString();
		}
		String s = v == null ? "" : v.toString();
		if (needQuote(s))
		  return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		return s;
	}

	/* 数字 / 布尔不必加引号；含空白、冒号、逗号、括号、# 等需要引号。 */
	private static boolean needQuote(String s) {
		if (s.isEmpty())
		  return true;
		if (s.matches("-?\\d+") || s.equals("true") || s.equals("false"))
		  return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (" ,{}[]:#&*!|>%@`\"'".indexOf(c) >= 0)
			  return true;
		}
		return false;
	}

	/* ---------- Map -> JSON ---------- */
	public static JSONObject mapToJson(Map<String, Object> m) {
		JSONObject o = new JSONObject();
		for (Map.Entry<String, Object> e : m.entrySet()) {
			try {
				o.put(e.getKey(), objToJson(e.getValue()));
			} catch (JSONException ex) {
			}
		}
		return o;
	}

	private static Object objToJson(Object v) {
		if (v instanceof Map)
		  return mapToJson((Map<String, Object>) v);
		if (v instanceof List) {
			JSONArray a = new JSONArray();
			for (Object o : (List<?>) v)
				a.put(objToJson(o));
			return a;
		}
		return v;
	}

	/* 标量：加引号的一定是字符串；否则把 true/false/null 与数字还原成真实类型，
	   这样转成 JSON 时不会把 443 / false 写成字符串。 */
	private static Object scalarToObj(String s) {
		if (s == null)
		  return "";
		s = s.trim();
		if (s.isEmpty())
		  return "";
		int len = s.length();
		if (len >= 2 &&
			((s.charAt(0) == '"' && s.charAt(len - 1) == '"') ||
			 (s.charAt(0) == '\'' && s.charAt(len - 1) == '\'')))
		  return unquote(s);
		if (s.equals("true"))
		  return Boolean.TRUE;
		if (s.equals("false"))
		  return Boolean.FALSE;
		if (s.equals("null"))
		  return "";
		try {
			if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0)
			  return Double.parseDouble(s);
			long l = Long.parseLong(s);
			if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
			  return (int) l;
			return l;
		} catch (NumberFormatException ignore) {
		}
		return s;
	}

	private static boolean isSpace(char c) {
		return c == ' ' || c == '\n' || c == '\r' || c == '\t';
	}

	private static String unquote(String s) {
		if (s == null)
		  return "";
		s = s.trim();
		if (s.length() >= 2 &&
			((s.startsWith("\"") && s.endsWith("\"")) ||
			 (s.startsWith("'") && s.endsWith("'"))))
		  return s.substring(1, s.length() - 1);
		return s;
	}
}
