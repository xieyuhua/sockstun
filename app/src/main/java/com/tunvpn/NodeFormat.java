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
		/* 去掉开头的列表短横线。 */
		if (t.startsWith("-"))
		  t = t.substring(1).trim();

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
		return null;
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
