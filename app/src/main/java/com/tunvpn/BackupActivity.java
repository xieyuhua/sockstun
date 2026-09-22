/*
 ============================================================================
 文件名  : BackupActivity.java
 说明    : 「备份管理」独立界面（设置 → 备份 → 备份管理）。
           上半部分「备份当前可用节点」按钮；下半部分「备份历史记录」列表，
           每条可「覆盖当前配置」或「删除」——这正是原来藏在订阅页菜单对话框里、
           却不在「备份」分组里的三样东西。所有逻辑都只读缓存（节点延迟 +
           订阅原文），不依赖订阅页的实时 nodes，所以能独立成页。
 ============================================================================
*/

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class BackupActivity extends BaseActivity {
	private Preferences prefs;
	private MaterialButton btnBackup;
	private TextView backupEmpty;
	private LinearLayout backupList;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		setContentView(R.layout.activity_backup);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { finish(); }
		});

		btnBackup = (MaterialButton) findViewById(R.id.btn_backup);
		backupEmpty = (TextView) findViewById(R.id.backup_empty);
		backupList = (LinearLayout) findViewById(R.id.backup_list);

		btnBackup.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { doBackup(); }
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		prefs = new Preferences(this);
		refresh();
	}

	/* 重建界面：更新备份按钮上的可用节点数、刷新历史列表。 */
	private void refresh() {
		int avail = availableNodes().size();
		btnBackup.setText(getString(R.string.backup_now)
			+ (avail > 0 ? " (" + avail + ")" : ""));

		List<Subscription> backups = listBackups();
		backupList.removeAllViews();
		if (backups.isEmpty()) {
			backupEmpty.setVisibility(View.VISIBLE);
		} else {
			backupEmpty.setVisibility(View.GONE);
			for (Subscription s : backups)
				backupList.addView(makeBackupItem(s));
		}
	}

	/* 一条历史备份：标题（名称 · N 个节点）+ 两个操作按钮。 */
	private View makeBackupItem(final Subscription s) {
		int count = ClashNode.decode(prefs.getSubNodes(s.id)).size();

		LinearLayout item = new LinearLayout(this);
		item.setOrientation(LinearLayout.VERTICAL);
		float density = getResources().getDisplayMetrics().density;
		int padV = (int) (12 * density);
		item.setPadding(0, padV, 0, padV);

		TextView tv = new TextView(this);
		tv.setText(s.label() + (count > 0 ? "  ·  " + count + " 个节点" : ""));
		tv.setTextSize(16);
		tv.setTypeface(null, android.graphics.Typeface.BOLD);
		item.addView(tv);

		LinearLayout btns = new LinearLayout(this);
		btns.setOrientation(LinearLayout.HORIZONTAL);
		btns.setPadding(0, padV / 2, 0, 0);

		MaterialButton restore = new MaterialButton(this);
		restore.setText(R.string.backup_overwrite);
		restore.setMinHeight(0);
		restore.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { confirmOverwrite(s); }
		});
		MaterialButton del = new MaterialButton(this);
		del.setText(R.string.backup_delete);
		del.setMinHeight(0);
		del.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { confirmDelete(s); }
		});

		btns.addView(restore);
		btns.addView(del);
		item.addView(btns);
		return item;
	}

	private void confirmOverwrite(final Subscription s) {
		final int count = ClashNode.decode(prefs.getSubNodes(s.id)).size();
		new AlertDialog.Builder(this)
			.setTitle(R.string.backup_overwrite)
			.setMessage(getString(R.string.backup_overwrite_confirm, s.label(), count))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int w) {
					applyLocalOverride(s.id, s.name);
					refresh();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void confirmDelete(final Subscription s) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.backup_delete)
			.setMessage(getString(R.string.backup_delete_confirm, s.label()))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int w) {
					deleteBackup(s.id);
					refresh();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	/* 把所有可用节点（延迟 >= 0）备份。目的地同订阅页：WebDAV 已配置→上传远端，
	   否则落成本地订阅。 */
	private void doBackup() {
		List<ClashNode> available = availableNodes();
		if (available.isEmpty()) {
			Toast.makeText(this, R.string.backup_empty, Toast.LENGTH_LONG).show();
			return;
		}
		StringBuilder sb = new StringBuilder("proxies:\n");
		List<ClashNode> kept = new ArrayList<ClashNode>();
		int missed = 0;
		for (ClashNode n : available) {
			String raw = findRawProxy(n);
			if (raw == null || raw.isEmpty()) {
				missed++;
				continue;
			}
			sb.append(raw).append('\n');
			kept.add(n);
		}
		if (kept.isEmpty()) {
			Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
			return;
		}
		String name = getString(R.string.backup_name,
			new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date()));
		if (prefs.webdavReady()) {
			uploadBackupRemote(sb.toString(), kept, missed, name);
		} else {
			final String id = Subscription.newId();
			saveBackupLocal(sb.toString(), id, name, kept, missed);
			refresh(); /* 新备份立刻出现在历史列表 */
			offerApplyBackup(id, name, kept.size());
		}
	}

	private void offerApplyBackup(final String id, final String name, final int count) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.backup_apply_title)
			.setMessage(getString(R.string.backup_apply_msg, name, count))
			.setPositiveButton(R.string.backup_apply, new DialogInterface.OnClickListener() {
				@Override public void onClick(DialogInterface d, int w) {
					applyLocalOverride(id, name);
				}
			})
			.setNegativeButton(R.string.backup_apply_save_only, null)
			.setOnDismissListener(new DialogInterface.OnDismissListener() {
				@Override public void onDismiss(DialogInterface d) { refresh(); }
			})
			.show();
	}

	/* 用某条本地备份覆盖当前订阅：启用它、停用其它订阅；隧道连着就立即重建。 */
	private void applyLocalOverride(String subId, String name) {
		List<Subscription> list = prefs.getSubscriptions();
		for (Subscription s : list)
		  s.enabled = s.id.equals(subId);
		prefs.setSubscriptions(list);
		if (prefs.getEnable()) {
			Intent i = new Intent(this, TProxyService.class);
			i.setAction(TProxyService.ACTION_RECONNECT);
			startService(i);
		}
		Toast.makeText(this, getString(R.string.backup_applied, name), Toast.LENGTH_LONG).show();
	}

	/* 删除一条本地备份：清掉原始内容缓存与节点缓存。 */
	private void deleteBackup(String id) {
		List<Subscription> list = prefs.getSubscriptions();
		for (int i = 0; i < list.size(); i++) {
			if (id.equals(list.get(i).id)) {
				list.remove(i);
				break;
			}
		}
		prefs.setSubscriptions(list);
		prefs.setSubRaw(id, "");
		prefs.setSubNodes(id, "");
		Toast.makeText(this, R.string.backup_deleted, Toast.LENGTH_SHORT).show();
	}

	/* 本地备份：把可用节点存成一条默认停用的本地订阅，并写好节点缓存。 */
	private void saveBackupLocal(String yml, String id, String name,
			List<ClashNode> kept, int missed) {
		List<Subscription> list = prefs.getSubscriptions();
		list.add(new Subscription(id, name, "", false, true));
		prefs.setSubscriptions(list);
		prefs.setSubRaw(id, yml);
		List<ClashNode> copies = new ArrayList<ClashNode>();
		for (ClashNode n : kept) {
			ClashNode c = new ClashNode(n.name, n.type, n.server, n.port, n.username, n.password);
			c.country = n.country;
			c.latency = n.latency;
			c.subId = id;
			copies.add(c);
		}
		prefs.setSubNodes(id, ClashNode.encode(copies));
		TProxyService.log("订阅备份: 已备份 " + kept.size() + " 个可用节点"
			+ (missed > 0 ? ("（" + missed + " 个因订阅原文里找不到被跳过）") : "")
			+ " · " + name);
		Toast.makeText(this, missed > 0
			? getString(R.string.backup_done_skipped, kept.size(), missed)
			: getString(R.string.backup_done, kept.size()), Toast.LENGTH_LONG).show();
	}

	/* 远程备份：工作线程上传到 WebDAV；失败则回退本地并提示。 */
	private void uploadBackupRemote(final String yml, final List<ClashNode> kept,
			final int missed, final String name) {
		Toast.makeText(this, R.string.backup_uploading, Toast.LENGTH_SHORT).show();
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					final String fileName = WebDav.uploadBackup(prefs, yml);
					runOnUiThread(new Runnable() {
						@Override public void run() {
							TProxyService.log("订阅备份: 已上传 " + kept.size()
								+ " 个可用节点到 WebDAV · " + fileName
								+ (missed > 0 ? ("（" + missed + " 个被跳过）") : ""));
							Toast.makeText(BackupActivity.this,
								getString(R.string.backup_upload_done, fileName, kept.size()),
								Toast.LENGTH_LONG).show();
							refresh();
						}
					});
				} catch (final IOException e) {
					runOnUiThread(new Runnable() {
						@Override public void run() {
							saveBackupLocal(yml, Subscription.newId(), name, kept, missed);
							Toast.makeText(BackupActivity.this,
								getString(R.string.backup_upload_failed, e.getMessage()),
								Toast.LENGTH_LONG).show();
							refresh();
						}
					});
				}
			}
		}).start();
	}

	/* 仅本地内容（备份 / 导入）订阅才算历史备份。 */
	private List<Subscription> listBackups() {
		List<Subscription> out = new ArrayList<Subscription>();
		for (Subscription s : prefs.getSubscriptions())
		  if (s.local)
			out.add(s);
		return out;
	}

	/* 从缓存重建可用节点：启用订阅的节点缓存里、延迟 >= 0 的，按名字去重。 */
	private List<ClashNode> availableNodes() {
		List<ClashNode> out = new ArrayList<ClashNode>();
		Set<String> seen = new HashSet<String>();
		for (Subscription sub : prefs.getSubscriptions()) {
			if (sub == null || !sub.enabled)
			  continue;
			for (ClashNode n : ClashNode.decode(prefs.getSubNodes(sub.id))) {
				if (n.latency < 0)
				  continue;
				String key = (n.name == null) ? "" : n.name;
				if (seen.contains(key))
				  continue;
				seen.add(key);
				out.add(n);
			}
		}
		return out;
	}

	/* 还原一个节点原始的那段 clash 配置。先在它自己的订阅里找，再找其它订阅。 */
	private String findRawProxy(ClashNode n) {
		String raw = findRawProxyIn(prefs.getSubRaw(n.subId), n);
		if (!raw.isEmpty())
		  return raw;
		for (Subscription sub : prefs.getSubscriptions()) {
			if (sub == null || sub.id == null || sub.id.equals(n.subId))
			  continue;
			raw = findRawProxyIn(prefs.getSubRaw(sub.id), n);
			if (!raw.isEmpty())
			  return raw;
		}
		return "";
	}

	private String findRawProxyIn(String raw, ClashNode n) {
		if (raw == null || raw.isEmpty())
		  return "";
		Pattern serverPat = null;
		Pattern portPat = null;
		if (n.server != null && !n.server.isEmpty()) {
			serverPat = Pattern.compile(
				"server\\s*:\\s*[\"']?" + Pattern.quote(n.server));
			portPat = Pattern.compile(
				"port\\s*:\\s*[\"']?" + n.port + "(?![0-9])");
		}
		String byAddr = "";
		for (ClashParser.ProxyDef p : ClashParser.extractProxies(raw)) {
			if (n.name != null && n.name.equals(p.name))
			  return p.text;
			if (byAddr.isEmpty() && serverPat != null && p.text != null
					&& p.text.contains(":") && serverPat.matcher(p.text).find()
					&& portPat.matcher(p.text).find())
			  byAddr = p.text;
		}
		return byAddr;
	}
}
