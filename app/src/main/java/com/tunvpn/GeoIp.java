/*
 ============================================================================
 Name        : GeoIp.java
 Description : Resolve a proxy's server host to its country (ISO-3166 alpha-2)
               via a public GeoIP service. Results are cached both in memory
               for the process and, through Preferences, across runs, so the
               config builder can group nodes by country without touching the
               network at tunnel-start time.
 ============================================================================
*/

package com.tunvpn;

import android.util.Log;

import java.io.BufferedReader;
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
	/* Providers tried in order; each returns the 2-letter code differently.
	   ipapi.co: plain text body "US"; ip-api.com: JSON {countryCode:"US"}. */
	private static final String[] ENDPOINTS = {
		"https://ipapi.co/%s/country/",
		"http://ip-api.com/json/%s?fields=countryCode"
	};

	/* server host/ip -> resolved code, for the lifetime of the process. Failed
	   lookups are NOT cached, so a later retry can succeed. */
	private static final Map<String, String> cache = new ConcurrentHashMap<String, String>();

	/* Country of a proxy server. Checks the persisted map first (offline),
	   then the in-memory cache, then performs a best-effort network lookup.
	   Returns UNKNOWN on any failure (and does not cache that failure). */
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
		String cc = ip != null ? lookup(ip) : null;
		if (cc == null || cc.isEmpty())
		  return UNKNOWN;
		prefs.setServerCountry(server, cc);
		cache.put(server, cc);
		return cc;
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

	/* Try each endpoint until one yields a 2-letter country code. */
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
