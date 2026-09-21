/*
 ============================================================================
 文件名  : SubscribeConfigActivity.java
 说明    : 管理 clash.yml 订阅地址：新增 / 编辑 / 删除 / 启用。启用的订阅会被**合并**
           成一个节点池（当前实现没有"默认订阅"的概念）。
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

import com.tunvpn.ClashNode;
import com.tunvpn.ClashParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SubscribeConfigActivity extends BaseActivity {
	private Preferences prefs;
	private MaterialButton buttonUpdate;
	private ListView listview;
	private TextView textview_empty;
	private List<Subscription> subs = new ArrayList<Subscription>();
	private SubAdapter adapter;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_subscribe_config);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		listview = (ListView) findViewById(R.id.subs_list);
		textview_empty = (TextView) findViewById(R.id.subs_empty);

		buttonUpdate = (MaterialButton) findViewById(R.id.subs_update);
		buttonUpdate.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				fetchAll();
			}
		});

		adapter = new SubAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		((MaterialButton) findViewById(R.id.subs_add)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showEditDialog(null);
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		load();
	}

	private void load() {
		subs.clear();
		subs.addAll(prefs.getSubscriptions());
		adapter.notifyDataSetChanged();
	}

	private void confirmDelete(final Subscription s) {
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.subs_delete_confirm, s.label()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					List<Subscription> list = prefs.getSubscriptions();
					for (int i = 0; i < list.size(); i++) {
						if (s.id.equals(list.get(i).id)) {
							list.remove(i);
							break;
						}
					}
					prefs.setSubscriptions(list);
					/* 一并丢掉该订阅已拉取的 YAML 与节点缓存，否则用同一个 id 重新
					   创建时会继承旧内容。 */
					prefs.clearSubCache(s.id);
					if (s.id.equals(prefs.getActiveSubId())) {
						prefs.setActiveSubId("");
						prefs.setSubUrl("");
						prefs.setSubSelected("");
					}
					load();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* 新增（existing == null）或编辑一条订阅。 */
	private void showEditDialog(final Subscription existing) {
		final boolean add = (existing == null);

		LinearLayout container = new LinearLayout(this);
		container.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		container.setPadding(pad, pad / 2, pad, 0);

		final EditText editName = new EditText(this);
		editName.setHint(R.string.subs_name);
		editName.setSingleLine(true);
		final EditText editUrl = new EditText(this);
		editUrl.setHint(R.string.subs_url);
		editUrl.setSingleLine(true);
		editUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
		if (!add) {
			editName.setText(existing.name);
			editUrl.setText(existing.url);
		}
		final SwitchMaterial switchEnabled = new SwitchMaterial(this);
		switchEnabled.setText(R.string.subs_enabled);
		/* 新增的订阅默认启用；已有的保持它原来的开关状态。 */
		switchEnabled.setChecked(add || existing.enabled);
		container.addView(editName);
		container.addView(editUrl);
		container.addView(switchEnabled);

		new AlertDialog.Builder(this)
			.setTitle(add ? R.string.subs_add : R.string.subs_edit)
			.setView(container)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					String url = editUrl.getText().toString().trim();
					if (url.isEmpty()) {
						Toast.makeText(SubscribeConfigActivity.this,
							R.string.subs_url_required, Toast.LENGTH_SHORT).show();
						return;
					}
					String name = editName.getText().toString().trim();
					List<Subscription> list = prefs.getSubscriptions();
					if (add) {
						list.add(new Subscription(Subscription.newId(), name, url, switchEnabled.isChecked()));
					} else {
						for (int i = 0; i < list.size(); i++) {
							if (existing.id.equals(list.get(i).id)) {
								list.set(i, new Subscription(existing.id, name, url, switchEnabled.isChecked()));
								break;
							}
						}
					}
					prefs.setSubscriptions(list);
					load();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private class SubAdapter extends ArrayAdapter<Subscription> {
		private final android.view.LayoutInflater inflater;

		SubAdapter() {
			super(SubscribeConfigActivity.this, R.layout.subscriptionitem, subs);
			inflater = getLayoutInflater();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null)
				convertView = inflater.inflate(R.layout.subscriptionitem, parent, false);
			final Subscription s = getItem(position);
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			SwitchMaterial sw = (SwitchMaterial) convertView.findViewById(R.id.item_enabled);
			Button edit = (Button) convertView.findViewById(R.id.item_edit);
			Button delete = (Button) convertView.findViewById(R.id.item_delete);

			name.setText(s.label());
			detail.setText(buildDetail(s));

			/* 直接在列表里切换启用状态，不必打开对话框。 */
			sw.setOnCheckedChangeListener(null);
			sw.setChecked(s.enabled);
			sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean isChecked) {
					s.enabled = isChecked;
					prefs.setSubscriptions(subs);
					detail.setText(buildDetail(s));
				}
			});

			edit.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showEditDialog(s);
				}
			});
			delete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmDelete(s);
				}
			});
			return convertView;
		}
	}

	/* 详情行：已缓存的节点数量，外加"已停用"标记，不展开也能看出状态。 */
	private String buildDetail(Subscription s) {
		int cached = ClashNode.decode(prefs.getSubNodes(s.id)).size();
		String detailStr = cached > 0
			? getString(R.string.subs_cached, s.url, cached)
			: getString(R.string.subs_not_fetched, s.url);
		if (!s.enabled)
			detailStr += "  ·  " + getString(R.string.subs_disabled);
		return detailStr;
	}

	/* 拉取每条（已启用的）订阅的 clash.yml，解析后按订阅分别存下原始 YAML 与节点列表。
	   停用的会跳过，与合并逻辑保持一致。拉完刷新列表，让缓存节点数跟着更新。 */
	private void fetchAll() {
		final List<Subscription> subs = prefs.getSubscriptions();
		if (subs.isEmpty()) {
			Toast.makeText(this, R.string.subs_none, Toast.LENGTH_SHORT).show();
			return;
		}
		buttonUpdate.setEnabled(false);
		buttonUpdate.setText(R.string.sub_fetching);
		new Thread(new Runnable() {
			@Override
			public void run() {
				int total = 0;
				String firstError = null;
				try {
					for (Subscription sub : subs) {
						/* 不会合并进来的订阅，就没必要去拉它。 */
						if (!sub.enabled)
						  continue;
						String url = sub.url == null ? "" : sub.url.trim();
						if (url.isEmpty())
						  continue;
						try {
							String yaml = ClashParser.decodeRaw(download(url));
							List<ClashNode> parsed = ClashParser.parseAll(yaml);
							for (ClashNode n : parsed)
							  n.subId = sub.id;
							prefs.setSubRaw(sub.id, yaml);
							prefs.setSubNodes(sub.id, ClashNode.encode(parsed));
							total += parsed.size();
						} catch (Exception e) {
							if (firstError == null)
							  firstError = sub.label() + ": " + e.getMessage();
						}
					}
				} finally {
					final int fetched = total;
					final String error = firstError;
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if (isFinishing() || isDestroyed())
							  return;
							buttonUpdate.setEnabled(true);
							buttonUpdate.setText(R.string.sub_update);
							load();
							if (fetched > 0)
							  Toast.makeText(SubscribeConfigActivity.this,
								getString(R.string.sub_fetched, fetched),
								Toast.LENGTH_SHORT).show();
							else if (error != null)
							  Toast.makeText(SubscribeConfigActivity.this,
								getString(R.string.sub_fetch_failed, error),
								Toast.LENGTH_LONG).show();
							else
							  Toast.makeText(SubscribeConfigActivity.this,
								R.string.sub_no_nodes, Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	private String download(String urlStr) throws Exception {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(urlStr);
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(10000);
			conn.setReadTimeout(10000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "tunVPN");
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
			  throw new Exception("HTTP " + code);
			StringBuilder sb = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(
					conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null)
				  sb.append(line).append('\n');
			}
			return sb.toString();
		} finally {
			if (conn != null)
			  conn.disconnect();
		}
	}
}
