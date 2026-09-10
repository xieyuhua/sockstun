/*
 ============================================================================
 Name        : SettingsActivity.java
 Description : Settings page: theme picker, logging and app version info.
 ============================================================================
 */

package hev.sockstun;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private RadioGroup theme_group;
	private CompoundButton switch_log;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_settings);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		setupBottomNav(R.id.nav_settings);

		theme_group = (RadioGroup) findViewById(R.id.theme_group);
		buildThemeOptions();

		switch_log = (CompoundButton) findViewById(R.id.settings_log_switch);
		switch_log.setChecked(prefs.getLogEnabled());
		switch_log.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton button, boolean checked) {
				prefs.setLogEnabled(checked);
				Toast.makeText(SettingsActivity.this,
					getString(R.string.log_toggle_hint), Toast.LENGTH_SHORT).show();
			}
		});

		((MaterialButton) findViewById(R.id.settings_view_log)).setOnClickListener(this);

		fillAbout();
	}

	/* One radio per palette, built from ThemeManager so adding a theme only
	   means touching ThemeManager + strings. */
	private void buildThemeOptions() {
		final int current = prefs.getTheme();
		int padding = (int) (8 * getResources().getDisplayMetrics().density);

		theme_group.removeAllViews();
		for (int i = 0; i < ThemeManager.count(); i++) {
			RadioButton button = new RadioButton(this);
			button.setText(ThemeManager.nameRes(i));
			button.setId(View.generateViewId());
			button.setTag(Integer.valueOf(i));
			button.setChecked(i == current);
			button.setPadding(0, padding, 0, padding);
			theme_group.addView(button);
		}

		/* Registered after the initial check, so building the list does not
		   look like the user picked something. */
		theme_group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(RadioGroup group, int checkedId) {
				View view = group.findViewById(checkedId);
				if (view == null || view.getTag() == null)
				  return;
				int id = ((Integer) view.getTag()).intValue();
				if (prefs.getTheme() == id)
				  return;
				prefs.setTheme(id);
				/* Recreate now for an instant preview; the other screens
				   repaint themselves via BaseActivity.onResume(). */
				recreate();
			}
		});
	}

	private void fillAbout() {
		TextView app = (TextView) findViewById(R.id.about_app);
		TextView version = (TextView) findViewById(R.id.about_version);
		TextView build = (TextView) findViewById(R.id.about_build);
		TextView pkg = (TextView) findViewById(R.id.about_package);

		app.setText(R.string.app_name);
		pkg.setText(getPackageName());
		try {
			PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
			version.setText(info.versionName);
			long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
				? info.getLongVersionCode() : info.versionCode;
			build.setText(Long.toString(code));
		} catch (Exception e) {
			version.setText("-");
			build.setText("-");
		}
	}

	@Override
	public void onClick(View view) {
		if (view.getId() == R.id.settings_view_log)
		  startActivity(new Intent(this, LogActivity.class));
	}
}
