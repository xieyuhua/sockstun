/*
 ============================================================================
 Name        : LogActivity.java
 Description : Startup / runtime log viewer. Tails the file while "auto refresh"
               is on; touching the text pauses it so a selection survives, and
               the whole log can be copied in one tap.
 ============================================================================
*/

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
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
	private static final long REFRESH_INTERVAL = 1500;

	private Handler handler;
	private Runnable refresher;
	private TextView textview_log;
	private ScrollView scrollview;
	private SwitchMaterial log_switch;
	private SwitchMaterial log_follow;
	private Preferences prefs;
	/* Last text handed to the TextView, so a tick that changed nothing does not
	   re-set it: setText() drops the selection, which is what made copying
	   impossible while the page was tailing. */
	private String shownText = null;

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
		log_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				prefs.setLogEnabled(isChecked);
				Toast.makeText(LogActivity.this,
					getString(R.string.log_toggle_hint), Toast.LENGTH_SHORT).show();
			}
		});

		textview_log = (TextView) findViewById(R.id.log_text);
		scrollview = (ScrollView) findViewById(R.id.log_scroll);
		((Button) findViewById(R.id.refresh)).setOnClickListener(this);
		((Button) findViewById(R.id.clear)).setOnClickListener(this);
		((Button) findViewById(R.id.copy)).setOnClickListener(this);

		/* Auto refresh (tail -f). Turned off the moment the user touches the
		   text, so a long-press selection is not wiped by the next tick. */
		log_follow = (SwitchMaterial) findViewById(R.id.log_follow);
		log_follow.setChecked(true);
		log_follow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (isChecked) {
					Toast.makeText(LogActivity.this,
						R.string.log_follow_on, Toast.LENGTH_SHORT).show();
					refresh();
				}
			}
		});
		textview_log.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				if (log_follow.isChecked()) {
					log_follow.setChecked(false);
					Toast.makeText(LogActivity.this,
						R.string.log_paused_hint, Toast.LENGTH_SHORT).show();
				}
				return false;
			}
		});

		handler = new Handler(Looper.getMainLooper());
		refresher = new Runnable() {
			@Override
			public void run() {
				if (log_follow.isChecked())
				  refresh();
				handler.postDelayed(this, REFRESH_INTERVAL);
			}
		};
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh();
		handler.postDelayed(refresher, REFRESH_INTERVAL);
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
		} else if (id == R.id.copy) {
			copyLog();
		}
	}

	/* Re-read the log and, while auto refresh is on, keep the view pinned to the
	   newest line. The text is only reassigned when it actually changed. */
	private void refresh() {
		String text = readLog();
		if (!text.equals(shownText)) {
			shownText = text;
			textview_log.setText(text);
		}
		if (log_follow.isChecked()) {
			scrollview.post(new Runnable() {
				@Override
				public void run() {
					scrollview.fullScroll(View.FOCUS_DOWN);
				}
			});
		}
	}

	/* The tunnel's log file. Startup diagnostics are written there even when
	   logging is disabled, so a fatal error is always visible. */
	private String readLog() {
		File log = new File(getCacheDir(), LOG_NAME);
		StringBuilder sb = new StringBuilder();
		if (!prefs.getLogEnabled())
		  sb.append(getString(R.string.log_disabled)).append("\n\n");
		if (!log.exists()) {
			sb.append(getString(R.string.log_empty));
			return sb.toString();
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
			String line;
			while ((line = reader.readLine()) != null)
			  sb.append(line).append('\n');
		} catch (IOException e) {
			sb.append("read error: ").append(e.getMessage());
		}
		return sb.toString();
	}

	/* Put the whole log on the clipboard, so it can be pasted out without
	   fighting the selection handles on a long TextView. */
	private void copyLog() {
		String text = textview_log.getText().toString();
		if (text.isEmpty())
		  text = readLog();
		ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (cm != null)
		  cm.setPrimaryClip(ClipData.newPlainText(LOG_NAME, text));
		Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
	}

	/* Truncate the log file (effective when the tunnel is stopped). */
	private void clearLog() {
		File log = new File(getCacheDir(), LOG_NAME);
		try (RandomAccessFile raf = new RandomAccessFile(log, "rw")) {
			raf.setLength(0);
		} catch (IOException e) {
			log.delete();
		}
		shownText = null;
	}
}
