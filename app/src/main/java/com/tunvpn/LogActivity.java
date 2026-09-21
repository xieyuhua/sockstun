/*
 ============================================================================
 文件名  : LogActivity.java
 说明    : 启动 / 运行日志查看器。开启"自动刷新"时持续跟随文件尾部；一碰文本就暂停，
           这样选中的内容不会被冲掉；整份日志可一键复制。
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
	/* 上一次交给 TextView 的文本。没变化就不重新 setText()：setText() 会清掉选中，
	   这正是"页面边滚动边复制不了"的原因。 */
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

		/* 自动刷新（相当于 tail -f）。用户一碰文本就关掉它，免得下一次刷新把长按选中的
		   内容冲掉。 */
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

	/* 重新读取日志；自动刷新开启时把视图钉在最新一行。只有内容真的变了才重新赋值。 */
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

	/* 隧道的日志文件。即使关闭了"记录日志"，启动诊断也会写进这里，所以致命错误永远
	   看得到。 */
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

	/* 把整份日志放进剪贴板：不用再跟长文本里的选择手柄较劲。 */
	private void copyLog() {
		String text = textview_log.getText().toString();
		if (text.isEmpty())
		  text = readLog();
		ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (cm != null)
		  cm.setPrimaryClip(ClipData.newPlainText(LOG_NAME, text));
		Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
	}

	/* 清空日志文件（隧道停止时才会真正生效）。 */
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
