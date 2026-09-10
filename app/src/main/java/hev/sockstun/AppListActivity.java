/*
 ============================================================================
 Name        : AppListActivity.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 xyz
 Description : App List Activity
 ============================================================================
 */

package hev.sockstun;

import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Collections;
import java.util.ArrayList;

import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;

import com.google.android.material.appbar.MaterialToolbar;

public class AppListActivity extends BaseActivity {
	private Preferences prefs;
	private AppArrayAdapter adapter;
	private ListView listView;
	private MaterialToolbar toolbar;
	private boolean isChanged = false;

	private class Package {
		public PackageInfo info;
		public boolean selected;
		public String label;

		public Package(PackageInfo info, boolean selected, String label) {
			this.info = info;
			this.selected = selected;
			this.label = label;
		}
	}

	private class AppArrayAdapter extends ArrayAdapter<Package> {
		private final List<Package> allPackages = new ArrayList<Package>();
		private final List<Package> filteredPackages = new ArrayList<Package>();
		private String lastFilter = "";

		public AppArrayAdapter(Context context) {
			super(context, R.layout.appitem);
		}

		@Override
		public void add(Package pkg) {
			allPackages.add(pkg);
			if (matchesFilter(pkg, lastFilter))
				filteredPackages.add(pkg);
			notifyDataSetChanged();
		}

		@Override
		public void clear() {
			allPackages.clear();
			filteredPackages.clear();
			notifyDataSetChanged();
		}

		@Override
		public void sort(Comparator<? super Package> cmp) {
			Collections.sort(allPackages, (Comparator) cmp);
			applyFilter(lastFilter);
		}

		@Override
		public int getCount() {
			return filteredPackages.size();
		}

		@Override
		public Package getItem(int position) {
			return filteredPackages.get(position);
		}

		public List<Package> getAllPackages() {
			return allPackages;
		}

		public int getSelectedCount() {
			int count = 0;
			for (Package pkg : allPackages) {
				if (pkg.selected)
				  count++;
			}
			return count;
		}

		private boolean matchesFilter(Package pkg, String filter) {
			if (filter == null || filter.length() == 0)
				return true;
			return pkg.label.toLowerCase().contains(filter.toLowerCase());
		}

		public void applyFilter(String filter) {
			lastFilter = filter != null ? filter : "";
			filteredPackages.clear();
			if (lastFilter.length() == 0) {
				filteredPackages.addAll(allPackages);
			} else {
				String f = lastFilter.toLowerCase();
				for (Package p : allPackages) {
					if (p.label != null && p.label.toLowerCase().contains(f))
						filteredPackages.add(p);
				}
			}
			notifyDataSetChanged();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			LayoutInflater inflater = (LayoutInflater) getContext()
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View rowView = inflater.inflate(R.layout.appitem, parent, false);
			ImageView imageView = (ImageView) rowView.findViewById(R.id.icon);
			TextView textView = (TextView) rowView.findViewById(R.id.name);
			TextView packageView = (TextView) rowView.findViewById(R.id.package_name);
			CompoundButton checkBox = (CompoundButton) rowView.findViewById(R.id.checked);

			Package pkg = getItem(position);
			PackageManager pm = getContext().getPackageManager();
			ApplicationInfo appinfo = pkg.info.applicationInfo;
			imageView.setImageDrawable(appinfo.loadIcon(pm));
			textView.setText(appinfo.loadLabel(pm).toString());
			packageView.setText(pkg.info.packageName);
			checkBox.setChecked(pkg.selected);

			return rowView;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.applist);
		toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		setupBottomNav(R.id.nav_apps);

		listView = (ListView) findViewById(R.id.list);
		listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
		listView.setEmptyView(findViewById(R.id.empty));

		prefs = new Preferences(this);
		Set<String> apps = prefs.getApps();
		PackageManager pm = getPackageManager();
		adapter = new AppArrayAdapter(this);

		for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
			if (info.packageName.equals(getPackageName()))
			  continue;
			if (info.requestedPermissions == null)
			  continue;
			if (!Arrays.asList(info.requestedPermissions).contains(Manifest.permission.INTERNET))
			  continue;
			boolean selected = apps.contains(info.packageName);
			String label = info.applicationInfo.loadLabel(pm).toString();
			Package pkg = new Package(info, selected, label);
			adapter.add(pkg);
		}

		EditText searchBox = (EditText) findViewById(R.id.search);

		adapter.sort(new Comparator<Package>() {
			public int compare(Package a, Package b) {
				if (a.selected != b.selected)
				  return a.selected ? -1 : 1;
				return a.label.compareTo(b.label);
			}
		});

		listView.setAdapter(adapter);
		updateSubtitle();
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				Package pkg = adapter.getItem(position);
				pkg.selected = !pkg.selected;
				CompoundButton checkbox = (CompoundButton) view.findViewById(R.id.checked);
				if (checkbox != null)
					checkbox.setChecked(pkg.selected);
				isChanged = true;
				updateSubtitle();
			}
		});

		searchBox.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				adapter.applyFilter(s.toString());
			}

			@Override
			public void afterTextChanged(Editable s) { }
		});
	}

	private void updateSubtitle() {
		toolbar.setSubtitle(getString(R.string.apps_selected, adapter.getSelectedCount()));
	}

	@Override
	protected void onDestroy() {
		if (isChanged) {
			Set<String> apps = new HashSet<String>();

			for (Package pkg : adapter.getAllPackages()) {
				if (pkg.selected)
				  apps.add(pkg.info.packageName);
			}

			prefs.setApps(apps);
		}

		super.onDestroy();
	}
}
