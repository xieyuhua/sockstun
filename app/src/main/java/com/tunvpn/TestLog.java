/*
 ============================================================================
 Name        : TestLog.java
 Description : The app's runtime log file (cache/tproxy.log) - the very file
               the 日志 page tails and copies. It lives in its own class so
               code that holds no Service/Activity reference can still record
               what the latency test did:

                 * ClashApiServer  runs inside the :native process
                 * CoreTestHost    runs inside the app process
                 * TProxyService.appendLog() delegates here as well

               Both processes resolve the same cache dir, so everything lands
               in one file the user can copy out. Every write is best-effort:
               logging must never break a test or the tunnel.
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
	/* Set once per process by TestLog.init(). */
	private static volatile File file;

	private TestLog() { }

	/* Bind the log file. Called from TProxyService.onCreate (:native) and from
	   the Activities (app process); whichever runs first wins, and both
	   processes resolve the same path. The first call is expected BEFORE any
	   TProxyService.appendLog(), so removing a stale file still works. */
	static void init(Context ctx) {
		if (file != null || ctx == null)
		  return;
		try {
			file = new File(ctx.getCacheDir(), "tproxy.log");
		} catch (Throwable ignore) {
		}
	}

	/* The bound log file, or null before init(). */
	static File file() {
		return file;
	}

	/* Append one timestamped line ("HH:mm:ss msg"), same shape as the tunnel's
	   own log lines so the two read as one stream. Also mirrored to logcat
	   under the "tunvpn" tag, which makes `adb logcat` usable for diagnosis. */
	static void append(String s) {
		if (s == null)
		  return;
		Log.d(TAG, s);
		File f = file;
		if (f == null)
		  return;
		synchronized (TestLog.class) {
			try {
				FileOutputStream fos = new FileOutputStream(f, true);
				String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " ";
				fos.write((ts + s + "\n").getBytes("UTF-8"));
				fos.close();
			} catch (Throwable ignore) {
			}
		}
	}
}
