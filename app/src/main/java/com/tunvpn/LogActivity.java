/*
 ============================================================================
 Name        : LogActivity.java
 Description : Startup / runtime log viewer
 ============================================================================
 */

package com.tunvpn;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;

public class LogActivity extends BaseActivity implements View.OnClickListener {
	private static final String LOG_NAME = "tproxy.log";
	private Handler handler;
	private Runnable refresher;
	private TextView textview_log;
	private ScrollView scrollview;
	private SwitchMaterial log_switch;
	private Preferences prefs;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_log);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		prefs = new Preferences(this);

		log_switch = (SwitchMaterial) findViewById(R.id.log_switch);
		log_switch.setChecked(prefs.getLogEnabled());
		log_switch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
				prefs.setLogEnabled(isChecked);
				Toast.makeText(LogActivity.this,
					getString(R.string.log_toggle_hint), Toast.LENGTH_SHORT).show();
			}
		});

		textview_log = (TextView) findViewById(R.id.log_text);
		scrollview = (ScrollView) findViewById(R.id.log_scroll);
		((Button) findViewById(R.id.refresh)).setOnClickListener(this);
		((Button) findViewById(R.id.clear)).setOnClickListener(this);

		handler = new Handler(Looper.getMainLooper());
		refresher = new Runnable() {
			@Override
			public void run() {
				refresh();
				handler.postDelayed(this, 1500);
			}
		};
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
		handler.postDelayed(refresher, 1500);
	}

	@Override
	protected void onPause() {
		super.onPause();
		handler.removeCallbacks(refresher);
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.refresh) {
			refresh();
		} else if (id == R.id.clear) {
			clearLog();
			refresh();
		}
	}

	/* Read the tunnel's log file. Startup diagnostics are written there even
	   when logging is disabled, so a fatal error is always visible. */
	private void refresh() {
		File log = new File(getCacheDir(), LOG_NAME);
		StringBuilder sb = new StringBuilder();
		if (!prefs.getLogEnabled())
		  sb.append(getString(R.string.log_disabled)).append("\n\n");
		if (!log.exists()) {
			sb.append(getString(R.string.log_empty));
			textview_log.setText(sb.toString());
			return;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
			String line;
			while ((line = reader.readLine()) != null)
			  sb.append(line).append('\n');
		} catch (IOException e) {
			sb.append("read error: ").append(e.getMessage());
		}
		textview_log.setText(sb.toString());
		scrollview.post(new Runnable() {
			@Override
			public void run() {
				scrollview.fullScroll(View.FOCUS_DOWN);
			}
		});
	}

	/* Truncate the log file (effective when the tunnel is stopped). */
	private void clearLog() {
		File log = new File(getCacheDir(), LOG_NAME);
		try {
			new RandomAccessFile(log, "rw").setLength(0);
		} catch (IOException e) {
			log.delete();
		}
	}
}
