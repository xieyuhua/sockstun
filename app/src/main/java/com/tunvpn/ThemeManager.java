/*
 ============================================================================
 Name        : ThemeManager.java
 Description : Runtime theme selection (multiple palettes).
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
		R.style.Theme_TunVPN,        /* Neon Green */
		R.style.Theme_TunVPN_Cyan,   /* Cyber Cyan */
		R.style.Theme_TunVPN_Purple, /* Midnight Purple */
		R.style.Theme_TunVPN_Amber,  /* Amber */
		R.style.Theme_TunVPN_Light,  /* Paper Light */
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

	/* Applied in every activity's onCreate, before super.onCreate(). */
	public static void applyTheme(Activity activity) {
		int id = Preferences.getTheme(activity);
		activity.setTheme(styleRes(id));
	}
}
