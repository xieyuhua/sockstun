/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : The "Settings" tab: per-app list, log, theme and version.
               Each entry opens its own detail (page or dialog).
 ============================================================================
 */

package hev.sockstun;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_settings);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		int[] ids = {
			R.id.settings_apps, R.id.settings_log,
			R.id.settings_theme, R.id.settings_version,
		};
		for (int id : ids)
		  ((MaterialButton) findViewById(id)).setOnClickListener(this);
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.settings_apps)
		  startActivity(new Intent(this, AppListActivity.class));
		else if (id == R.id.settings_log)
		  startActivity(new Intent(this, LogActivity.class));
		else if (id == R.id.settings_theme)
		  showThemeDialog();
		else if (id == R.id.settings_version)
		  showVersionDialog();
	}

	/* Theme picker: choose one of the bundled palettes, then recreate so the
	   new theme is picked up. */
	private void showThemeDialog() {
		final int current = prefs.getTheme();
		final String[] names = new String[ThemeManager.count()];
		for (int i = 0; i < names.length; i++)
			names[i] = getString(ThemeManager.nameRes(i));

		new AlertDialog.Builder(this)
			.setTitle(R.string.theme)
			.setSingleChoiceItems(names, current, null)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
					if (sel >= 0 && sel != current) {
						prefs.setTheme(sel);
						recreate();
					}
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void showVersionDialog() {
		String name = "";
		int code = 0;
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			name = pi.versionName;
			code = pi.versionCode;
		} catch (Exception e) {
		}

		new AlertDialog.Builder(this)
			.setTitle(R.string.version_info)
			.setMessage(getString(R.string.version_detail, name, code))
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}
}
