/*
 ============================================================================
 Name        : SubscribeConfigActivity.java
 Description : Manage the clash.yml subscriptions: add, edit, delete and pick
               which one is the default (the one the subscribe page fetches).
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.graphics.Color;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

public class SubscribeConfigActivity extends BaseActivity {
	private Preferences prefs;
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

	private void setDefault(Subscription s) {
		prefs.setActiveSubId(s.id);
		prefs.setSubUrl(s.url);
		Toast.makeText(this, getString(R.string.subs_current, s.label()),
			Toast.LENGTH_SHORT).show();
		load();
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
					if (s.id.equals(prefs.getActiveSubId())) {
						prefs.setActiveSubId("");
						prefs.setSubUrl("");
						prefs.setSubRaw("");
						prefs.setSubNodes("");
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
		container.addView(editName);
		container.addView(editUrl);

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
						list.add(new Subscription(Subscription.newId(), name, url));
					} else {
						for (int i = 0; i < list.size(); i++) {
							if (existing.id.equals(list.get(i).id)) {
								list.set(i, new Subscription(existing.id, name, url));
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
		private final int colorSelected;

		SubAdapter() {
			super(SubscribeConfigActivity.this, R.layout.subscriptionitem, subs);
			inflater = getLayoutInflater();
			colorSelected = MaterialColors.getColor(SubscribeConfigActivity.this,
				com.google.android.material.R.attr.colorPrimaryContainer, 0);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null)
				convertView = inflater.inflate(R.layout.subscriptionitem, parent, false);
			final Subscription s = getItem(position);
			MaterialCardView card = (MaterialCardView) convertView;
			TextView name = (TextView) convertView.findViewById(R.id.item_name);
			TextView detail = (TextView) convertView.findViewById(R.id.item_detail);
			TextView badge = (TextView) convertView.findViewById(R.id.item_badge);
			Button def = (Button) convertView.findViewById(R.id.item_default);
			Button edit = (Button) convertView.findViewById(R.id.item_edit);
			Button delete = (Button) convertView.findViewById(R.id.item_delete);

			name.setText(s.label());
			detail.setText(s.url);

			boolean active = s.id.equals(prefs.getActiveSubId());
			badge.setVisibility(active ? View.VISIBLE : View.GONE);
			card.setCardBackgroundColor(active ? colorSelected : Color.TRANSPARENT);
			def.setEnabled(!active);

			def.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					setDefault(s);
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
}
