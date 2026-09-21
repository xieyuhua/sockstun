/*
 ============================================================================
 文件名  : WebDav.java
 说明    : 极简 WebDAV 客户端，只做"订阅备份"需要的三件事：上传、下载、测试连接。
           用 HttpURLConnection + Basic 鉴权，无第三方依赖。
           **所有方法都跑在工作线程上**；出错抛 IOException，消息里带上可读原因
           （HTTP 码 + 服务器原话），由调用方决定提示用户还是回退到本地备份。

           两套入口：
           - `xxx(Preferences, ...)`：用已存盘的配置，供备份 / 导入流程；
           - `xxx(String url, String user, String pass, String dir, ...)`：用临时填好的
             值，供设置弹窗里"先填后测"（不必先存盘）。逻辑只在参数版里实现一份。
 ============================================================================
*/

package com.tunvpn;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WebDav {
	/* 一次请求的超时。备份文件很小，卡住通常是网络或地址写错，早点报出来更好。 */
	private static final int TIMEOUT_MS = 15000;

	/* 备份索引：远端一份纯文本清单，一行一个文件名。
	   **有意不用 PROPFIND 列目录**：PROPFIND 是非标准动词，而 HttpURLConnection 只接受
	   GET/POST/PUT/DELETE/HEAD/OPTIONS/TRACE，在部分实现上会直接抛 ProtocolException；
	   而索引文件只用到 GET/PUT —— 各家 WebDAV（Nextcloud、坚果云…）都支持。
	   代价是清单要与文件同步：见 uploadBackup()（先读索引、把新名插到最前、再写回）。 */
	private static final String INDEX = "tunvpn-index.txt";
	/* 索引最多记这么多条。注意：这只影响**清单**，不会去删远端的文件。 */
	private static final int MAX_INDEX = 50;

	/* ===== 配置归一化 ===== */

	/* 拼接后的目录地址：`<url>[/<dir>]/`。尾斜杠会补齐，目录两端的斜杠会被抹平，
	   所以用户填 `https://dav.example.com/x`、`.../x/`、目录填 `/bak/` 都能对上。 */
	static String dirUrl(String url, String dir) {
		String u = url == null ? "" : url.trim();
		if (u.isEmpty())
		  return "";
		if (!u.endsWith("/"))
		  u = u + "/";
		String d = dir == null ? "" : dir.trim();
		while (d.startsWith("/"))
		  d = d.substring(1);
		while (d.endsWith("/"))
		  d = d.substring(0, d.length() - 1);
		if (!d.isEmpty())
		  u = u + d + "/";
		return u;
	}

	/* 用已存盘配置算目录地址（供备份 / 导入流程）。 */
	static String dirUrl(Preferences prefs) {
		return dirUrl(prefs.getWebdavUrl(), prefs.getWebdavDir());
	}

	/* Basic 鉴权头；账号为空时返回 null（匿名访问）。 */
	private static String authHeader(String user, String pass) {
		String u = user == null ? "" : user.trim();
		if (u.isEmpty())
		  return null;
		String token = u + ":" + (pass == null ? "" : pass);
		return "Basic " + Base64.encodeToString(
			token.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
	}

	/* 某文件在远端的最终 URL（目录 + 转义后的文件名）。 */
	private static String fileUrl(String url, String dir, String name) {
		return dirUrl(url, dir) + encode(name);
	}

	/* ===== 参数版（对外也提供，供设置弹窗先填后测） ===== */

	static void put(String url, String user, String pass, String dir, String name, String text)
			throws IOException {
		Resp r = send("PUT", fileUrl(url, dir, name), authHeader(user, pass),
			text, "text/yaml; charset=utf-8");
		if (r.code == 200 || r.code == 201 || r.code == 204)
		  return;
		if (r.code == 409)
		  throw new IOException("目标目录不存在（HTTP 409）—— 请先在网盘里新建「"
			+ (dir == null || dir.trim().isEmpty() ? "根目录" : dir.trim())
			+ "」，或把「远程目录」留空");
		throw new IOException(describe(r));
	}

	static String get(String url, String user, String pass, String dir, String name)
			throws IOException {
		Resp r = send("GET", fileUrl(url, dir, name), authHeader(user, pass), null, null);
		if (r.code == 200)
		  return r.body;
		if (r.code == 404)
		  throw new IOException("远端没有这个文件（HTTP 404）");
		throw new IOException(describe(r));
	}

	static void delete(String url, String user, String pass, String dir, String name)
			throws IOException {
		Resp r = send("DELETE", fileUrl(url, dir, name), authHeader(user, pass), null, null);
		if (r.code >= 400 && r.code != 404)
		  throw new IOException(describe(r));
	}

	/* 测试连接：写一个探针文件、读回来、再删掉 —— 一次就同时验证了写入、读取与删除
	   权限，比只"连得上"有意义得多。返回目录地址供提示使用。 */
	static String test(String url, String user, String pass, String dir) throws IOException {
		String probe = ".tunvpn-probe";
		put(url, user, pass, dir, probe, "ok");
		String back = get(url, user, pass, dir, probe);
		/* 删探针是清理动作：个别服务器禁 DELETE，不该因此判连接失败；删不掉顶多
		   在云端留个 .tunvpn-probe 小文件，不影响功能。 */
		try {
			delete(url, user, pass, dir, probe);
		} catch (IOException ignore) {
		}
		if (!"ok".equals(back.trim()))
		  throw new IOException("写入后读回的内容不一致");
		return dirUrl(url, dir);
	}

	/* ===== Preferences 版（保持旧签名，供备份 / 导入流程） ===== */

	static void put(Preferences prefs, String name, String text) throws IOException {
		put(prefs.getWebdavUrl(), prefs.getWebdavUser(), prefs.getWebdavPass(),
			prefs.getWebdavDir(), name, text);
	}

	static String get(Preferences prefs, String name) throws IOException {
		return get(prefs.getWebdavUrl(), prefs.getWebdavUser(), prefs.getWebdavPass(),
			prefs.getWebdavDir(), name);
	}

	static void delete(Preferences prefs, String name) throws IOException {
		delete(prefs.getWebdavUrl(), prefs.getWebdavUser(), prefs.getWebdavPass(),
			prefs.getWebdavDir(), name);
	}

	static String test(Preferences prefs) throws IOException {
		return test(prefs.getWebdavUrl(), prefs.getWebdavUser(), prefs.getWebdavPass(),
			prefs.getWebdavDir());
	}

	/* ===== 业务：上传 / 列目录 / 下载 ===== */

	/* 上传一份备份，返回远端文件名。 */
	static String uploadBackup(Preferences prefs, String text) throws IOException {
		String name = "tunvpn-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
			Locale.US).format(new Date()) + ".yml";
		put(prefs, name, text);
		/* 更新索引：新名字放最前、去重、截断。读失败（例如第一次用、还没有索引）时
		   按空清单处理 —— 备份本身已经上传成功，不该因为清单而报错。 */
		List<String> names;
		try {
			names = readIndex(prefs);
		} catch (IOException e) {
			names = new ArrayList<String>();
		}
		names.remove(name);
		names.add(0, name);
		while (names.size() > MAX_INDEX)
		  names.remove(names.size() - 1);
		StringBuilder sb = new StringBuilder();
		for (String n : names)
		  sb.append(n).append('\n');
		try {
			put(prefs, INDEX, sb.toString());
		} catch (IOException e) {
			TProxyService.log("WebDAV: 索引写入失败（备份文件已上传）：" + e.getMessage());
		}
		return name;
	}

	/* 远端已有的备份文件名，最新的排前面（文件名带时间戳）。 */
	static List<String> listBackups(Preferences prefs) throws IOException {
		List<String> out = readIndex(prefs);
		/* 名字倒序 = 时间倒序（yyyyMMdd-HHmmss 可以按字典序比）。 */
		Collections.sort(out, Collections.reverseOrder());
		return out;
	}

	static String download(Preferences prefs, String fileName) throws IOException {
		return get(prefs, fileName);
	}

	/* ===== 底层 ===== */

	private static List<String> readIndex(Preferences prefs) throws IOException {
		String body;
		try {
			body = get(prefs, INDEX);
		} catch (IOException e) {
			/* 索引还不存在（第一次备份前 / 刚建好的空目录）→ 当作空清单，而不是报错。
			   这样"导入"在云端还没有任何备份时会正确地提示"没有备份文件"，而不是失败。 */
			if (e.getMessage() != null && e.getMessage().contains("404"))
			  return new ArrayList<String>();
			throw e;
		}
		List<String> out = new ArrayList<String>();
		for (String line : body.split("\\r?\\n")) {
			String n = line.trim();
			if (!n.isEmpty() && !out.contains(n))
			  out.add(n);
		}
		return out;
	}

	private static final class Resp {
		int code;
		String body = "";
	}

	private static Resp send(String method, String urlStr, String auth, String body,
			String contentType) throws IOException {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(urlStr).openConnection();
			conn.setRequestMethod(method);
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "tunVPN");
			if (auth != null)
			  conn.setRequestProperty("Authorization", auth);
			if (contentType != null)
			  conn.setRequestProperty("Content-Type", contentType);
			if (body != null) {
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				conn.setDoOutput(true);
				conn.setFixedLengthStreamingMode(bytes.length);
				OutputStream os = conn.getOutputStream();
				try {
					os.write(bytes);
				} finally {
					try {
						os.close();
					} catch (IOException ignore) {
					}
				}
			}
			Resp r = new Resp();
			r.code = conn.getResponseCode();
			InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
			r.body = readAll(in);
			TProxyService.log("WebDAV: " + method + " " + urlStr + " -> " + r.code);
			return r;
		} catch (IOException e) {
			/* 连不上 / 超时 / 证书问题的原文照带出去，别让用户只看到一句"失败"。 */
			String msg = e.getMessage() == null ? e.toString() : e.getMessage();
			TProxyService.log("WebDAV: " + method + " " + urlStr + " -> " + msg);
			throw new IOException(msg, e);
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}

	/* 错误描述：HTTP 码 + 服务器原话（截断）。“401/403 是账号问题、404 是地址问题”，
	   这两种最需要用户看到原文。 */
	private static String describe(Resp r) {
		String body = r.body == null ? "" : r.body.trim().replace('\n', ' ');
		if (body.length() > 160)
		  body = body.substring(0, 160) + "…";
		String hint = "";
		if (r.code == 401 || r.code == 403)
		  hint = "（账号或密码不对）";
		else if (r.code == 404)
		  hint = "（地址写错了，或该路径不存在）";
		return "HTTP " + r.code + hint + (body.isEmpty() ? "" : " · " + body);
	}

	private static String readAll(InputStream in) throws IOException {
		if (in == null)
		  return "";
		StringBuilder sb = new StringBuilder();
		BufferedReader reader = new BufferedReader(
			new InputStreamReader(in, StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = reader.readLine()) != null)
			  sb.append(line).append('\n');
		} finally {
			reader.close();
		}
		return sb.toString();
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return s;
		}
	}
}
