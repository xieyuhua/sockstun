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
	   from every screen that shows the bar (see @layout/bottom_nav).

	   Both callbacks are wired on purpose. BottomNavigationView decides
	   between "selected" and "reselected" using its own internal
	   selectedItemId, which is not reliably updated by setSelectedItemId()
	   below - it can stay on the first menu entry. Without the reselected
	   callback, tapping Home from another tab is treated as re-tapping the
	   current tab and never reaches onNavigationItemSelected(). */
	protected void setupBottomNav(int selectedId) {
		final BottomNavigationView nav = (BottomNavigationView) findViewById(R.id.bottom_nav);
		if (nav == null)
		  return;

		/* Mark the current tab first, so registering the listeners does not
		   immediately fire a navigation. */
		nav.setSelectedItemId(selectedId);

		nav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
			@Override
			public boolean onNavigationItemSelected(MenuItem item) {
				return navigateTo(item.getItemId());
			}
		});
		nav.setOnItemReselectedListener(new NavigationBarView.OnItemReselectedListener() {
			@Override
			public void onNavigationItemReselected(MenuItem item) {
				navigateTo(item.getItemId());
			}
		});
	}

	private boolean navigateTo(int itemId) {
		Class<?> target = activityFor(itemId);
		if (target == null)
		  return false;
		/* Compare activity types, not the id passed to setupBottomNav():
		   that is what actually decides whether we are already there. */
		if (getClass() == target)
		  return true;

		Intent intent = new Intent(this, target);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
			Intent.FLAG_ACTIVITY_SINGLE_TOP);
		startActivity(intent);
		/* No transition: the bar must feel like switching tabs inside a
		   single screen, not like opening a new page. */
		overridePendingTransition(0, 0);
		if (!(this instanceof MainActivity))
		  finish();
		return true;
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
