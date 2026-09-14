/*
 ============================================================================
 Name        : GeoIp.java
 Description : Resolve a proxy's server host to its country (ISO-3166 alpha-2).
               Resolution is local-first: a bundled GeoLite2-Country.mmdb is
               consulted via the MaxMind DB reader, so node grouping works
               fully offline. When the database is missing, a best-effort
               network lookup is used as a fallback. Results are cached both
               in memory and, through Preferences, across runs.
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;
import android.util.Log;

import com.maxmind.db.Reader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GeoIp {
	/* Group name suffix used for nodes whose country could not be resolved. */
	public static final String UNKNOWN = "OTHER";

	private static final String TAG = "GeoIp";
	private static final int TIMEOUT = 3000;
	/* Asset name of the bundled MaxMind country database. Drop the file into
	   app/src/main/assets/ to enable offline resolution. */
	private static final String MMDB_NAME = "GeoLite2-Country.mmdb";
	/* Providers tried in order (network fallback only). ipapi.co: plain text
	   "US"; ip-api.com: JSON {countryCode:"US"}. */
	private static final String[] ENDPOINTS = {
		"https://ipapi.co/%s/country/",
		"http://ip-api.com/json/%s?fields=countryCode"
	};

	/* server host/ip -> resolved code, for the lifetime of the process. Failed
	   lookups are NOT cached, so a later retry can succeed. */
	private static final Map<String, String> cache = new ConcurrentHashMap<String, String>();

	/* Lazily opened MaxMind reader; null means unavailable (and we won't retry
	   until process restart, see MMDB_TRIED). */
	private static Reader mmdbReader = null;
	private static boolean mmdbTried = false;
	private static final Object MMDB_LOCK = new Object();

	/* Country of a proxy server. Checks the persisted map first (offline),
	   then the in-memory cache, then the local database, then — only if the
	   database is unavailable — a network lookup. Returns UNKNOWN on any
	   failure (and does not cache that failure). */
	public static String countryOf(Preferences prefs, String server) {
		if (server == null || server.isEmpty())
		  return UNKNOWN;
		String cached = prefs.getServerCountry(server);
		if (cached != null && !cached.isEmpty())
		  return cached;
		String mem = cache.get(server);
		if (mem != null)
		  return mem;
		String ip = resolve(server);
		String cc = ip != null ? lookupLocal(prefs, ip) : null;
		if (cc == null && ip != null)
		  cc = lookup(ip);
		if (cc == null || cc.isEmpty())
		  return UNKNOWN;
		prefs.setServerCountry(server, cc);
		cache.put(server, cc);
		return cc;
	}

	/* Country from the bundled GeoLite2 database, or null if it is missing or
	   the address is not covered. */
	@SuppressWarnings("unchecked")
	private static String lookupLocal(Preferences prefs, String ip) {
		Context ctx = prefs.getContext();
		if (ctx == null)
		  return null;
		Reader reader = getReader(ctx);
		if (reader == null)
		  return null;
		try {
			InetAddress addr = InetAddress.getByName(ip);
			Object rec = reader.get(addr);
			if (rec instanceof Map) {
				Map<String, Object> m = (Map<String, Object>) rec;
				String cc = isoFrom(m.get("country"));
				if (cc == null)
				  cc = isoFrom(m.get("registered_country"));
				return cc;
			}
		} catch (Exception e) {
			Log.w(TAG, "mmdb lookup failed for " + ip + ": " + e);
		}
		return null;
	}

	private static String isoFrom(Object o) {
		if (o instanceof Map) {
			Object iso = ((Map<String, Object>) o).get("iso_code");
			if (iso instanceof String)
			  return ((String) iso).toUpperCase();
		}
		return null;
	}

	/* Open the bundled database once. Returns null (and remembers so) if the
	   asset is not present, in which case callers fall back to the network. */
	private static Reader getReader(Context ctx) {
		synchronized (MMDB_LOCK) {
			if (mmdbTried)
			  return mmdbReader;
			mmdbTried = true;
			try {
				InputStream in = ctx.getAssets().open(MMDB_NAME);
				mmdbReader = new Reader(in);
			} catch (Exception e) {
				Log.w(TAG, "GeoLite2 (" + MMDB_NAME + ") not found in assets, " +
					"falling back to network lookup: " + e);
				mmdbReader = null;
			}
			return mmdbReader;
		}
	}

	/* Hostname -> IP. IP literals are returned unchanged. Null on failure. */
	private static String resolve(String server) {
		if (server.matches("^[0-9.]+$") || server.contains(":"))
		  return server;
		try {
			InetAddress a = InetAddress.getByName(server);
			return a.getHostAddress();
		} catch (Exception e) {
			return null;
		}
	}

	/* Try each network endpoint until one yields a 2-letter country code
	   (used only as a fallback when the local database is unavailable). */
	private static String lookup(String ip) {
		for (String tmpl : ENDPOINTS) {
			String url = String.format(tmpl, ip);
			String body = httpGet(url);
			if (body == null)
			  continue;
			String cc = parse(body);
			if (cc != null && !cc.isEmpty())
			  return cc;
		}
		return null;
	}

	private static String parse(String body) {
		String s = body.trim();
		/* ip-api.com returns JSON; pull countryCode out of it. */
		if (s.startsWith("{")) {
			int i = s.indexOf("\"countryCode\"");
			if (i < 0)
			  return "";
			int c = s.indexOf(':', i);
			if (c < 0)
			  return "";
			int q1 = s.indexOf('"', c);
			if (q1 < 0)
			  return "";
			int q2 = s.indexOf('"', q1 + 1);
			if (q2 < 0)
			  return "";
			return s.substring(q1 + 1, q2).toUpperCase();
		}
		/* ipapi.co returns the bare code, e.g. "US\n". */
		if (s.matches("^[A-Za-z]{2}$"))
		  return s.toUpperCase();
		return "";
	}

	private static String httpGet(String urlStr) {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(TIMEOUT);
			conn.setReadTimeout(TIMEOUT);
			conn.setRequestProperty("User-Agent", "tunVPN");
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
			  return null;
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null)
				  sb.append(line);
			}
			return sb.toString();
		} catch (Exception e) {
			Log.d(TAG, "lookup failed for " + urlStr + ": " + e);
			return null;
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}
}
