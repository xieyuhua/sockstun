/*
 ============================================================================
 文件名  : SubscribeConfigActivity.java
 说明    : 管理 clash.yml 订阅地址：新增 / 编辑 / 删除 / 启用。启用的订阅会被**合并**
           成一个节点池（当前实现没有"默认订阅"的概念）。
 ============================================================================
 */

package com.tunvpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
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
import java.io.IOException;
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
		/* 长按列表唤起"订阅工具"菜单：复制全部地址 / 节点、导入（base64 / 备份文本）、
		   从 WebDAV 导入 —— 不必点开右上角溢出菜单。 */
		registerForContextMenu(listview);

		((MaterialButton) findViewById(R.id.subs_add)).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showEditDialog(null);
			}
		});

		/* 溢出菜单：导入（base64 / clash.yml / 备份文本）+ 两种"复制全部"。
		   备份与导入产生的都是**本地订阅**（Subscription.local），不依赖机场地址。 */
		toolbar.inflateMenu(R.menu.subscribe_config_menu);
		toolbar.setOnMenuItemClickListener(new MaterialToolbar.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(android.view.MenuItem item) {
				int id = item.getItemId();
				if (id == R.id.action_import)
				  importContent();
				else if (id == R.id.action_import_webdav)
				  importFromWebdav();
				else if (id == R.id.action_copy_urls)
				  copyAllUrls();
				else if (id == R.id.action_copy_nodes)
				  copyAllNodes();
				return true;
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
		/* 本地内容（备份 / 导入）：没有地址可填，内容也不该被改。 */
		final boolean local = !add && existing.local;

		LinearLayout container = new LinearLayout(this);
		container.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		container.setPadding(pad, pad / 2, pad, 0);

		final EditText editName = new EditText(this);
		editName.setHint(R.string.subs_name);
		editName.setSingleLine(true);
		final EditText editUrl = new EditText(this);
		/* 新增时这一栏既能填订阅链接，也能直接粘贴 base64 / 备份文本：提示与输入类型都
		   放宽成多行纯文本；编辑已有网络订阅时仍是单行 URI。 */
		if (add) {
			editUrl.setHint(R.string.subs_add_input_hint);
			editUrl.setSingleLine(false);
			editUrl.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		} else {
			editUrl.setHint(R.string.subs_url);
			editUrl.setSingleLine(true);
			editUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
		}
		if (!add) {
			editName.setText(existing.name);
			editUrl.setText(existing.url);
		}
		if (local)
		  editUrl.setVisibility(View.GONE);
		final SwitchMaterial switchEnabled = new SwitchMaterial(this);
		switchEnabled.setText(R.string.subs_enabled);
		/* 新增的订阅默认启用；已有的保持它原来的开关状态。 */
		switchEnabled.setChecked(add || existing.enabled);
		container.addView(editName);
		container.addView(editUrl);
		container.addView(switchEnabled);

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(add ? R.string.subs_add : R.string.subs_edit)
			.setView(container)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					String raw = editUrl.getText().toString().trim();
					/* 本地条目的地址本来就是空的（备份 / 导入），不该被"必须填地址"挡住。 */
					if (!local && raw.isEmpty()) {
						Toast.makeText(SubscribeConfigActivity.this,
							R.string.subs_url_required, Toast.LENGTH_SHORT).show();
						return;
					}
					String name = editName.getText().toString().trim();
					List<Subscription> list = prefs.getSubscriptions();
					if (add) {
						/* 链接 → 网络订阅；否则视为 base64 / 备份文本，直接落成本地订阅。 */
						boolean isUrl = !raw.isEmpty()
							&& (raw.startsWith("http://") || raw.startsWith("https://"));
						if (isUrl) {
							list.add(new Subscription(Subscription.newId(), name, raw,
								switchEnabled.isChecked()));
							prefs.setSubscriptions(list);
							load();
						} else {
							int n = saveImportedLocal(
								name.isEmpty() ? getString(R.string.subs_import_name) : name,
								raw, switchEnabled.isChecked());
							if (n < 0) {
								Toast.makeText(SubscribeConfigActivity.this,
									R.string.subs_add_content_failed, Toast.LENGTH_LONG).show();
							}
						}
						return;
					} else {
						for (int i = 0; i < list.size(); i++) {
							if (existing.id.equals(list.get(i).id)) {
								/* 保留 local 标记与它原本的地址：改个名字不该把它变成
								   一条"有地址但拉不到内容"的网络订阅。 */
								list.set(i, new Subscription(existing.id, name,
									local ? existing.url : raw, switchEnabled.isChecked(), local));
								break;
							}
						}
					}
					prefs.setSubscriptions(list);
					load();
				}
			})
			.setNegativeButton(android.R.string.cancel, null);
		if (local) {
			/* 一键"用它替换"：启用这条本地备份并停用其它订阅 —— 机场地址失效时通常就是
			   这个动作（对应"下次可以直接选择使用订阅备份，覆盖当前订阅列表"）。 */
			builder.setNeutralButton(R.string.subs_use_local, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					useLocalReplace(existing);
				}
			});
		}
		builder.show();
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
		String detailStr;
		if (s.local) {
			/* 本地条目（备份 / 导入）没有地址可展示，只说清它存了多少节点。 */
			detailStr = getString(R.string.subs_local_detail, cached);
		} else {
			detailStr = cached > 0
				? getString(R.string.subs_cached, s.url, cached)
				: getString(R.string.subs_not_fetched, s.url);
		}
		if (!s.enabled)
			detailStr += "  ·  " + getString(R.string.subs_disabled);
		return detailStr;
	}

	/* 导入订阅内容：粘贴 base64、含 `proxies:` 的 clash.yml，或此前「复制全部节点」得到的
	   备份文本 → 存成一条**本地订阅**（不下载、不依赖地址）。默认**停用**，由用户在列表里
	   打开开关，免得和来源订阅一起合并、把节点翻倍。 */
	private void importContent() {
		LinearLayout container = new LinearLayout(this);
		container.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		container.setPadding(pad, pad / 2, pad, 0);
		final EditText editName = new EditText(this);
		editName.setHint(R.string.subs_name);
		editName.setSingleLine(true);
		editName.setText(R.string.subs_import_name);
		final EditText editContent = new EditText(this);
		editContent.setHint(R.string.subs_import_hint);
		editContent.setMinLines(4);
		editContent.setGravity(android.view.Gravity.TOP);
		editContent.setInputType(InputType.TYPE_CLASS_TEXT
			| InputType.TYPE_TEXT_FLAG_MULTI_LINE
			| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		container.addView(editName);
		container.addView(editContent);
		new AlertDialog.Builder(this)
			.setTitle(R.string.subs_import_title)
			.setView(container)
			.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					String input = editContent.getText().toString().trim();
					if (input.isEmpty()) {
						Toast.makeText(SubscribeConfigActivity.this,
							R.string.subs_import_empty, Toast.LENGTH_SHORT).show();
						return;
					}
					/* decodeRaw 自己会处理 base64（明文则原样返回）。 */
					String yaml = ClashParser.decodeRaw(input);
					if (yaml == null || !yaml.contains("proxies:")) {
						Toast.makeText(SubscribeConfigActivity.this,
							R.string.subs_import_failed, Toast.LENGTH_LONG).show();
						return;
					}
					List<ClashNode> parsed = ClashParser.parseAll(yaml);
					if (parsed.isEmpty()) {
						Toast.makeText(SubscribeConfigActivity.this,
							R.string.subs_import_failed, Toast.LENGTH_LONG).show();
						return;
					}
					String id = Subscription.newId();
					for (ClashNode n : parsed)
					  n.subId = id;
					String name = editName.getText().toString().trim();
					if (name.isEmpty())
					  name = getString(R.string.subs_import_name);
					List<Subscription> list = prefs.getSubscriptions();
					list.add(new Subscription(id, name, "", false, true));
					prefs.setSubscriptions(list);
					prefs.setSubRaw(id, yaml);
					prefs.setSubNodes(id, ClashNode.encode(parsed));
					load();
					Toast.makeText(SubscribeConfigActivity.this,
						getString(R.string.subs_import_ok, parsed.size()),
						Toast.LENGTH_LONG).show();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* 从 WebDAV 导入：列出远端备份 → 选一个 → 下载 → 存成本地订阅。列目录与下载都是
	   网络操作，走后台线程。没配置 WebDAV 时引导去设置页。 */
	private void importFromWebdav() {
		if (!prefs.webdavReady()) {
			Toast.makeText(this, R.string.subs_import_webdav_need, Toast.LENGTH_LONG).show();
			return;
		}
		Toast.makeText(this, R.string.subs_import_fetching, Toast.LENGTH_SHORT).show();
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					final List<String> names = WebDav.listBackups(prefs);
					runOnUiThread(new Runnable() {
						@Override public void run() {
							if (names.isEmpty()) {
								Toast.makeText(SubscribeConfigActivity.this,
									R.string.subs_import_webdav_none, Toast.LENGTH_LONG).show();
								return;
							}
							showWebdavPicker(names);
						}
					});
				} catch (final IOException e) {
					runOnUiThread(new Runnable() {
						@Override public void run() {
							Toast.makeText(SubscribeConfigActivity.this,
								getString(R.string.subs_import_webdav_failed, e.getMessage()),
								Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	private void showWebdavPicker(final List<String> names) {
		final String[] items = names.toArray(new String[0]);
		new AlertDialog.Builder(this)
			.setTitle(R.string.subs_import_webdav)
			.setItems(items, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int which) {
					downloadAndSave(names.get(which));
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void downloadAndSave(final String fileName) {
		Toast.makeText(this, R.string.subs_import_fetching, Toast.LENGTH_SHORT).show();
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					final String yaml = WebDav.download(prefs, fileName);
					runOnUiThread(new Runnable() {
						@Override public void run() {
							int n = saveImportedLocal(
								getString(R.string.subs_import_remote_name, fileName), yaml, false);
							if (n < 0) {
								Toast.makeText(SubscribeConfigActivity.this,
									R.string.subs_import_failed, Toast.LENGTH_LONG).show();
								return;
							}
							Toast.makeText(SubscribeConfigActivity.this,
								getString(R.string.subs_import_ok, n), Toast.LENGTH_LONG).show();
						}
					});
				} catch (final IOException e) {
					runOnUiThread(new Runnable() {
						@Override public void run() {
							Toast.makeText(SubscribeConfigActivity.this,
								getString(R.string.subs_import_webdav_failed, e.getMessage()),
								Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	/* 把一份订阅内容（base64 或含 `proxies:` 的明文）存成本地订阅。
	   decodeRaw 自己会处理 base64（明文则原样返回）。返回节点数；解析失败返回 -1。 */
	private int saveImportedLocal(String name, String input, boolean enabled) {
		if (input == null)
		  return -1;
		String yaml = ClashParser.decodeRaw(input.trim());
		if (yaml == null || !yaml.contains("proxies:"))
		  return -1;
		List<ClashNode> parsed = ClashParser.parseAll(yaml);
		if (parsed.isEmpty())
		  return -1;
		String id = Subscription.newId();
		for (ClashNode n : parsed)
		  n.subId = id;
		List<Subscription> list = prefs.getSubscriptions();
		list.add(new Subscription(id, name, "", enabled, true));
		prefs.setSubscriptions(list);
		prefs.setSubRaw(id, yaml);
		prefs.setSubNodes(id, ClashNode.encode(parsed));
		load();
		return parsed.size();
	}

	/* 复制**全部订阅地址**（每行一个）：方便分享 / 迁移到别的设备。本地条目没有地址，跳过。 */
	private void copyAllUrls() {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (Subscription s : prefs.getSubscriptions()) {
			if (s.local)
			  continue;
			String url = s.url == null ? "" : s.url.trim();
			if (url.isEmpty())
			  continue;
			sb.append(url).append('\n');
			count++;
		}
		if (count == 0) {
			Toast.makeText(this, R.string.subs_copy_none, Toast.LENGTH_SHORT).show();
			return;
		}
		copyToClipboard("subscriptions", sb.toString());
		Toast.makeText(this, getString(R.string.subs_copy_url_ok, count),
			Toast.LENGTH_SHORT).show();
	}

	/* 复制**全部节点**：把每份订阅（含本地备份）的 `proxies:` 段合并成一份 clash.yml 文本，
	   按节点名去重。它可以直接粘回「导入订阅内容」，也能带到别的设备/客户端 —— 整盘搬走。 */
	private void copyAllNodes() {
		StringBuilder sb = new StringBuilder("proxies:\n");
		java.util.Set<String> seen = new java.util.HashSet<String>();
		int count = 0;
		for (Subscription s : prefs.getSubscriptions()) {
			for (ClashParser.ProxyDef p : ClashParser.extractProxies(prefs.getSubRaw(s.id))) {
				if (p.name == null || p.name.isEmpty() || !seen.add(p.name))
				  continue;
				sb.append(p.text).append('\n');
				count++;
			}
		}
		if (count == 0) {
			Toast.makeText(this, R.string.subs_copy_none, Toast.LENGTH_SHORT).show();
			return;
		}
		copyToClipboard("nodes", sb.toString());
		Toast.makeText(this, getString(R.string.subs_copy_nodes_ok, count),
			Toast.LENGTH_SHORT).show();
	}

	/* 一键"用它替换"：启用这条本地备份、停用其它订阅，于是节点池只由它提供。
	   配置的节点池是**连接时**烘焙的，所以要重新连接才生效。 */
	private void useLocalReplace(Subscription target) {
		List<Subscription> list = prefs.getSubscriptions();
		for (Subscription s : list)
		  s.enabled = s.id.equals(target.id);
		prefs.setSubscriptions(list);
		load();
		/* 运行中的隧道若已连接，立即重建配置，让"覆盖当前订阅"马上生效。 */
		if (prefs.getEnable()) {
			Intent i = new Intent(this, TProxyService.class);
			i.setAction(TProxyService.ACTION_RECONNECT);
			startService(i);
		}
		Toast.makeText(this, getString(R.string.subs_used_local, target.label()),
			Toast.LENGTH_LONG).show();
	}

	private void copyToClipboard(String label, String text) {
		ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		if (cm != null)
		  cm.setPrimaryClip(ClipData.newPlainText(label, text));
	}

	/* 长按列表弹出的"订阅工具"菜单：与右上角溢出菜单的导入 / 复制项一一对应，
	   只是换了个更顺手的长按入口。 */
	@Override
	public void onCreateContextMenu(android.view.ContextMenu menu, View v,
			android.view.ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		menu.setHeaderTitle(R.string.subs_tools);
		menu.add(0, R.id.action_copy_urls, 0, R.string.subs_copy_all_url);
		menu.add(0, R.id.action_copy_nodes, 1, R.string.subs_copy_all_nodes);
		menu.add(0, R.id.action_import, 2, R.string.subs_import);
		menu.add(0, R.id.action_import_webdav, 3, R.string.subs_import_webdav);
	}

	@Override
	public boolean onContextItemSelected(android.view.MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_copy_urls) { copyAllUrls(); return true; }
		if (id == R.id.action_copy_nodes) { copyAllNodes(); return true; }
		if (id == R.id.action_import) { importContent(); return true; }
		if (id == R.id.action_import_webdav) { importFromWebdav(); return true; }
		return super.onContextItemSelected(item);
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
						/* 本地内容（备份 / 导入）没有地址可拉，内容也已经在本地：
						   去拉只会把它覆盖掉，必须跳过。 */
						if (sub.local)
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
