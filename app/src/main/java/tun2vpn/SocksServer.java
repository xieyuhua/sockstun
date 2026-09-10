/*
 ============================================================================
 Name        : SocksServer.java
 Description : One manually configured SOCKS5 upstream server. Servers are
               kept as a list; enabling one makes it the upstream (and the
               subscription is then ignored).
 ============================================================================
 */

package tun2vpn;

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

	public SocksServer(String id, String name, String addr, int port,
			String user, String pass) {
		this.id = id;
		this.name = name;
		this.addr = addr;
		this.port = port;
		this.user = user;
		this.pass = pass;
	}

	public String label() {
		if (name != null && !name.trim().isEmpty())
		  return name.trim();
		return addr + ":" + port;
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
					o.optString("pass")));
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
