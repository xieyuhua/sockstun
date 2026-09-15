/*
 ============================================================================
 Name        : ConfigActivity.java
 Description : Preview and edit the clash config the core actually loads
               (files/config.yaml). It can regenerate it from the current
               settings, and a "use custom config" switch keeps the hand-edited
               file from being overwritten on the next connect.
 ============================================================================
*/

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ConfigActivity extends BaseActivity implements View.OnClickListener {
	/* Same name/location MihomoConfig writes and the core loads. */
	private static final String CONFIG_NAME = "config.yaml";

	private Preferences prefs;
	private EditText edittext_config;
	private SwitchMaterial switch_custom;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_config);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		edittext_config = (EditText) findViewById(R.id.config_text);
		switch_custom = (SwitchMaterial) findViewById(R.id.config_custom);
		switch_custom.setChecked(prefs.getCustomConfig());
		switch_custom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				prefs.setCustomConfig(isChecked);
				Toast.makeText(ConfigActivity.this,
					R.string.config_custom_toast, Toast.LENGTH_LONG).show();
			}
		});

		((Button) findViewById(R.id.config_regen)).setOnClickListener(this);
		((Button) findViewById(R.id.config_save)).setOnClickListener(this);
		((Button) findViewById(R.id.config_copy)).setOnClickListener(this);

		load();
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.config_regen)
		  regenerate();
		else if (id == R.id.config_save)
		  save();
		else if (id == R.id.config_copy)
		  copy();
	}

	private File file() {
		return new File(getFilesDir(), CONFIG_NAME);
	}

	private void load() {
		File f = file();
		if (!f.exists()) {
			edittext_config.setText("");
			Toast.makeText(this, R.string.config_none, Toast.LENGTH_LONG).show();
			return;
		}
		try {
			edittext_config.setText(read(f));
		} catch (IOException e) {
			Toast.makeText(this, getString(R.string.config_read_failed, e.getMessage()),
				Toast.LENGTH_LONG).show();
		}
	}

	/* Rebuild the file from the current subscription / rules / DNS settings. */
	private void regenerate() {
		try {
			File f = MihomoConfig.build(this, prefs);
			edittext_config.setText(read(f));
			Toast.makeText(this, R.string.config_regen_ok, Toast.LENGTH_SHORT).show();
		} catch (Throwable e) {
			Toast.makeText(this, getString(R.string.config_regen_failed, e.getMessage()),
				Toast.LENGTH_LONG).show();
		}
	}

	private void save() {
		try {
			write(file(), edittext_config.getText().toString());
			/* Without "use custom config" the next connect regenerates the file,
			   so say so rather than letting the edit look permanent. */
			Toast.makeText(this, prefs.getCustomConfig()
				? R.string.config_saved : R.string.config_saved_overwrite,
				prefs.getCustomConfig() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
		} catch (IOException e) {
			Toast.makeText(this, getString(R.string.config_save_failed, e.getMessage()),
				Toast.LENGTH_LONG).show();
		}
	}

	private void copy() {
		ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (cm != null)
		  cm.setPrimaryClip(ClipData.newPlainText(CONFIG_NAME,
			edittext_config.getText().toString()));
		Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
	}

	private static String read(File f) throws IOException {
		byte[] buf = new byte[(int) f.length()];
		FileInputStream in = new FileInputStream(f);
		try {
			int off = 0;
			while (off < buf.length) {
				int n = in.read(buf, off, buf.length - off);
				if (n < 0)
				  break;
				off += n;
			}
		} finally {
			in.close();
		}
		return new String(buf, StandardCharsets.UTF_8);
	}

	private static void write(File f, String text) throws IOException {
		FileOutputStream out = new FileOutputStream(f, false);
		try {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
	}
}
