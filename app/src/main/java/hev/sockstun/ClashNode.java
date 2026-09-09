/*
 ============================================================================
 Name        : ClashNode.java
 Description : One parsed SOCKS5 proxy node from a clash.yml subscription.
 ============================================================================
 */

package hev.sockstun;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ClashNode {
	/* -1 = never tested, -2 = unreachable, >=0 = latency in ms */
	public long latency = -1;
	public String name;
	public String type;
	public String server;
	public int port;
	public String username;
	public String password;

	public ClashNode(String name, String type, String server, int port,
			String username, String password) {
		this.name = name;
		this.type = type;
		this.server = server;
		this.port = port;
		this.username = username;
		this.password = password;
	}

	/* Serialize the node list as JSON so names/special chars survive safely. */
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
				out.add(n);
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
