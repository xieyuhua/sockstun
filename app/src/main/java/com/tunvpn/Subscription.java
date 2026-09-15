/*
 ============================================================================
 Name        : Subscription.java
 Description : One clash.yml subscription address. Several can be stored; the
               enabled one is what the subscribe page fetches and uses.
 ============================================================================
 */

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Subscription {
	public String id;
	public String name;
	public String url;
	/* Whether this subscription is merged into the tunnel config. A disabled
	   one keeps its cached nodes but contributes nothing to the proxy pool. */
	public boolean enabled;

	public Subscription(String id, String name, String url) {
		this(id, name, url, true);
	}

	public Subscription(String id, String name, String url, boolean enabled) {
		this.id = id;
		this.name = name;
		this.url = url;
		this.enabled = enabled;
	}

	public String label() {
		if (name != null && !name.trim().isEmpty())
		  return name.trim();
		return url;
	}

	public static String newId() {
		return Long.toString(System.currentTimeMillis(), 36);
	}

	public static String encode(List<Subscription> list) {
		JSONArray arr = new JSONArray();
		try {
			for (Subscription s : list) {
				JSONObject o = new JSONObject();
				o.put("id", s.id);
				o.put("name", s.name);
				o.put("url", s.url);
				o.put("enabled", s.enabled);
				arr.put(o);
			}
		} catch (JSONException e) {
		}
		return arr.toString();
	}

	public static List<Subscription> decode(String json) {
		List<Subscription> out = new ArrayList<Subscription>();
		if (json == null || json.isEmpty())
		  return out;
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.getJSONObject(i);
				/* Default true so subscriptions saved before this field existed
				   stay enabled (no silent opt-out on upgrade). */
				out.add(new Subscription(
					o.optString("id"),
					o.optString("name"),
					o.optString("url"),
					o.optBoolean("enabled", true)));
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
