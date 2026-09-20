/*
 ============================================================================
 Name        : ClashApiServer.java
 Description : A tiny loopback HTTP server that exposes mihomo's control API
               (the "clash-api") by translating each REST call to the in-process
               action bridge. libmihomo is a JNI-first library and never binds
               external-controller itself, so without this nothing listens on
               the API port: a browser cannot reach 127.0.0.1:9090 and the app's
               own REST-based code paths (the latency test) fail too.
               Runs inside the :native process, where the core is loaded.
 ============================================================================
*/

package com.tunvpn;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class ClashApiServer {
	/* The latency test fires one request per node (up to 16 in parallel), so
	   the pool must be at least that wide or the tests would serialise. */
	private static final int WORKERS = 16;

	private final int port;
	private final Preferences prefs;
	private volatile ServerSocket server;
	private volatile boolean running = false;
	private Thread acceptThread;
	private final ExecutorService pool = Executors.newFixedThreadPool(WORKERS);

	ClashApiServer(int port, Preferences prefs) {
		this.port = port;
		this.prefs = prefs;
	}

	/* Bind 127.0.0.1:<port>. Returns false when the port is already taken
	   (e.g. mihomo DID bind it in this environment); the caller just logs and
	   keeps going, and the core's own listener then serves the API. */
	boolean start() {
		try {
			ServerSocket ss = new ServerSocket();
			ss.setReuseAddress(true);
			ss.bind(new InetSocketAddress("127.0.0.1", port));
			server = ss;
			running = true;
			acceptThread = new Thread(new Runnable() {
				@Override public void run() { acceptLoop(); }
			}, "clash-api");
			acceptThread.setDaemon(true);
			acceptThread.start();
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	void stop() {
		running = false;
		try { if (server != null) server.close(); } catch (Throwable ignore) { }
		server = null;
		pool.shutdownNow();
	}

	private void acceptLoop() {
		while (running) {
			try {
				final Socket s = server.accept();
				pool.execute(new Runnable() {
					@Override public void run() { handle(s); }
				});
			} catch (Throwable e) {
				if (!running) return;
			}
		}
	}

	private void handle(Socket s) {
		try {
			s.setSoTimeout(65000);
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			String requestLine = readLine(in);
			if (requestLine == null)
			  return;
			String[] parts = requestLine.split(" ");
			if (parts.length < 2) {
				write(out, 400, "application/json", "{\"message\":\"bad request\"}");
				return;
			}
			String method = parts[0];
			String target = parts[1];
			int contentLength = 0;
			String line;
			while ((line = readLine(in)) != null && !line.isEmpty()) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					String hn = line.substring(0, colon).trim().toLowerCase();
					if (hn.equals("content-length")) {
						try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
						catch (Throwable ignore) { }
					}
				}
			}
			String body = "";
			if (contentLength > 0) {
				byte[] buf = new byte[contentLength];
				int got = 0;
				while (got < contentLength) {
					int n = in.read(buf, got, contentLength - got);
					if (n < 0) break;
					got += n;
				}
				body = new String(buf, 0, got, StandardCharsets.UTF_8);
			}
			String path = target;
			String query = "";
			int q = target.indexOf('?');
			if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
			route(method, path, query, body, out);
		} catch (Throwable ignore) {
		} finally {
			try { s.close(); } catch (Throwable ignore) { }
		}
	}

	private void route(String method, String path, String query, String body,
			OutputStream out) throws Exception {
		/* The info page needs no auth (we only ever listen on loopback). */
		if (path.equals("/") || path.equals("/ui") || path.equals("/ui/")) {
			write(out, 200, "text/html; charset=utf-8", dashboardHtml());
			return;
		}
		/* Everything else mirrors mihomo's REST surface, translated to the
		   in-process bridge (the names mihomo itself uses). */
		if (path.equals("/version")) {
			write(out, 200, "application/json", "{\"meta\":true,\"version\":\"v1.19.30\"}");
			return;
		}
		if (path.equals("/connections")) {
			if ("DELETE".equals(method)) {
				String d = TProxyService.apiAction("closeAllConnections", null);
				write(out, d != null ? 204 : 502, "application/json", "");
				return;
			}
			String d = TProxyService.apiAction("getConnections", null);
			if (d == null) { write(out, 502, "application/json", "{\"message\":\"bridge error\"}"); return; }
			write(out, 200, "application/json", d);
			return;
		}
		if (path.equals("/proxies")) {
			String d = TProxyService.apiAction("getProxies", null);
			if (d == null) { write(out, 502, "application/json", "{\"message\":\"bridge error\"}"); return; }
			write(out, 200, "application/json", d);
			return;
		}
		if (path.startsWith("/proxies/")) {
			String rest = path.substring("/proxies/".length());
			if (rest.endsWith("/delay")) {
				String name = URLDecoder.decode(
					rest.substring(0, rest.length() - "/delay".length()), "UTF-8");
				handleDelay(name, query, out);
				return;
			}
			String name = URLDecoder.decode(rest, "UTF-8");
			if ("PUT".equals(method)) {
				String proxy = "";
				try { proxy = new JSONObject(body).optString("name", ""); } catch (Throwable ignore) { }
				String d = TProxyService.apiAction("changeProxy",
					"{\"group-name\":\"" + esc(name) + "\",\"proxy-name\":\"" + esc(proxy) + "\"}");
				write(out, d != null ? 204 : 502, "application/json", "");
				return;
			}
			String d = TProxyService.apiAction("getProxies", null);
			if (d == null) { write(out, 502, "application/json", "{\"message\":\"bridge error\"}"); return; }
			try {
				JSONObject all = new JSONObject(d).optJSONObject("proxies");
				JSONObject g = all == null ? null : all.optJSONObject(name);
				if (g == null) { write(out, 404, "application/json", "{\"message\":\"proxy not found\"}"); return; }
				write(out, 200, "application/json", g.toString());
			} catch (Throwable e) {
				write(out, 502, "application/json", "{\"message\":\"bridge error\"}");
			}
			return;
		}
		if (path.equals("/configs")) {
			write(out, 200, "application/json",
				"{\"mode\":\"rule\",\"mixed-port\":" + prefs.getProxyPort() + "}");
			return;
		}
		write(out, 404, "application/json", "{\"message\":\"not found\"}");
	}

	/* GET /proxies/{name}/delay?url=&timeout= -> mihomo's ISOLATED per-proxy
	   URL test via the "testDelay" action (exactly what FlClash uses). It does
	   NOT touch the group selection, so many run concurrently. Returns
	   {"delay": n} on success, 504 when the node cannot complete the probe. */
	private void handleDelay(String name, String query, OutputStream out) throws Exception {
		String url = param(query, "url");
		if (url == null || url.isEmpty())
		  url = "http://www.gstatic.com/generate_204";
		int timeout = 5000;
		try { timeout = Integer.parseInt(param(query, "timeout")); } catch (Throwable ignore) { }
		if (timeout <= 0)
		  timeout = 5000;
		/* Same parameter shape the in-process probe uses (CoreTestHost knows
		   which one this core accepts - see DELAY_SHAPES). */
		String data = CoreTestHost.delayData(name, url, timeout);
		/* The probe may run for `timeout` inside the core, so wait past it -
		   the default 6s bridge wait cuts a long probe off and loses the
		   verdict (which then looked like a failed node). */
		String r = data == null ? null
			: TProxyService.apiAction("testDelay", data, timeout + 5000L);
		if (r == null) {
			/* No answer at all: the bridge/core is the problem, the node is
			   NOT proven dead. 502 = "test could not run"; the caller must
			   leave the node untested rather than mark it 不可用. */
			TProxyService.log("clash-api delay: 无返回 name=" + name + " url=" + url);
			write(out, 502, "application/json", "{\"message\":\"bridge error\"}");
			return;
		}
		int delay;
		try {
			delay = Integer.parseInt(r.trim());
		} catch (Throwable e) {
			/* Answered with something that is not a number: malfunction, not
			   a node verdict. Reporting 504 here used to flip working nodes
			   to 不可用, so keep it a 502. */
			TProxyService.log("clash-api delay: 返回非数字 \"" + r + "\" name=" + name);
			write(out, 502, "application/json", "{\"message\":\"bad delay result\"}");
			return;
		}
		if (delay > 0) {
			write(out, 200, "application/json", "{\"delay\":" + delay + "}");
		} else {
			TProxyService.log("clash-api delay: 内核判定失败(" + delay + ") name="
				+ name + " url=" + url);
			write(out, 504, "application/json", "{\"message\":\"An error occurred in the delay test\"}");
		}
	}

	private static String param(String query, String key) {
		if (query == null)
		  return "";
		for (String kv : query.split("&")) {
			int eq = kv.indexOf('=');
			if (eq < 0)
			  continue;
			if (kv.substring(0, eq).equals(key)) {
				try { return URLDecoder.decode(kv.substring(eq + 1), "UTF-8"); }
				catch (Throwable e) { return kv.substring(eq + 1); }
			}
		}
		return "";
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/* Read one CRLF/LF-terminated line; null at end of stream. */
	private static String readLine(InputStream in) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\r') {
				int c = in.read();
				if (c == '\n') break;
				bos.write('\r');
				if (c != -1) bos.write(c);
			} else if (b == '\n') {
				break;
			} else {
				bos.write(b);
			}
		}
		if (b == -1 && bos.size() == 0)
		  return null;
		return new String(bos.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void write(OutputStream out, int code, String type, String body) throws Exception {
		byte[] bytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
		String reason = code == 200 ? "OK" : code == 204 ? "No Content"
			: code == 400 ? "Bad Request" : code == 404 ? "Not Found"
			: code == 502 ? "Bad Gateway" : code == 504 ? "Gateway Timeout" : "";
		StringBuilder head = new StringBuilder();
		head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
			.append("Content-Type: ").append(type).append("\r\n")
			.append("Content-Length: ").append(bytes.length).append("\r\n")
			.append("Connection: close\r\n\r\n");
		out.write(head.toString().getBytes(StandardCharsets.UTF_8));
		if (bytes.length > 0)
		  out.write(bytes);
		out.flush();
	}

	private String dashboardHtml() {
		return "<!doctype html><html><head><meta charset=\"utf-8\">"
			+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
			+ "<title>ProTun Clash API</title>"
			+ "<style>body{font-family:sans-serif;margin:24px;background:#111;color:#eee}"
			+ "a{color:#7cf}code{background:#222;padding:2px 6px;border-radius:4px}</style></head><body>"
			+ "<h2>ProTun · mihomo 控制接口</h2>"
			+ "<p>本服务由 App 在内核进程内提供（这台设备的内核不绑定 external-controller）。"
			+ "仅监听 <code>127.0.0.1:" + port + "</code>。</p>"
			+ "<ul>"
			+ "<li><a href=\"/version\">/version</a></li>"
			+ "<li><a href=\"/connections\">/connections</a></li>"
			+ "<li><a href=\"/proxies\">/proxies</a></li>"
			+ "<li><a href=\"/configs\">/configs</a></li>"
			+ "</ul>"
			+ "<p>把地址填入外部面板（metacubexd / yacd 等）即可使用。</p>"
			+ "</body></html>";
	}
}
