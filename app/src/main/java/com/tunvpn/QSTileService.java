/*
 ============================================================================
 文件名  : QSTileService.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2024 xyz
 说明    : 快捷设置磁贴（下拉通知栏的方块开关）。
 ============================================================================
*/

package com.tunvpn;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QSTileService extends TileService {
	@Override
	public void onStartListening() {
		super.onStartListening();
		updateTile();
	}

	@Override
	public void onClick() {
		super.onClick();

		Preferences prefs = new Preferences(this);
		if (prefs.getEnable()) {
			/* 已连接 → 断开隧道。 */
			prefs.setEnable(false);
			Intent intent = new Intent(this, TProxyService.class);
			startService(intent.setAction(TProxyService.ACTION_DISCONNECT));
			updateTile();
			return;
		}

		/* 未连接 → 启动隧道。 */
		Intent prepare = VpnService.prepare(this);
		if (prepare != null) {
			/* 还没授予 VPN 权限：交给 MainActivity 去申请，授权后再启动隧道。 */
			prefs.setEnable(true);
			Intent intent = new Intent(this, MainActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
					PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
				startActivityAndCollapse(pi);
			} else {
				startActivityAndCollapse(intent);
			}
			return;
		}

		prefs.setEnable(true);
		Intent intent = new Intent(this, TProxyService.class);
		intent.setAction(TProxyService.ACTION_CONNECT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		  startForegroundService(intent);
		else
		  startService(intent);
		updateTile();
	}

	private void updateTile() {
		Tile tile = getQsTile();
		if (tile == null)
		  return;
		boolean enable = new Preferences(this).getEnable();
		tile.setState(enable ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
		tile.setLabel(getString(R.string.app_name));
		tile.updateTile();
	}

	/* 请求系统刷新磁贴，使它在别处被切换后也能反映当前状态。 */
	public static void requestUpdate(Context context) {
		TileService.requestListeningState(context,
			new ComponentName(context, QSTileService.class));
	}
}
