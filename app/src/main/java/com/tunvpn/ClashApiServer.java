/*
 ============================================================================
 文件名  : ClashApiServer.java
 说明    : 一个极小的**回环** HTTP 服务，把 mihomo 的控制接口（clash-api）暴露出来：
           每个 REST 请求都被翻译成进程内的动作桥调用。libmihomo 是 JNI 优先的库，
           自己从不绑定 external-controller，所以没有它就没有任何东西监听 API 端口：
           浏览器连不上 127.0.0.1:9090，App 自己的 REST 代码路径（延迟测试）也会失败。
           运行在 :native 进程（内核所在的那个进程）里。
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
	/* 工作线程数。再多也没用：每个路由最终都落到 TProxyService.apiAction()，而它被
	   原生桥"单次在途回调"的限制串行化了 —— 多出来的 worker 只是堵在那把锁上
	   （16 个线程约 16MB 栈，吞吐一点不涨）。App 自己的探测池是 4 并发，这里与它对齐；
	   外部面板的请求就排在同一个锁后面。 */
	private static final int WORKERS = 4;
	/* 允许缓冲的最大请求体。Content-Length 直接来自网络，不能让一个胡乱（或恶意）的
	   数值决定分配大小：只有本机能访问，但本机任何进程都能发一个 2GB 的头。 */
	private static final int MAX_BODY = 1 << 20;

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

	/* 绑定 127.0.0.1:<port>。端口被占用时返回 false（例如这个环境里 mihomo **自己**
	   绑上了）；调用方只记日志然后继续，之后由内核自己的监听提供服务。 */
	boolean start() {
		ServerSocket ss = null;
		try {
			ss = new ServerSocket();
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
			/* 绑定失败不能泄漏这个 socket（即使从未 bind 成功，它也占着一个 fd），
			   而且**失败原因**必须进日志：调用方以前一律宣称"端口被占用"，把权限问题、
			   fd 耗尽等真实原因都掩盖了。 */
			if (ss != null) {
				try { ss.close(); } catch (Throwable ignore) { }
			}
			TProxyService.log("clash-api: 监听 127.0.0.1:" + port + " 失败：" + e);
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
				/* 本该在运行时 accept() 却失败，说明监听已不可用（fd 耗尽、被关掉）。
				   退避一下，而不是让这个线程 100% 空转烧 CPU。 */
				try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
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
			/* 不能拿网络上传来的 Content-Length 直接决定分配大小：一个乱写的头就能
			   把进程撑爆（虽然只有本机能访问，但本机任何 App 都能发）。 */
			if (contentLength > MAX_BODY) {
				write(out, 413, "application/json", "{\"message\":\"body too large\"}");
				return;
			}
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
		} catch (Throwable e) {
			/* 吞掉这里会让"路由失败"看起来像"内核没回应"：客户端只看到连接被关，
			   日志里一个字都没有。记下来；隧道本身不受影响。 */
			TProxyService.log("clash-api: 请求处理失败 " + e);
		} finally {
			try { s.close(); } catch (Throwable ignore) { }
		}
	}

	private void route(String method, String path, String query, String body,
			OutputStream out) throws Exception {
		/* 说明页不需要鉴权（我们只监听回环）。 */
		if (path.equals("/") || path.equals("/ui") || path.equals("/ui/")) {
			write(out, 200, "text/html; charset=utf-8", dashboardHtml());
			return;
		}
		/* 其余接口对齐 mihomo 的 REST 面（用内核自己的名字），只是被翻译成进程内的
		   动作桥调用。 */
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

	/* GET /proxies/{name}/delay?url=&timeout= → 走 "testDelay" 动作做内核的**隔离**逐节点
	   URL 测速（与 FlClash 完全一致）。它**不碰**组的选择，所以可以并发很多个。
	   成功返回 {"delay": n}；节点无法完成探测时返回 504。 */
	private void handleDelay(String name, String query, OutputStream out) throws Exception {
		String url = param(query, "url");
		if (url == null || url.isEmpty())
		  url = "http://www.gstatic.com/generate_204";
		int timeout = 5000;
		try { timeout = Integer.parseInt(param(query, "timeout")); } catch (Throwable ignore) { }
		if (timeout <= 0)
		  timeout = 5000;
		/* 与进程内探测使用同一种参数形状（CoreTestHost 知道这个内核接受哪一种，
		   见 DELAY_SHAPES）。 */
		String data = CoreTestHost.delayData(name, url, timeout);
		/* 探测可能在内核里跑满 `timeout`，所以要等得比它更久：默认 6s 的桥等待会把
		   长时间探测掐断、丢掉判定结果（那就会被当成"节点失败"）。 */
		String r = data == null ? null
			: TProxyService.apiAction("testDelay", data, timeout + 5000L);
		if (r == null) {
			/* 完全没有回应：问题在桥/内核，**不能**据此认定节点已死。
			   502 = "测试没能跑起来"；调用方必须把节点留作未测速，而不是标成不可用。 */
			TProxyService.log("clash-api delay: 无返回 name=" + name + " url=" + url);
			write(out, 502, "application/json", "{\"message\":\"bridge error\"}");
			return;
		}
		int delay;
		try {
			delay = Integer.parseInt(r.trim());
		} catch (Throwable e) {
			/* 回了一个不是数字的东西：这是故障，不是节点判定。以前这里报 504，会把
			   本来可用的节点刷成"不可用"，所以保持 502。 */
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

	/* 读一行（以 CRLF 或 LF 结尾）；流已结束返回 null。 */
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
			: code == 413 ? "Payload Too Large"
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
