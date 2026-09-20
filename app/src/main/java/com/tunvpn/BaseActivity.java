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
		/* Bind the shared log file (cache/tproxy.log) for this process so code
		   without a Service handle - CoreTestHost and the latency test running
		   in the app process - can write into the same log the 日志 page shows.
		   TProxyService does the same in :native. */
		TestLog.init(this);
	}
}
