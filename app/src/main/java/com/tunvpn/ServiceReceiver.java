/*
 ============================================================================
 Name        : ServiceReceiver.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : ServiceReceiver
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
		/* A broadcast intent may carry no action, so compare the other way
		   round instead of dereferencing getAction() unguarded. */
		if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			Preferences prefs = new Preferences(context);

			/* Auto-start */
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
