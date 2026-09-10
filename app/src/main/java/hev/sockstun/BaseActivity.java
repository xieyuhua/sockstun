/*
 ============================================================================
 Name        : BaseActivity.java
 Description : Applies the selected theme before every activity is created
               and wires up the shared bottom navigation bar.
 ============================================================================
 */

package hev.sockstun;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

public abstract class BaseActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		ThemeManager.applyTheme(this);
		super.onCreate(savedInstanceState);
	}

	/* Attach the horizontal bottom menu. Call it after setContentView()
	   from every screen that shows the bar (see @layout/bottom_nav). */
	protected void setupBottomNav(int selectedId) {
		final BottomNavigationView nav = (BottomNavigationView) findViewById(R.id.bottom_nav);
		if (nav == null)
		  return;

		nav.setSelectedItemId(selectedId);
		nav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
			@Override
			public boolean onNavigationItemSelected(MenuItem item) {
				int id = item.getItemId();
				if (id == selectedId)
				  return true;

				Class<?> target = activityFor(id);
				if (target == null)
				  return false;

				Intent intent = new Intent(BaseActivity.this, target);
				intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
					Intent.FLAG_ACTIVITY_SINGLE_TOP);
				startActivity(intent);
				/* No transition: the bar must feel like switching tabs
				   inside a single screen, not like opening a new page. */
				overridePendingTransition(0, 0);
				if (!(BaseActivity.this instanceof MainActivity))
				  finish();
				return true;
			}
		});
	}

	private static Class<?> activityFor(int itemId) {
		if (itemId == R.id.nav_home)
		  return MainActivity.class;
		if (itemId == R.id.nav_server)
		  return ServerActivity.class;
		if (itemId == R.id.nav_rules)
		  return RulesActivity.class;
		if (itemId == R.id.nav_apps)
		  return AppListActivity.class;
		if (itemId == R.id.nav_log)
		  return LogActivity.class;
		return null;
	}
}
