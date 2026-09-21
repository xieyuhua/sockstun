/*
 ============================================================================
 文件名  : Subscription.java
 说明    : 一条 clash.yml 订阅地址。可以保存多条；**启用**的那些会被订阅页拉取并
           合并进节点池（当前实现是"全部启用的都合并"，不再有"默认订阅"的概念）。
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
	/* 是否合并进隧道配置。禁用后仍保留已缓存的节点，但不贡献任何代理。 */
	public boolean enabled;
	/* **本地内容**（备份 / 导入）：节点不来自网络，而是直接存在本地的这份内容
	   （`SubRaw.<id>`）。所以"拉取订阅"会跳过它、编辑时也没有地址可填 —— 它的用途是
	   "把当前可用的节点固化下来"，机场地址失效或订阅被清空时仍能直接启用。 */
	public boolean local;

	public Subscription(String id, String name, String url) {
		this(id, name, url, true);
	}

	public Subscription(String id, String name, String url, boolean enabled) {
		this(id, name, url, enabled, false);
	}

	public Subscription(String id, String name, String url, boolean enabled, boolean local) {
		this.id = id;
		this.name = name;
		this.url = url;
		this.enabled = enabled;
		this.local = local;
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
				o.put("local", s.local);
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
				/* 默认 true：这个字段出现之前保存的订阅升级后保持启用
				   （不能悄悄把用户订阅关掉）。 */
				out.add(new Subscription(
					o.optString("id"),
					o.optString("name"),
					o.optString("url"),
					o.optBoolean("enabled", true),
					o.optBoolean("local", false)));
			}
		} catch (JSONException e) {
		}
		return out;
	}
}
