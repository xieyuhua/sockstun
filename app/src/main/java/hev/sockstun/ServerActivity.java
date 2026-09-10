/*
 ============================================================================
 Name        : ServerActivity.java
 Description : Server list: add / edit / delete / pick the proxy to use.
               The selected entry is what the Home screen connects through.
 ============================================================================
 */

package hev.sockstun;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

public class ServerActivity extends BaseActivity {
	public static final String EXTRA_INDEX = "index";

	private Preferences prefs;
	private ListView listView;
	private ServerAdapter adapter;

	private static class Entry {
		public int index;
		public String name;
		public String addr;
		public int port;
	}

	private class ServerAdapter extends ArrayAdapter<Entry> {
		public ServerAdapter() {
			super(ServerActivity.this, R.layout.serveritem);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			LayoutInflater inflater = LayoutInflater.from(getContext());
			View row = inflater.inflate(R.layout.serveritem, parent, false);

			final Entry entry = getItem(position);
			final boolean selected = prefs.getSelected() == entry.index;

			((TextView) row.findViewById(R.id.name)).setText(entry.name);
			((TextView) row.findViewById(R.id.detail)).setText(entry.addr + ":" + entry.port);
			row.findViewById(R.id.badge).setVisibility(selected ? View.VISIBLE : View.GONE);
			row.setBackgroundColor(selected
				? MaterialColors.getColor(row,
					com.google.android.material.R.attr.colorPrimaryContainer, 0)
				: Color.TRANSPARENT);

			/* The row itself handles the click: the ImageButtons inside would
			   otherwise swallow it, and ListView.onItemClick never fires. */
			row.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					select(entry.index);
				}
			});

			ImageButton edit = (ImageButton) row.findViewById(R.id.edit);
			edit.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					openEditor(entry.index);
				}
			});

			ImageButton delete = (ImageButton) row.findViewById(R.id.delete);
			delete.setEnabled(prefs.getProfileCount() > 1);
			delete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					confirmDelete(entry);
				}
			});

			return row;
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_server);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		setupBottomNav(R.id.nav_server);

		adapter = new ServerAdapter();
		listView = (ListView) findViewById(R.id.list);
		listView.setAdapter(adapter);

		((MaterialButton) findViewById(R.id.add)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				addServer();
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		renderList();
	}

	private void renderList() {
		prefs = new Preferences(this);
		List<Entry> entries = new ArrayList<Entry>();
		int count = prefs.getProfileCount();
		for (int i = 0; i < count; i++) {
			Entry entry = new Entry();
			entry.index = i;
			entry.name = prefs.getProfileName(i);
			entry.addr = prefs.getSocksAddress(i);
			entry.port = prefs.getSocksPort(i);
			entries.add(entry);
		}

		adapter.setNotifyOnChange(false);
		adapter.clear();
		for (Entry entry : entries)
		  adapter.add(entry);
		adapter.notifyDataSetChanged();
	}

	/* Tapping an entry makes it the active one right away. While the tunnel
	   is up that means restarting it: stopService() ends the :native process
	   with System.exit(0), so the connect has to wait until it is gone. */
	private static final long RESTART_DELAY = 1000;

	private void select(int index) {
		if (index == prefs.getSelected())
		  return;

		prefs.setSelected(index);
		renderList();

		if (!prefs.getEnable()) {
			Toast.makeText(this, R.string.server_selected_hint, Toast.LENGTH_SHORT).show();
			return;
		}

		Toast.makeText(this, R.string.server_switching, Toast.LENGTH_SHORT).show();
		final Context app = getApplicationContext();
		Intent stop = new Intent(app, TProxyService.class)
			.setAction(TProxyService.ACTION_DISCONNECT);
		startService(stop);

		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override
			public void run() {
				Intent start = new Intent(app, TProxyService.class)
					.setAction(TProxyService.ACTION_CONNECT);
				app.startService(start);
			}
		}, RESTART_DELAY);
	}

	private void addServer() {
		if (prefs.getProfileCount() >= Preferences.MAX_PROFILES) {
			Toast.makeText(this, R.string.server_limit, Toast.LENGTH_SHORT).show();
			return;
		}
		if (prefs.getEnable()) {
			Toast.makeText(this, R.string.server_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		/* A new entry starts as a copy of the current one, then opens in the
		   editor so the name and address can be fixed right away. */
		String name = getString(R.string.server_new_name, prefs.getProfileCount() + 1);
		prefs.addProfile(name);
		openEditor(prefs.getSelected());
	}

	private void confirmDelete(final Entry entry) {
		if (prefs.getProfileCount() <= 1) {
			Toast.makeText(this, R.string.server_last, Toast.LENGTH_SHORT).show();
			return;
		}
		if (prefs.getEnable()) {
			Toast.makeText(this, R.string.server_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.server_delete_confirm, entry.name))
			.setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
				@Override
				public void onClick(android.content.DialogInterface dialog, int which) {
					prefs.removeProfile(entry.index);
					renderList();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void openEditor(int index) {
		Intent intent = new Intent(this, ServerEditActivity.class);
		intent.putExtra(EXTRA_INDEX, index);
		startActivity(intent);
	}
}
