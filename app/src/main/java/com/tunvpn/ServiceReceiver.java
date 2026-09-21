/*
 ============================================================================
 文件名  : ServiceReceiver.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2023 xyz
 说明    : 开机自启广播接收器。
 ============================================================================
*/

package com.tunvpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class ServiceReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		/* 广播的 action 可能为空，所以反向比较（常量.equals(getAction())），
		   不要直接对 intent.getAction() 取值。 */
		if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			Preferences prefs = new Preferences(context);

			/* 上次是连接状态 → 开机后自动恢复隧道。 */
			if (prefs.getEnable()) {
				Intent i = VpnService.prepare(context);
				if (i != null) {
					i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					context.startActivity(i);
				}
				i = new Intent(context, TProxyService.class);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					context.startForegroundService(i.setAction(TProxyService.ACTION_CONNECT));
				} else {
					context.startService(i.setAction(TProxyService.ACTION_CONNECT));
				}
			}
		}
	}
}
