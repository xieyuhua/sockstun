/*
 ============================================================================
 文件名  : TestLog.java
 说明    : App 的运行时日志文件（cache/tproxy.log）—— 就是「日志」页滚动显示 / 复制
           的那份文件。单独成类，是为了让没有 Service/Activity 句柄的代码也能记录
           测速过程：

             * ClashApiServer  运行在 :native 进程
             * CoreTestHost    运行在 App 进程
             * TProxyService.appendLog() 也委托到这里

           两个进程解析出的 cache 目录相同，所以所有内容都落在同一个文件里，用户可
           直接复制出来。每次写入都是"尽力而为"：写日志绝不能影响测速或隧道本身。
 ============================================================================
*/

package com.tunvpn;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class TestLog {
	private static final String TAG = "tunvpn";
	/* 每个进程由 TestLog.init() 设置一次。 */
	private static volatile File file;

	/* 每个线程一个格式化器：SimpleDateFormat 不是线程安全的，而一轮测速要写几千行，
	   原来每行都 new 一个既慢又产生大量垃圾。 */
	private static final ThreadLocal<SimpleDateFormat> TS =
		new ThreadLocal<SimpleDateFormat>() {
			@Override protected SimpleDateFormat initialValue() {
				return new SimpleDateFormat("HH:mm:ss", Locale.US);
			}
		};

	private TestLog() { }

	/* 绑定日志文件。由 TProxyService.onCreate（:native）与各 Activity（App 进程）调用；
	   谁先执行谁生效，两个进程解析出的路径相同。首次调用要早于任何
	   TProxyService.appendLog()，这样"删除上一轮的旧文件"才有效。 */
	static void init(Context ctx) {
		if (file != null || ctx == null)
		  return;
		try {
			file = new File(ctx.getCacheDir(), "tproxy.log");
		} catch (Throwable ignore) {
		}
	}

	/* 已绑定的日志文件，init() 之前为 null。 */
	static File file() {
		return file;
	}

	/* 追加一行带时间戳的记录（"HH:mm:ss 内容"），格式与隧道自身的日志行一致，
	   这样两者读起来像一条流。同时镜像到 logcat（tag "tunvpn"），便于 `adb logcat`
	   排查。 */
	static void append(String s) {
		if (s == null)
		  return;
		Log.d(TAG, s);
		File f = file;
		if (f == null)
		  return;
		synchronized (TestLog.class) {
			/* try-with-resources：写入抛异常时（磁盘满、cache 目录被清）原来每次都会
			   泄漏一个文件描述符。 */
			try (FileOutputStream fos = new FileOutputStream(f, true)) {
				String ts = TS.get().format(new Date()) + " ";
				fos.write((ts + s + "\n").getBytes("UTF-8"));
			} catch (Throwable ignore) {
				/* 写日志绝不能影响测速或隧道 */
			}
		}
	}
}
