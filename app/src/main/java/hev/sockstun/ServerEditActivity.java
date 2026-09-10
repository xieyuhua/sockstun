/*
 ============================================================================
 Name        : ServerEditActivity.java
 Description : Edit one entry of the server list (name, address, port,
               UDP relay address, credentials).
 ============================================================================
 */

package hev.sockstun;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class ServerEditActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private int index;

	private EditText edittext_name;
	private EditText edittext_socks_addr;
	private EditText edittext_socks_udp_addr;
	private EditText edittext_socks_port;
	private EditText edittext_socks_user;
	private EditText edittext_socks_pass;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		index = getIntent().getIntExtra(ServerActivity.EXTRA_INDEX, prefs.getSelected());
		if (index < 0 || index >= prefs.getProfileCount())
		  index = prefs.getSelected();

		setContentView(R.layout.activity_server_edit);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		edittext_name = (EditText) findViewById(R.id.server_name);
		edittext_socks_addr = (EditText) findViewById(R.id.socks_addr);
		edittext_socks_udp_addr = (EditText) findViewById(R.id.socks_udp_addr);
		edittext_socks_port = (EditText) findViewById(R.id.socks_port);
		edittext_socks_user = (EditText) findViewById(R.id.socks_user);
		edittext_socks_pass = (EditText) findViewById(R.id.socks_pass);
		((MaterialButton) findViewById(R.id.save)).setOnClickListener(this);

		loadUI();
	}

	private void loadUI() {
		edittext_name.setText(prefs.getProfileName(index));
		edittext_socks_addr.setText(prefs.getSocksAddress(index));
		edittext_socks_udp_addr.setText(prefs.getSocksUdpAddress(index));
		edittext_socks_port.setText(Integer.toString(prefs.getSocksPort(index)));
		edittext_socks_user.setText(prefs.getSocksUsername(index));
		edittext_socks_pass.setText(prefs.getSocksPassword(index));
	}

	/* Persist on the way out the same way the old single-page editor did, so
	   pressing back never loses an edit. */
	@Override
	protected void onPause() {
		super.onPause();
		savePrefs();
	}

	@Override
	public void onClick(View view) {
		savePrefs();
		Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
		finish();
	}

	private void savePrefs() {
		String name = edittext_name.getText().toString().trim();
		if (!name.isEmpty())
		  prefs.setProfileName(index, name);
		prefs.setSocksAddress(index, edittext_socks_addr.getText().toString());
		prefs.setSocksUdpAddress(index, edittext_socks_udp_addr.getText().toString());
		prefs.setSocksUsername(index, edittext_socks_user.getText().toString());
		prefs.setSocksPassword(index, edittext_socks_pass.getText().toString());

		String port = edittext_socks_port.getText().toString().trim();
		if (!port.isEmpty()) {
			try {
				prefs.setSocksPort(index, Integer.parseInt(port));
			} catch (NumberFormatException e) {
				edittext_socks_port.setText(Integer.toString(prefs.getSocksPort(index)));
			}
		}
	}
}
