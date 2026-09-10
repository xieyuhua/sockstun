/*
 ============================================================================
 Name        : BaseActivity.java
 Description : Applies the selected theme before every activity is created.
 ============================================================================
 */

package com.tunvpn;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		ThemeManager.applyTheme(this);
		super.onCreate(savedInstanceState);
	}
}
