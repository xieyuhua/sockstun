/*
 ============================================================================
 Name        : SocksServer.java
 Description : One manually configured SOCKS5 upstream server. Servers are
               kept as a list; enabling one makes it the upstream (and the
               subscription is then ignored).
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
	/* Proxy protocol. "socks5" keeps the original behaviour (the app's own
	   upstream is a SOCKS5 server). Any other value means the user supplied a
	   raw clash proxy definition in `raw` (hysteria2 / vmess / vless / trojan
	   / ss / ...), which MihomoConfig emits verbatim as the upstream node. */
	public String type = "socks5";
	public String raw = "";
	/* Runtime latency probe result, mirroring ClashNode.latency semantics:
	   -1 = never tested, -2 = unreachable, >=0 = latency in ms. Deliberately
	   omitted from encode()/decode() below, so it is recomputed each session
	   and never persisted. */
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

	/* One-line summary for the server list: the socket for SOCKS5, otherwise
	   just the protocol so a raw-pasted node still shows something useful. */
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
				out.add(new SocksServer(
					o.optString("id"),
					o.optString("name"),
					o.optString("addr"),
					o.optInt("port", 1080),
					o.optString("user"),
					o.optString("pass"),
					o.optString("type", "socks5"),
					o.optString("raw", "")));
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
