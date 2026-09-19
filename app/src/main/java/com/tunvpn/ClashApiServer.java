package com.tunvpn;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/* A tiny in-app clash-api compatible HTTP server.
 *
 * This libmihomo-android build (v0.3.3) brings the mixed-port up on quickSetup
 * but never binds the external-controller (clash-api) HTTP listener on 9090 -
 * even though the profile carries external-controller + secret. So a browser or
 * an external dashboard (yacd) can never reach the control API, while everything
 * in-app works because it talks to the core through the in-process apiAction
 * bridge instead of HTTP.
 *
 * This server restores browser/dashboard access: it listens on 0.0.0.0:API_PORT
 * and translates the clash-api REST surface to the very same in-process bridge
 * (TProxyService.bridgeApi), so every endpoint a dashboard needs
 * (/version, /proxies, /connections, node switching, ...) answers. It also
 * serves a self-contained dashboard at /ui so the user can just open
 * http://127.0.0.1:9090/ui in any browser. The clash-api secret is enforced on
 * the REST endpoints (Authorization: Bearer <secret>), like mihomo does. */
public class ClashApiServer {
	private final Context context;
	private final int port;
	private final String secret;
	private ServerSocket ss;
	private Thread acceptThread;
	private volatile boolean running;

	public ClashApiServer(Context context, int port, String secret) {
		this.context = context.getApplicationContext();
		this.port = port;
		this.secret = secret == null ? "" : secret;
	}

