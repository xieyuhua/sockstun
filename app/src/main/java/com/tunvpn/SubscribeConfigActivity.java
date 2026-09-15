/*
 ============================================================================
 Name        : SubscribeConfigActivity.java
 Description : Manage the clash.yml subscriptions: add, edit, delete and pick
               which one is the default (the one the subscribe page fetches).
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
					/* Drop this subscription's fetched YAML + node cache,
					   otherwise a recreate with the same id would inherit it. */
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

	/* Add (existing == null) or edit one subscription. */
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
		/* New subscriptions default to enabled; existing ones keep their flag. */
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
			Button edit = (Button) convertView.findViewById(R.id.item_edit);
			Button delete = (Button) convertView.findViewById(R.id.item_delete);

			name.setText(s.label());
			/* Which subscriptions still need a fetch becomes obvious here. */
			int cached = ClashNode.decode(prefs.getSubNodes(s.id)).size();
			String detailStr = cached > 0
				? getString(R.string.subs_cached, s.url, cached)
				: getString(R.string.subs_not_fetched, s.url);
			if (!s.enabled)
				detailStr += "  ·  " + getString(R.string.subs_disabled);
			detail.setText(detailStr);

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

	/* Pull every (enabled) subscription's clash.yml, parse it and store the raw
	   YAML + node list per subscription. Disabled ones are skipped, mirroring the
	   merge logic. Refreshes the list afterwards so cached node counts update. */
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
						/* No point fetching a subscription we will not merge. */
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
