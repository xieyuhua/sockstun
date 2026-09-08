/*
 ============================================================================
 Name        : ServerActivity.java
 Description : Server settings page
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

public class ServerActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private EditText edittext_socks_addr;
	private EditText edittext_socks_udp_addr;
	private EditText edittext_socks_port;
	private EditText edittext_socks_user;
	private EditText edittext_socks_pass;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_server);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		edittext_socks_addr = (EditText) findViewById(R.id.socks_addr);
		edittext_socks_udp_addr = (EditText) findViewById(R.id.socks_udp_addr);
		edittext_socks_port = (EditText) findViewById(R.id.socks_port);
		edittext_socks_user = (EditText) findViewById(R.id.socks_user);
		edittext_socks_pass = (EditText) findViewById(R.id.socks_pass);
		((MaterialButton) findViewById(R.id.save)).setOnClickListener(this);

		loadUI();
	}

	private void loadUI() {
		edittext_socks_addr.setText(prefs.getSocksAddress());
		edittext_socks_udp_addr.setText(prefs.getSocksUdpAddress());
		edittext_socks_port.setText(Integer.toString(prefs.getSocksPort()));
		edittext_socks_user.setText(prefs.getSocksUsername());
		edittext_socks_pass.setText(prefs.getSocksPassword());

		boolean editable = !prefs.getEnable();
		edittext_socks_addr.setEnabled(editable);
		edittext_socks_udp_addr.setEnabled(editable);
		edittext_socks_port.setEnabled(editable);
		edittext_socks_user.setEnabled(editable);
		edittext_socks_pass.setEnabled(editable);
	}

	/* Persist any edits when leaving the page so the Home screen and the
	   running tunnel always see the latest values. */
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
		prefs.setSocksAddress(edittext_socks_addr.getText().toString());
		prefs.setSocksUdpAddress(edittext_socks_udp_addr.getText().toString());
		prefs.setSocksUsername(edittext_socks_user.getText().toString());
		prefs.setSocksPassword(edittext_socks_pass.getText().toString());
		String port = edittext_socks_port.getText().toString().trim();
		if (!port.isEmpty()) {
			try {
				prefs.setSocksPort(Integer.parseInt(port));
			} catch (NumberFormatException e) {
				edittext_socks_port.setText(Integer.toString(prefs.getSocksPort()));
			}
		}
	}
}
