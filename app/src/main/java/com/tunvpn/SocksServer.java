/*
 ============================================================================
 文件名  : SocksServer.java
 说明    : 手动配置的一条上游服务器。可以存多条；启用其中一条后它就成为上游
           （此时订阅被忽略）。
 ============================================================================
*/

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SocksServer {
	public String id;
	public String name;
	public String addr;
	public int port;
	public String user;
	public String pass;
	/* 代理协议。"socks5" 保持原有行为（App 自己起一个 SOCKS5 上游）。其它值表示用户
	   在 raw 里粘贴了完整的 clash 节点定义（hysteria2 / vmess / vless / trojan / ss …），
	   MihomoConfig 会把它**原样**发射成上游节点。 */
	public String type = "socks5";
	public String raw = "";
	/* 运行期连通性探测结果，语义与 ClashNode.latency 一致：
	   -1 = 从未测过，-2 = 不可达，>=0 = 延迟毫秒数。会随节点一起持久化
	   （见 encode()/decode() 的 "latency" 字段），所以服务器列表里测过的
	   延迟下次进来还显示，不必重测。 */
	public long latency = -1;

	public SocksServer(String id, String name, String addr, int port,
			String user, String pass) {
		this(id, name, addr, port, user, pass, "socks5", "");
	}

	public SocksServer(String id, String name, String addr, int port,
			String user, String pass, String type, String raw) {
		this.id = id;
		this.name = name;
		this.addr = addr;
		this.port = port;
		this.user = user;
		this.pass = pass;
		this.type = (type == null || type.isEmpty()) ? "socks5" : type;
		this.raw = raw == null ? "" : raw;
	}

	public boolean isSocks() {
		return type == null || type.isEmpty() || "socks5".equals(type);
	}

	public String label() {
		if (name != null && !name.trim().isEmpty())
		  return name.trim();
		if (!isSocks())
		  return type;
		return addr + ":" + port;
	}

	/* 服务器列表用的一行摘要：SOCKS5 显示地址端口，其它协议只显示协议名
	   （粘贴式节点没有可用的地址端口可显示）。 */
	public String summary() {
		if (isSocks())
		  return (addr == null ? "" : addr) + ":" + port;
		return type;
	}

	public static String newId() {
		return Long.toString(System.currentTimeMillis(), 36);
	}

	public static String encode(List<SocksServer> list) {
		JSONArray arr = new JSONArray();
		try {
			for (SocksServer s : list) {
				JSONObject o = new JSONObject();
				o.put("id", s.id);
				o.put("name", s.name);
				o.put("addr", s.addr);
				o.put("port", s.port);
				o.put("user", s.user);
				o.put("pass", s.pass);
				o.put("type", s.type);
				o.put("raw", s.raw);
				o.put("latency", s.latency);
				arr.put(o);
			}
		} catch (JSONException e) {
		}
		return arr.toString();
	}

	public static List<SocksServer> decode(String json) {
		List<SocksServer> out = new ArrayList<SocksServer>();
		if (json == null || json.isEmpty())
		  return out;
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				SocksServer s = new SocksServer(
					o.optString("id"),
					o.optString("name"),
					o.optString("addr"),
					o.optInt("port", 1080),
					o.optString("user"),
					o.optString("pass"),
					o.optString("type", "socks5"),
					o.optString("raw", ""));
				/* 测速结果一并持久化，下次进列表直接显示，不必重测。 */
				s.latency = o.optLong("latency", -1);
				out.add(s);
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