	/* Returns true when the listener actually came up. */
	public boolean start() {
		try {
			ss = new ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress("0.0.0.0", port));
			running = true;
			acceptThread = new Thread(this::loop);
			acceptThread.setName("clash-api-srv");
			acceptThread.setDaemon(true);
			acceptThread.start();
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	public void stop() {
		running = false;
		try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
		if (acceptThread != null) acceptThread.interrupt();
	}

	public int getPort() { return port; }

	private void loop() {
		while (running) {
			Socket s;
			try {
				s = ss.accept();
			} catch (Throwable e) {
				if (running) continue;
				break;
			}
			final Socket sock = s;
			new Thread(() -> handle(sock)).start();
		}
	}

	private void handle(Socket s) {
		try {
			s.setSoTimeout(20000);
			BufferedReader in = new BufferedReader(
				new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			OutputStream os = s.getOutputStream();

			String reqLine = in.readLine();
			if (reqLine == null) return;
			String[] parts = reqLine.split(" ");
			if (parts.length < 2) { send(os, 400, "text/plain", "Bad Request"); return; }
			String method = parts[0];
			String rawPath = parts[1];

			/* Headers (we only need a couple). */
			Map<String, String> headers = new HashMap<>();
			int contentLength = 0;
			String h;
			while ((h = in.readLine()) != null && !h.isEmpty()) {
				int idx = h.indexOf(':');
				if (idx > 0) {
					String k = h.substring(0, idx).trim().toLowerCase();
					String v = h.substring(idx + 1).trim();
					headers.put(k, v);
					if (k.equals("content-length")) {
						try { contentLength = Integer.parseInt(v); } catch (Throwable ignore) { }
					}
				}
			}

			/* Read the request body (PUT /proxies/{name}). */
			String body = null;
			if (contentLength > 0) {
				char[] cbuf = new char[contentLength];
				int rd = 0;
				while (rd < contentLength) {
					int n = in.read(cbuf, rd, contentLength - rd);
					if (n < 0) break;
					rd += n;
				}
				body = new String(cbuf, 0, rd);
			}

			/* Split path / query. */
			int q = rawPath.indexOf('?');
			String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
			String query = q >= 0 ? rawPath.substring(q + 1) : "";
			try { path = java.net.URLDecoder.decode(path, "UTF-8"); } catch (Throwable ignore) { }

			/* CORS preflight. */
			if ("OPTIONS".equals(method)) {
				sendCors(os);
				sendStatus(os, 204, "text/plain", "");
				return;
			}

			/* The dashboard page itself needs no auth (it bakes the token in). */
			if (path.equals("/") || path.equals("/ui") || path.equals("/ui/")) {
				serveDashboard(os);
				return;
			}

			/* Everything else is the clash-api: require the bearer token. */
			if (!secret.isEmpty()) {
				String auth = headers.get("authorization");
				if (auth == null || !("Bearer " + secret).equals(auth.trim())) {
					sendCors(os);
					sendStatus(os, 401, "application/json",
						"{\"message\":\"Unauthorized\"}");
					return;
				}
			}

			route(os, method, path, query, body);
		} catch (Throwable ignore) {
			/* A broken client socket is not worth logging. */
		} finally {
			try { s.close(); } catch (Throwable ignore) { }
		}
	}

	private void route(OutputStream os, String method, String path, String query, String body) {
		try {
			if ("GET".equals(method) && path.equals("/version")) {
				json(os, 200, versionJson());
				return;
			}
			if ("GET".equals(method) && path.equals("/configs")) {
				json(os, 200, configsJson());
				return;
			}
			if ("GET".equals(method) && path.equals("/rules")) {
				json(os, 200, "{\"rules\":[]}");
				return;
			}
			if (path.equals("/connections")) {
				if ("DELETE".equals(method)) {
					TProxyService.ApiResult r = TProxyService.bridgeApi("DELETE",
						"127.0.0.1", port, "/connections", null, secret);
					json(os, r.code == -1 ? 502 : (r.code <= 0 ? 204 : r.code),
						r.body == null ? "" : r.body);
					return;
				}
				if ("GET".equals(method)) {
					TProxyService.ApiResult r = TProxyService.bridgeApi("GET",
						"127.0.0.1", port, "/connections", null, secret);
					json(os, r.code == -1 ? 502 : r.code, r.body == null ? "" : r.body);
					return;
				}
			}
			if (path.startsWith("/proxies")) {
				if ("GET".equals(method)) {
					TProxyService.ApiResult r = TProxyService.bridgeApi("GET",
						"127.0.0.1", port, path, null, secret);
					json(os, r.code == -1 ? 502 : r.code, r.body == null ? "" : r.body);
					return;
				}
				if ("PUT".equals(method)) {
					TProxyService.ApiResult r = TProxyService.bridgeApi("PUT",
						"127.0.0.1", port, path, body, secret);
					json(os, r.code == -1 ? 502 : (r.code <= 0 ? 204 : r.code),
						r.body == null ? "" : r.body);
					return;
				}
				/* POST /proxies/{group}/delay?url=...&timeout=...
				   The core measures a real forwarded latency; we approximate it
				   with a request through the live mixed-port (the selected node
				   carries it), which is exactly what Clash's own /delay does. */
				if ("POST".equals(method) && path.contains("/delay")) {
					serveDelay(os, query);
					return;
				}
			}
			sendCors(os);
			sendStatus(os, 404, "application/json", "{\"message\":\"Not Found\"}");
		} catch (Throwable e) {
			sendCors(os);
			sendStatus(os, 500, "application/json", "{\"message\":\"Internal\"}");
		}
	}

	/* Best-effort latency measurement through the active tunnel. Clash's own
	   /delay test forwards a real request through the selected node; we do the
	   same via the live mixed-port, which the proxyFetch helper already drives. */
	private void serveDelay(OutputStream os, String query) {
		String url = "https://www.gstatic.com/generate_204";
		if (query != null && !query.isEmpty()) {
			for (String kv : query.split("&")) {
				int eq = kv.indexOf('=');
				if (eq > 0 && "url".equals(kv.substring(0, eq))) {
					try { url = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"); } catch (Throwable ignore) { }
				}
			}
		}
		int proxyPort = new Preferences(context).getProxyPort();
		TProxyService.ApiResult r = TProxyService.proxyFetch(proxyPort, url);
		if (r.code > 0)
		  json(os, 200, "{\"delay\":" + r.rtt + "}");
		else
		  json(os, 400, "{\"message\":\"delay test failed\"}");
	}

	private String versionJson() {
		return "{\"version\":\"v1.19.30\",\"premium\":false," +
			"\"meta\":true,\"provider\":\"tunVPN (embedded clash-api)\"}";
	}

	private String configsJson() {
		return "{\"mode\":\"rule\",\"port\":0,\"socks-port\":0," +
			"\"allow-lan\":false,\"log-level\":\"info\",\"ipv6\":false," +
			"\"interface-name\":\"\",\"unified-delay\":true}";
	}

	private void serveDashboard(OutputStream os) throws IOException {
		String html;
		try (InputStream is = context.getAssets().open("dashboard.html")) {
			byte[] buf = new byte[is.available()];
			int n = is.read(buf);
			html = new String(buf, 0, n < 0 ? 0 : n, StandardCharsets.UTF_8);
		} catch (Throwable e) {
			html = "<h2>tunVPN 控制台</h2><p>dashboard.html 缺失。</p>";
		}
		html = html.replace("{{TOKEN}}", secret);
		sendCors(os);
		sendStatus(os, 200, "text/html; charset=utf-8", html);
	}

	/* ---- low level HTTP helpers ---- */

	private void json(OutputStream os, int code, String body) throws IOException {
		sendCors(os);
		sendStatus(os, code, "application/json; charset=utf-8", body == null ? "" : body);
	}

	private void send(OutputStream os, int code, String type, String body) throws IOException {
		sendCors(os);
		sendStatus(os, code, type, body);
	}

	private void sendCors(OutputStream os) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("Access-Control-Allow-Origin: *\r\n");
		sb.append("Access-Control-Allow-Methods: GET, PUT, POST, DELETE, OPTIONS\r\n");
		sb.append("Access-Control-Allow-Headers: Authorization, Content-Type, Origin, Accept\r\n");
		os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private void sendStatus(OutputStream os, int code, String type, String body) throws IOException {
		String reason = code == 204 ? "No Content" : (code == 401 ? "Unauthorized"
			: (code == 400 ? "Bad Request" : (code == 404 ? "Not Found"
			: (code == 502 ? "Bad Gateway" : (code == 500 ? "Internal Server Error" : "OK")))));
		StringBuilder sb = new StringBuilder();
		sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
		sb.append("Content-Type: ").append(type).append("\r\n");
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		sb.append("Content-Length: ").append(b.length).append("\r\n");
		sb.append("Connection: close\r\n\r\n");
		os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
		if (b.length > 0) os.write(b);
		os.flush();
	}
}
