/*
 ============================================================================
 文件名  : AppListActivity.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2025 xyz
 说明    : 「部分应用」模式下的应用勾选页（只列出声明了联网权限的应用，可搜索）。
 ============================================================================
 */

package com.tunvpn;

import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Comparator;
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
import android.widget.Toast;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.content.pm.PackageInfo;


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
		/* 在构建列表时解析一次：loadIcon()/loadLabel() 会访问 PackageManager，
		   在 getView() 里调用太慢。 */
		public Drawable icon;

		public Package(PackageInfo info, boolean selected, String label, Drawable icon) {
			this.info = info;
			this.selected = selected;
			this.label = label;
			this.icon = icon;
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
			/* List.sort 本身就接受 Comparator<? super E>；以前的写法先强转成原始
			   (Comparator)，那是一次未经检查的转换，编译器会告警。 */
			allPackages.sort(cmp);
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

		/* 整体换入：因为列表是在 UI 线程之外拼装好的。 */
		public void setAll(List<Package> list) {
			allPackages.clear();
			allPackages.addAll(list);
			sortPackages();
			applyFilter(lastFilter);
		}

		/* 已选中的应用排前面，其余按字母序。 */
		public void sortPackages() {
			allPackages.sort(new Comparator<Package>() {
				@Override
				public int compare(Package a, Package b) {
					if (a.selected != b.selected)
					  return a.selected ? -1 : 1;
					return a.label.compareTo(b.label);
				}
			});
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View rowView = convertView;
			if (rowView == null) {
				LayoutInflater inflater = (LayoutInflater) getContext()
					.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				rowView = inflater.inflate(R.layout.appitem, parent, false);
			}
			Package pkg = getItem(position);
			((ImageView) rowView.findViewById(R.id.icon)).setImageDrawable(pkg.icon);
			((TextView) rowView.findViewById(R.id.name)).setText(pkg.label);
			((TextView) rowView.findViewById(R.id.package_name))
				.setText(pkg.info.packageName);
			((CompoundButton) rowView.findViewById(R.id.checked)).setChecked(pkg.selected);
			return rowView;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.applist);
		toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				finish();
			}
		});

		listView = (ListView) findViewById(R.id.list);
		listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
		listView.setEmptyView(findViewById(R.id.empty));

		prefs = new Preferences(this);
		adapter = new AppArrayAdapter(this);
		listView.setAdapter(adapter);

		EditText searchBox = (EditText) findViewById(R.id.search);
		loadApps();

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

	/* 遍历所有已安装应用（还要加载图标和名称）耗时足够把 onCreate 卡住，所以在工作
	   线程里构建，一次性换入。 */
	private void loadApps() {
		toolbar.setSubtitle(R.string.app_list_loading);
		new Thread(new Runnable() {
			@Override
			public void run() {
				final Set<String> apps = prefs.getApps();
				final PackageManager pm = getPackageManager();
				final List<Package> found = new ArrayList<Package>();
				for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
					if (info.packageName.equals(getPackageName()))
					  continue;
					if (info.requestedPermissions == null)
					  continue;
					if (!Arrays.asList(info.requestedPermissions)
							.contains(Manifest.permission.INTERNET))
					  continue;
					boolean selected = apps.contains(info.packageName);
					String label = info.applicationInfo.loadLabel(pm).toString();
					Drawable icon = info.applicationInfo.loadIcon(pm);
					found.add(new Package(info, selected, label, icon));
				}
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (isFinishing() || isDestroyed())
						  return;
						adapter.setAll(found);
						updateSubtitle();
					}
				});
			}
		}).start();
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
			/* 分应用白名单只在**建立**隧道时被读取（VpnService.Builder.
			   addAllowedApplication）。隧道已经起来时，只保存等于沿用旧作用域，
			   用户会觉得"选了没用"，直到重连。所以这里直接重建隧道，让新的应用范围
			   立刻生效。 */
			if (prefs.getEnable()) {
				startService(new Intent(this, TProxyService.class)
					.setAction(TProxyService.ACTION_RECONNECT));
				Toast.makeText(this, R.string.apps_applied_restart, Toast.LENGTH_LONG).show();
			}
		}

		super.onDestroy();
	}
}
