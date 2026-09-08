/*
 ============================================================================
 Name        : DnsActivity.java
 Description : DNS settings page
 ============================================================================
 */

package hev.sockstun;

import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textfield.TextInputLayout;

public class DnsActivity extends AppCompatActivity implements View.OnClickListener {
	private Preferences prefs;
	private CompoundButton checkbox_remote_dns;
	private EditText edittext_dns_ipv4;
	private EditText edittext_dns_ipv6;
	private TextInputLayout til_dns_ipv4;
	private TextInputLayout til_dns_ipv6;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DynamicColors.applyToActivityIfAvailable(this);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_dns);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		checkbox_remote_dns = (CompoundButton) findViewById(R.id.remote_dns);
		edittext_dns_ipv4 = (EditText) findViewById(R.id.dns_ipv4);
		edittext_dns_ipv6 = (EditText) findViewById(R.id.dns_ipv6);
		til_dns_ipv4 = (TextInputLayout) findViewById(R.id.til_dns_ipv4);
		til_dns_ipv6 = (TextInputLayout) findViewById(R.id.til_dns_ipv6);
		((MaterialButton) findViewById(R.id.save)).setOnClickListener(this);

		loadUI();
	}

	private void loadUI() {
		checkbox_remote_dns.setChecked(prefs.getRemoteDns());
		edittext_dns_ipv4.setText(prefs.getDnsIpv4());
		edittext_dns_ipv6.setText(prefs.getDnsIpv6());

		boolean editable = !prefs.getEnable();
		checkbox_remote_dns.setEnabled(editable);
		boolean remote = checkbox_remote_dns.isChecked();
		edittext_dns_ipv4.setEnabled(editable && !remote);
		edittext_dns_ipv6.setEnabled(editable && !remote);
		til_dns_ipv4.setEnabled(editable && !remote);
		til_dns_ipv6.setEnabled(editable && !remote);
	}

	@Override
	protected void onPause() {
		super.onPause();
		savePrefs();
	}

	@Override
	public void onClick(View view) {
		if (view == checkbox_remote_dns) {
			boolean editable = !prefs.getEnable();
			boolean remote = checkbox_remote_dns.isChecked();
			edittext_dns_ipv4.setEnabled(editable && !remote);
			edittext_dns_ipv6.setEnabled(editable && !remote);
			til_dns_ipv4.setEnabled(editable && !remote);
			til_dns_ipv6.setEnabled(editable && !remote);
			return;
		}
		savePrefs();
		Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
		finish();
	}

	private void savePrefs() {
		prefs.setRemoteDns(checkbox_remote_dns.isChecked());
		prefs.setDnsIpv4(edittext_dns_ipv4.getText().toString());
		prefs.setDnsIpv6(edittext_dns_ipv6.getText().toString());
	}
}
