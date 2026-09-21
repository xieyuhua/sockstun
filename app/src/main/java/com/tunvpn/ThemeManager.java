/*
 ============================================================================
 文件名  : ThemeManager.java
 说明    : 运行时主题（多套配色）选择。
 ============================================================================
*/

package com.tunvpn;

import android.app.Activity;

import androidx.annotation.StyleRes;

public final class ThemeManager {
	public static final int NEON = 0;
	public static final int CYAN = 1;
	public static final int PURPLE = 2;
	public static final int AMBER = 3;
	public static final int LIGHT = 4;

	private static final int[] STYLES = {
		R.style.Theme_TunVPN,        /* 霓虹绿 Neon Green */
		R.style.Theme_TunVPN_Cyan,   /* 赛博青 Cyber Cyan */
		R.style.Theme_TunVPN_Purple, /* 午夜紫 Midnight Purple */
		R.style.Theme_TunVPN_Amber,  /* 琥珀 Amber */
		R.style.Theme_TunVPN_Light,  /* 纸白 Paper Light */
	};

	private ThemeManager() {}

	public static int count() {
		return STYLES.length;
	}

	@StyleRes
	public static int styleRes(int id) {
		if (id < 0 || id >= STYLES.length)
		  id = NEON;
		return STYLES[id];
	}

	public static int nameRes(int id) {
		switch (id) {
		case CYAN:
			return R.string.theme_cyan;
		case PURPLE:
			return R.string.theme_purple;
		case AMBER:
			return R.string.theme_amber;
		case LIGHT:
			return R.string.theme_light;
		default:
			return R.string.theme_neon;
		}
	}

	/* 在每个 Activity 的 onCreate 里、super.onCreate() 之前调用。 */
	public static void applyTheme(Activity activity) {
		int id = Preferences.getTheme(activity);
		activity.setTheme(styleRes(id));
	}
}
