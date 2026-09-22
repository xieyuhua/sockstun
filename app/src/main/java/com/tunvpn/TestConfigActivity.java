/*
 ============================================================================
 文件名  : TestConfigActivity.java
 说明    : 「测速配置」二级页（设置 → 延迟测试配置 → 测速配置）。
           把原先直接铺在设置页里的"延迟测试配置"整块搬到这里：测速地址、单次
           超时、无响应判定、自动测速间隔、未连接时是否预加载测速内核。
           行的构建方式与 SettingsActivity 完全一致（settings_item /
           settings_switch_item + 代码 inflate），保证风格统一。
 ============================================================================
*/

package com.tunvpn;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class TestConfigActivity extends BaseActivity implements View.OnClickListener {
	private Preferences prefs;
	private LinearLayout group;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_test_config);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		group = (LinearLayout) findViewById(R.id.test_config_group);
	}

	/* 每次 resume 都重建，让副标题反映刚改过的值。 */
	@Override
	protected void onResume() {
		super.onResume();
		prefs = new Preferences(this);
		buildList();
	}

	private void buildList() {
		group.removeAllViews();

		/* 测速地址：url-test 组判断节点快慢用的目标，访问不到就会把好节点测成失败。 */
		addRow(group, R.drawable.ic_routing, R.string.sub_test_url_title,
			getString(R.string.sub_test_url, prefs.getAutoTestUrl()), R.id.test_config_url);
		/* 单次探测超时（秒）：内核转发这次探测最多等多久，超时即判不可用。 */
		addRow(group, R.drawable.ic_routing, R.string.settings_test_timeout,
			getString(R.string.sub_test_timeout, prefs.getProxyTestTimeout()),
			R.id.test_config_timeout);
		/* 探测**完全没有回应**时该怎么算：不可用（默认）还是未测速。做成开关，是因为
		   否则"所有节点都变不可用"与"内核/桥坏了"这两种情况很难区分。 */
		addSwitchRow(group, R.drawable.ic_routing, R.string.settings_probe_timeout_fail,
			R.string.settings_probe_timeout_fail_hint, prefs.getProbeTimeoutAsFail(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setProbeTimeoutAsFail(checked);
				}
			});
		/* 自动选择时的定时重测间隔（分钟），在隧道（重）启动时生效。 */
		addRow(group, R.drawable.ic_routing, R.string.settings_autosel_interval,
			autoSelectIntervalSubtitle(), R.id.test_config_interval);
		/* 未连接时是否预加载无 TUN 的测速内核（以便测真实转发延迟）。 */
		addSwitchRow(group, R.drawable.ic_routing, R.string.settings_preload_core,
			R.string.settings_preload_core_hint, prefs.getPreloadCore(),
			new CompoundButton.OnCheckedChangeListener() {
				@Override
				public void onCheckedChanged(CompoundButton button, boolean checked) {
					prefs.setPreloadCore(checked);
					CoreTestHost.reset();
				}
			});
	}

	/* 一行：图标 + 标题 + 可选副标题 + 右侧箭头。行与行之间插一条细分隔线。 */
	private void addRow(LinearLayout group, int iconRes, int titleRes, String subtitle, int id) {
		if (group.getChildCount() > 0)
		  group.addView(makeDivider());

		View row = getLayoutInflater().inflate(R.layout.settings_item, group, false);
		row.setId(id);
		((ImageView) row.findViewById(R.id.item_icon)).setImageResource(iconRes);
		((TextView) row.findViewById(R.id.item_title)).setText(titleRes);

		TextView sub = (TextView) row.findViewById(R.id.item_subtitle);
		if (subtitle == null || subtitle.isEmpty()) {
			sub.setVisibility(View.GONE);
		} else {
			sub.setText(subtitle);
			sub.setVisibility(View.VISIBLE);
		}

		row.setOnClickListener(this);
		group.addView(row);
	}

	/* 与 addRow() 相同，只是右侧控件换成开关；点整行也能切换。 */
	private void addSwitchRow(LinearLayout group, int iconRes, int titleRes, int subtitleRes,
			boolean checked, CompoundButton.OnCheckedChangeListener listener) {
		if (group.getChildCount() > 0)
		  group.addView(makeDivider());

		View row = getLayoutInflater().inflate(R.layout.settings_switch_item, group, false);
		((ImageView) row.findViewById(R.id.item_icon)).setImageResource(iconRes);
		((TextView) row.findViewById(R.id.item_title)).setText(titleRes);
		TextView sub = (TextView) row.findViewById(R.id.item_subtitle);
		sub.setText(subtitleRes);
		sub.setVisibility(View.VISIBLE);

		final SwitchMaterial toggle = (SwitchMaterial) row.findViewById(R.id.item_switch);
		toggle.setChecked(checked);
		toggle.setOnCheckedChangeListener(listener);
		row.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				toggle.toggle();
			}
		});
		group.addView(row);
	}

	private View makeDivider() {
		View divider = new View(this);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, 1);
		float density = getResources().getDisplayMetrics().density;
		/* 与标题左对齐：16dp 内边距 + 22dp 图标 + 16dp 间距 */
		lp.leftMargin = (int) (54 * density);
		divider.setLayoutParams(lp);
		divider.setBackgroundColor(MaterialColors.getColor(this,
			com.google.android.material.R.attr.colorOutlineVariant, 0));
		return divider;
	}

	/* 测速地址/间隔都会写进生成的配置，所以运行中的隧道会一直沿用旧值，直到重启。 */
	private void afterNetworkChange() {
		if (prefs.getEnable())
		  Toast.makeText(this, R.string.settings_restart_needed, Toast.LENGTH_LONG).show();
	}

	/* 统一的单字段编辑器：Material 描边输入框 + 实时校验错误 + 可选的帮助文字。 */
	private interface InputValidator {
		String validate(String value);
	}
	private interface OnValue {
		void onReceiveValue(String value);
	}

	private AlertDialog showInputDialog(int titleRes, String hint, String help,
			int inputType, String initial, final InputValidator validator,
			final OnValue onOk, final Runnable onDefault) {
		View v = getLayoutInflater().inflate(R.layout.dialog_input, null);
		final TextInputLayout til = (TextInputLayout) v.findViewById(R.id.til);
		final TextInputEditText edit = (TextInputEditText) v.findViewById(R.id.edit_text);
		TextView helpView = (TextView) v.findViewById(R.id.help_text);
		til.setHint(hint);
		edit.setInputType(inputType);
		if (help != null && !help.isEmpty()) {
			helpView.setText(help);
			helpView.setVisibility(View.VISIBLE);
		} else {
			helpView.setVisibility(View.GONE);
		}
		if (initial != null) {
			edit.setText(initial);
			edit.setSelection(initial.length());
		}
		AlertDialog.Builder b = new AlertDialog.Builder(this)
			.setTitle(titleRes)
			.setView(v)
			.setPositiveButton(R.string.save, null)
			.setNegativeButton(android.R.string.cancel, null);
		if (onDefault != null)
			b.setNeutralButton(R.string.settings_port_default,
				new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface di, int w) {
						onDefault.run();
					}
				});
		final AlertDialog d = b.show();
		edit.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
			@Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
			@Override public void afterTextChanged(Editable s) {
				til.setError(validator.validate(s.toString().trim()));
			}
		});
		d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View vv) {
				String val = edit.getText().toString().trim();
				String err = validator.validate(val);
				if (err != null) {
					til.setError(err);
					return;
				}
				d.dismiss();
				onOk.onReceiveValue(val);
			}
		});
		return d;
	}

	/* 测速地址：留空恢复默认值；任何输入都必须是 http(s) 地址，且边输边校验。 */
	private void editTestUrl() {
		showInputDialog(R.string.sub_test_url_title,
			getString(R.string.sub_test_url_title),
			getString(R.string.sub_test_url_hint),
			InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT,
			prefs.getAutoTestUrl(),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return null;
					if (!isHttpUrl(v))
					  return getString(R.string.sub_test_url_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					prefs.setAutoTestUrl(v.trim());
					buildList();
					afterNetworkChange();
				}
			},
			null);
	}

	private static boolean isHttpUrl(String url) {
		String u = url.toLowerCase();
		return u.startsWith("http://") || u.startsWith("https://");
	}

	/* 单次探测目标的超时（秒，1–30）：限定内核转发这次探测最多花多久，免得一个慢但
	   可用的节点被过紧的默认值误判为不可用。 */
	private void editTestTimeout() {
		final int cur = prefs.getProxyTestTimeout();
		showInputDialog(R.string.settings_test_timeout,
			getString(R.string.settings_test_timeout),
			getString(R.string.sub_test_timeout_hint),
			InputType.TYPE_CLASS_NUMBER,
			Integer.toString(cur),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return getString(R.string.sub_test_timeout_invalid);
					int s;
					try {
						s = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						return getString(R.string.sub_test_timeout_invalid);
					}
					if (s < Preferences.MIN_PROXY_TEST_TIMEOUT
							|| s > Preferences.MAX_PROXY_TEST_TIMEOUT)
					  return getString(R.string.sub_test_timeout_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					int s;
					try {
						s = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						s = cur;
					}
					int clamped = Math.max(Preferences.MIN_PROXY_TEST_TIMEOUT,
						Math.min(Preferences.MAX_PROXY_TEST_TIMEOUT, s));
					prefs.setProxyTestTimeout(clamped);
					buildList();
				}
			},
			null);
	}

	/* url-test 组的自动重测间隔（分钟，1–1440），在隧道（重）启动时生效。 */
	private String autoSelectIntervalSubtitle() {
		return getString(R.string.sub_minutes,
			Math.max(1, Math.round(prefs.getAutoSelectInterval() / 60f)));
	}

	private void editAutoSelectInterval() {
		final int cur = Math.max(1, Math.round(prefs.getAutoSelectInterval() / 60f));
		showInputDialog(R.string.sub_interval_title,
			getString(R.string.sub_interval_hint),
			getString(R.string.sub_interval_custom_title),
			InputType.TYPE_CLASS_NUMBER,
			Integer.toString(cur),
			new InputValidator() {
				@Override public String validate(String v) {
					if (v.isEmpty())
					  return getString(R.string.sub_interval_invalid);
					int m;
					try {
						m = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						return getString(R.string.sub_interval_invalid);
					}
					if (m < 1 || m > 1440)
					  return getString(R.string.sub_interval_invalid);
					return null;
				}
			},
			new OnValue() {
				@Override public void onReceiveValue(String v) {
					int m;
					try {
						m = Integer.parseInt(v);
					} catch (NumberFormatException e) {
						m = cur;
					}
					int clamped = Math.max(1, Math.min(1440, m));
					prefs.setAutoSelectInterval(clamped * 60);
					buildList();
					afterNetworkChange();
				}
			},
			null);
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.test_config_url)
		  editTestUrl();
		else if (id == R.id.test_config_timeout)
		  editTestTimeout();
		else if (id == R.id.test_config_interval)
		  editAutoSelectInterval();
	}
}
