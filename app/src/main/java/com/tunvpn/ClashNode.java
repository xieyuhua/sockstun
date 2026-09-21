/*
 ============================================================================
 文件名  : ClashNode.java
 说明    : 从 clash.yml 订阅里解析出的一个代理节点。
 ============================================================================
*/

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ClashNode {
	/* -1 = 从未测过，-2 = 不可达，>=0 = 延迟毫秒数。
	   由测速线程池写入、UI 线程读取，所以是 volatile。 */
	public volatile long latency = -1;
	public String name;
	public String type;
	public String server;
	public int port;
	public String username;
	public String password;
	/* 节点服务器的 ISO-3166 两位国家码，由 GeoIp 解析。"": 还没探测过
	   （界面显示为"未知"）。 */
	public String country = "";
	/* 该节点解析自哪个订阅。订阅页展示的是一个合并列表，但每个节点的延迟要写回它
	   所属的那个订阅，所以要记住来源。 */
	public String subId;

	public ClashNode(String name, String type, String server, int port,
			String username, String password) {
		this.name = name;
		this.type = type;
		this.server = server;
		this.port = port;
		this.username = username;
		this.password = password;
	}

	/* 序列化成 JSON，避免节点名里的特殊字符在分隔符方案下被破坏。 */
	public static String encode(List<ClashNode> nodes) {
		JSONArray arr = new JSONArray();
		try {
			for (ClashNode n : nodes) {
				JSONObject o = new JSONObject();
				o.put("name", n.name);
				o.put("type", n.type);
				o.put("server", n.server);
				o.put("port", n.port);
				o.put("user", n.username);
				o.put("pass", n.password);
				o.put("lat", n.latency);
				o.put("cc", n.country == null ? "" : n.country);
				o.put("sub", n.subId == null ? "" : n.subId);
				arr.put(o);
			}
		} catch (JSONException e) {
		}
		return arr.toString();
	}

	public static List<ClashNode> decode(String json) {
		List<ClashNode> out = new ArrayList<ClashNode>();
		if (json == null || json.isEmpty())
		  return out;
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				ClashNode n = new ClashNode(
					o.optString("name"),
					o.optString("type"),
					o.optString("server"),
					o.optInt("port", 0),
					o.optString("user"),
					o.optString("pass"));
				n.latency = o.optLong("lat", -1);
				n.country = o.optString("cc", "");
				n.subId = o.optString("sub");
				out.add(n);
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
