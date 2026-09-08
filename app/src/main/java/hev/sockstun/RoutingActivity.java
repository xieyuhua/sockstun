/*
 ============================================================================
 Name        : RoutingActivity.java
 Description : Routing settings page
 ============================================================================
 */

package hev.sockstun;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;

public class RoutingActivity extends AppCompatActivity implements View.OnClickListener {
	private Preferences prefs;
	private CompoundButton checkbox_global;
	private CompoundButton checkbox_ipv4;
	private CompoundButton checkbox_ipv6;
	private CompoundButton checkbox_udp_in_tcp;
	private Button button_apps;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DynamicColors.applyToActivityIfAvailable(this);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_routing);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		checkbox_global = (CompoundButton) findViewById(R.id.global);
		checkbox_ipv4 = (CompoundButton) findViewById(R.id.ipv4);
		checkbox_ipv6 = (CompoundButton) findViewById(R.id.ipv6);
		checkbox_udp_in_tcp = (CompoundButton) findViewById(R.id.udp_in_tcp);
		button_apps = (Button) findViewById(R.id.apps);
		((MaterialButton) findViewById(R.id.save)).setOnClickListener(this);

		button_apps.setOnClickListener(this);
		checkbox_global.setOnClickListener(this);

		loadUI();
	}

	private void loadUI() {
		checkbox_global.setChecked(prefs.getGlobal());
		checkbox_ipv4.setChecked(prefs.getIpv4());
		checkbox_ipv6.setChecked(prefs.getIpv6());
		checkbox_udp_in_tcp.setChecked(prefs.getUdpInTcp());

		boolean editable = !prefs.getEnable();
		checkbox_global.setEnabled(editable);
		checkbox_ipv4.setEnabled(editable);
		checkbox_ipv6.setEnabled(editable);
		checkbox_udp_in_tcp.setEnabled(editable);
		button_apps.setEnabled(editable && !checkbox_global.isChecked());
	}

	@Override
	protected void onPause() {
		super.onPause();
		savePrefs();
	}

	@Override
	public void onClick(View view) {
		if (view == button_apps) {
			startActivity(new Intent(this, AppListActivity.class));
			return;
		}
		if (view == checkbox_global) {
			boolean editable = !prefs.getEnable();
			button_apps.setEnabled(editable && !checkbox_global.isChecked());
			return;
		}
		savePrefs();
		Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
		finish();
	}

	private void savePrefs() {
		prefs.setGlobal(checkbox_global.isChecked());
		prefs.setIpv4(checkbox_ipv4.isChecked());
		prefs.setIpv6(checkbox_ipv6.isChecked());
		prefs.setUdpInTcp(checkbox_udp_in_tcp.isChecked());
	}
}
