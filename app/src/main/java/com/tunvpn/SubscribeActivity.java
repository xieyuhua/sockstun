/*
 ============================================================================
 文件名  : SubscribeActivity.java
 说明    : 抓取远程 clash.yml 订阅，列出所有节点，逐个做延迟测速（可用 / 不可用），
           支持排序 + 筛选，并把选中的节点作为隧道的上游。
 ============================================================================
 */

package com.tunvpn;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.tunvpn.GeoIp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class SubscribeActivity extends BaseActivity {
	/* 下拉框默认选中第 0 项，而"最快的排前面"正是用户想要的，
	   所以延迟是索引 0，订阅原始顺序是索引 1。 */

	private Preferences prefs;
	private FloatingActionButton fab_test_all;
	private ListView listview;
	private TextView textview_empty;
	private TextView textview_stats;
	/* "测速全部"的进度：卡片里的一条**确定进度**条，让用户看得见它在动，
	   而不用猜按钮到底有没有生效。 */
	private ProgressBar progress_bar;
	private SwitchMaterial switch_auto;
	private TextView textview_auto_hint;
	/* 国家标签：一排可横向滑动的小标签；"" = 全部国家（= 池里的每个节点）。
	   选中的国家会**真正收窄隧道的节点池**（MihomoConfig.mergedConfig），
	   所以已经不再需要单独的"自动选择国家"下拉框了。 */
	private ChipGroup countryChips;
	private final List<String> filterCountryCodes = new ArrayList<String>();
	private String filterCountry = "";
	/* 节点列表的协议（节点类型，如 ss / vmess / trojan）筛选。
	   "" 表示"全部协议"。会持久化，所以重新打开页面依然保留。 */
	private Spinner spinner_proto_filter;
	private ArrayAdapter<String> protoFilterAdapter;
	private final List<String> filterProtoTypes = new ArrayList<String>();
	private final List<String> filterProtoLabels = new ArrayList<String>();
	private String filterProto = "";
	/* subId -> 订阅名，好让每行显示它来自哪份订阅。 */
	private final java.util.Map<String, String> subNames =
		new java.util.HashMap<String, String>();
	/* 测速用的**有界**线程池。进程内的动作桥本来就是串行的（只有一个在途回调），
	   所以 16 个 worker 只是让 15 个线程卡在锁上排队 —— 徒增内存与调度压力，一点不快。
	   4 个足够喂满内核，也能避免一份超大订阅堆积出一堆做不完的任务。 */
	private final ExecutorService testPool = Executors.newFixedThreadPool(4);
	/* 国别解析跑在**独立线程**上。它只是装饰（国旗 + 国家标签），但每台主机可能耗掉
	   好几秒（3 秒 DNS 上限再加两次 HTTP 兜底）；之前内联在探测线程里做，会让 worker
	   测完一个节点后先等国别解析才去测下一个 —— 几百个节点光这一项就能把一轮拖长几分钟。 */
	private final ExecutorService geoPool = Executors.newSingleThreadExecutor(
		new java.util.concurrent.ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "geoip-resolve");
				t.setDaemon(true);
				return t;
			}
		});
	/* 本轮的时间都花在哪了（收尾日志）：桥是**单回调**的，所以一轮的耗时=各次探测之
	   **和** —— 这几个计数反映的正是这件事。 */
	private final java.util.concurrent.atomic.AtomicLong probeMsTotal =
		new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicInteger probeCount =
		new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicLong slowestMs =
		new java.util.concurrent.atomic.AtomicLong();
	private volatile String slowestName = null;

	/* 本轮测速解析出的 mihomo 节点名："server|port|type" -> 运行配置里**真实**的
	   （去重后的）节点名。按需从 clash-api 拉取，这样即使 mergedConfig 把重名的节点
	   改成了 "name (N)"，逐个节点的延迟测试也能打到正确的那个上。 */
	private volatile java.util.Map<String, String> proxyNameCache = null;
	/* 内核报出的**可测**节点数，供日志用：它能区分"生成的配置里压根没有节点"和
	   "这个节点不在其中"。 */
	private volatile int coreNodeCount = 0;
	/* 因为本轮被新轮替换/停止而没来得及测的节点数。收尾时会报出来，
	   这样"N 个未测速"就不会是个谜。 */
	private final java.util.concurrent.atomic.AtomicInteger passSkipped =
		new java.util.concurrent.atomic.AtomicInteger();
	/* 本轮在内核里**找不到**的节点数（通常是因为测速内核是按国家筛选后的池构建的）。
	   用来决定"几乎什么都没测出来"时给出的那条提示。 */
	private final java.util.concurrent.atomic.AtomicInteger passNotFound =
		new java.util.concurrent.atomic.AtomicInteger();
	/* 本轮**仅仅因为探测没有响应**就被判为不可用的节点数
	   （设置 → 「无响应判为不可用」）。如果整轮都是这种情况，原因几乎一定是内核/桥，
	   而不是这些节点本身 —— 值得明确说出来。 */
	private final java.util.concurrent.atomic.AtomicInteger passTimeoutFail =
		new java.util.concurrent.atomic.AtomicInteger();

	/* 本轮测速可用的 clash-api 主机。优先探 127.0.0.1（external-controller 绑的回环），
	   并以设备 IP 作为兜底，以防回环哪天被 TUN 捕获。 */
	private String apiHost = null;

	/* 探过一次而控制接口**没有应答**之后置 true。没有它的话，**每个**节点都会再探一次
	   （每次都走一次桥调用，内核沉默时还要等满整个等待时间），光这一项就能把一轮拖成
	   爬行。每轮测速只探一次。 */
	private volatile boolean apiHostChecked = false;

	/* nodes = 解析出来的**全部**节点（唯一真相，会持久化）
	   shown = 经过筛选 + 排序后列表实际展示的那些 */
	private List<ClashNode> nodes = new ArrayList<ClashNode>();
	private List<ClashNode> shown = new ArrayList<ClashNode>();
	private NodeAdapter adapter;
	private final Handler ui = new Handler(Looper.getMainLooper());

	/* **本轮**测速里已经打过日志的键。否则一个"整轮级"的情况（没有内核 / 没有节点映射 /
	   控制接口挂了）会在**每个**节点上各打一遍，把真正有用的行全淹掉。由 testAll() 清空。 */
	private final java.util.Set<String> passLogged =
		java.util.Collections.newSetFromMap(
			new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/* 一个"整轮级"的情况，每轮最多记一次。用户反馈"某节点变成不可用"时就是要发这段
	   日志，所以内容里带着原因和撞上它的那个节点。 */
	private void logOnce(String key, String msg) {
		if (passLogged.add(key))
		  TProxyService.log(msg);
	}

	/* mihomo 的内置适配器 + 所有代理**组**类型：它们不是可拨号的节点，
	   所以绝不能拿来当延迟测试的目标。需要这个判断是因为现在的 mihomo 在 /proxies 里
	   不再报 server/port，没法再用"有没有地址"来区分条目类型。 */
	private static boolean isBuiltinOrGroup(String name, String type) {
		String t = type == null ? "" : type.toLowerCase();
		if (t.equals("selector") || t.equals("urltest") || t.equals("fallback")
				|| t.equals("loadbalance") || t.equals("relay")
				|| t.equals("direct") || t.equals("reject") || t.equals("rejectdrop")
				|| t.equals("compatible") || t.equals("pass") || t.equals("passrule"))
		  return true;
		String n = name == null ? "" : name.toUpperCase();
		return n.equals("DIRECT") || n.equals("REJECT") || n.equals("REJECT-DROP")
			|| n.equals("GLOBAL") || n.equals("COMPATIBLE") || n.equals("PASS");
	}

	/* 任意 JSON 片段压成简短单行，给日志用。 */
	private static String shortText(Object o) {
		if (o == null)
		  return "(无)";
		String s = String.valueOf(o).replace('\n', ' ');
		return s.length() > 400 ? s.substring(0, 400) + "…" : s;
	}

	/* 保护那次性的 /proxies 拉取（见 ensureProxyNameCache）。 */
	private final Object nameCacheLock = new Object();
	/* 本轮这次拉取已经失败过了：不要再逐节点重试。 */
	private volatile boolean nameCacheFailed = false;

	/* 同一时刻只允许**一轮**测速，并且是**进程级**跟踪的（见 TestProgress）：
	   上一轮还在跑时又起一轮，会弄坏计数，还会往本已繁忙的内核上再压几千个探测。
	   之所以是 static 状态，是因为一轮的生命周期**长于**本页面 —— 用户可能切走再切回来，
	   页面甚至可能被重建 —— 而回来时进度条必须还在。 */

	/* 总时长**不设上限**：每个节点只受自身"单次探测超时"控制，整轮耗时≈各节点探测之和，
	   轮次靠每个节点的超时自然收敛、自行结束（几千上万个代理也一轮测完，绝不会因为
	   "总时长超预算"而被中途截断）。唯一的安全网是 armStallWatchdog：只有内核/桥真死、
	   连续很久零进度时才收尾——那是应对卡死，不是限制总时长。 */
	/* 每完成 N 个节点就把（共享的）节点缓存存一次：一轮测速如果被切页或进程被杀打断，
	   已经做过的部分不能白丢。 */
	private static final int INCREMENTAL_SAVE_EVERY = 15;

	/* 每轮测速只置一次：那个无 TUN 的测速内核已经准备好了。 */
	private volatile boolean corePrepared = false;
	/* 本轮要测的范围（"测速全部"开始时定下）。分组快测要把结果回填到这些节点上，
	   所以留一份给工作线程用；单节点测速时为 null。 */
	private volatile List<ClashNode> roundScope = null;
	/* 上一次完整重筛列表的时间。每收到一个结果就把几百行重排一遍，代价比它省下的
	   等待还大，所以完整刷新做了节流。 */
	private long lastTestUiUpdate = 0;
	/* 已被增量保存落盘的节点数（见 INCREMENTAL_SAVE_EVERY）。 */
	private int lastSavedDone = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		prefs = new Preferences(this);
		/* 恢复上次的视图状态，免得筛选条件又重置成"全部"。 */
		filterCountry = prefs.getSubCountryFilter();
		filterProto = prefs.getSubProtoFilter();
		setContentView(R.layout.activity_subscribe);

		MaterialToolbar toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		fab_test_all = (FloatingActionButton) findViewById(R.id.sub_test_all);
		listview = (ListView) findViewById(R.id.sub_list);
		textview_empty = (TextView) findViewById(R.id.sub_empty);
		textview_stats = (TextView) findViewById(R.id.sub_stats);
		progress_bar = (ProgressBar) findViewById(R.id.sub_progress);
		switch_auto = (SwitchMaterial) findViewById(R.id.sub_auto);
		textview_auto_hint = (TextView) findViewById(R.id.sub_auto_hint);

		countryChips = (ChipGroup) findViewById(R.id.sub_country_chips);
		spinner_proto_filter = (Spinner) findViewById(R.id.sub_proto_filter);
		protoFilterAdapter = new ArrayAdapter<String>(this,
			R.layout.spinner_item_small, filterProtoLabels);
		protoFilterAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_small);
		spinner_proto_filter.setAdapter(protoFilterAdapter);
		spinner_proto_filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (position < 0 || position >= filterProtoTypes.size())
				  return;
				filterProto = filterProtoTypes.get(position);
				prefs.setSubProtoFilter(filterProto);
				applyView();
			}
			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});


		adapter = new NodeAdapter();
		listview.setAdapter(adapter);
		listview.setEmptyView(textview_empty);

		/* 长按节点：把它复制到手动服务器列表，这样一个特定节点就能脱离订阅单独使用。
		   用菜单（与服务器列表同一个范式）把两种意图明确摆出来，
		   而不是默默只做其中一件。 */
		listview.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view,
					int position, long id) {
				ClashNode n = adapter.getItem(position);
				if (n != null)
				  showNodeMenu(n);
				return true;
			}
		});

		/* 必须在挂载 adapter **之前**读入已保存的选择：一挂上 adapter 就会立刻触发
		   onItemSelected(0)，那会把保存的值覆盖成 0。之后再设置选中项则会让监听器
		   以正确的位置重新触发 —— 真正生效的正是这一步。 */


		fab_test_all.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				testAll();
			}
		});
		/* 工具栏菜单只放「使用说明」；订阅地址的增删改查已经移到 设置 → 订阅配置
		   （本页只负责展示合并后的节点与测速）。 */
		toolbar.inflateMenu(R.menu.subscribe_menu);
		toolbar.setOnMenuItemClickListener(new MaterialToolbar.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(android.view.MenuItem item) {
				int id = item.getItemId();
				if (id == R.id.action_help)
				  showHelp();
				else if (id == R.id.action_backup)
				  showBackupManager();
				return true;
			}
		});

		setupAutoSelect();
		loadNodes();
	}

	@Override
	protected void onDestroy() {
		/* 丢掉排队中的测试任务：它们持有这个 Activity，否则会在页面消失之后继续跑
		   （还继续投递 UI 更新）。国别解析同理 —— 它的任务通过 ui.post() 捕获了本页面。 */
		testPool.shutdownNow();
		geoPool.shutdownNow();
		super.onDestroy();
	}



	/* 合并成一份列表，来源是每份订阅各自的缓存。 */
	private void loadNodes() {
		nodes.clear();
		for (Subscription sub : prefs.getSubscriptions()) {
			/* 停用的订阅不会并入隧道，所以也不要进这个合并后的节点列表。 */
			if (!sub.enabled)
			  continue;
			for (ClashNode n : ClashNode.decode(prefs.getSubNodes(sub.id))) {
				/* "合并"功能之前写下的缓存还没有归属信息。 */
				if (n.subId == null || n.subId.isEmpty())
				  n.subId = sub.id;
				nodes.add(n);
			}
		}
		applyView();
		refreshCountryChips();
		refreshProtoFilterSpinner();
	}

	@Override
	protected void onResume() {
		super.onResume();
		/* 本页离开期间测速仍在继续（它的状态是进程级的），所以要显示**当前**进度，
		   而不是从 0% 重新开始。若某一轮的页面已经销毁、最后一个节点没能上报，
		   就在这里把它收掉 —— 这样回到页面时不会被"还在测速中"挡住而无法再测。 */
		if (TestProgress.stale())
		  TestProgress.finish();
		/* 设置里可能改过「显示不可用节点」（或测速内核相关开关），
		   回来时重新筛一遍，让列表立刻反映新的选择。 */
		applyView();
		updateTestProgress();
		/* 预加载那个无 TUN 的测速内核，让第一次测速立刻能开始。「未连接时内核测速」
		   这个开关做的就是这件事：关掉它内核仍会在点测速时按需加载，只是要多等几秒。
		   放在 UI 线程之外 —— 加载内核要花点时间。 */
		if (!prefs.getEnable() && prefs.getPreloadCore()) {
			final android.content.Context app = getApplicationContext();
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						CoreTestHost.ensureReady(app, prefs);
					} catch (Throwable e) {
						/* 预加载只是"让第一次测速快一点"：任何异常都不能从裸线程里逃出去
						   —— 工作线程的未捕获异常会直接杀掉进程。按需加载那条路仍在，
						   点「测速」时会重新尝试。 */
						TProxyService.log("测速: 预加载测试内核失败 " + e);
					}
				}
			}, "test-core-preload").start();
		}
	}

	/* 按筛选 + 排序，从 nodes 重新构造可见列表。 */
	private void applyView() {
		refreshSubNames();
		shown.clear();
		/* 默认视图：只显示**可达**（可用）的节点，按延迟从快到慢排序。
		   「显示不可用节点」（设置 → 订阅）打开后，还会列出不可用（-2）和未测速（-1）
		   的节点，它们排在最后。国家标签和协议下拉框会进一步收窄。 */
		boolean showAll = prefs.getShowUnavailable();
		for (ClashNode n : nodes) {
			if (!showAll && !isAvailable(n))
			  continue;
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
			  continue;
			if (!filterProto.isEmpty() && !filterProto.equals(nodeType(n)))
			  continue;
			shown.add(n);
		}
		Collections.sort(shown, latencyComparator);
		adapter.notifyDataSetChanged();
		updateStats();
	}

	private static boolean isAvailable(ClashNode n) {
		return n.latency >= 0;
	}

	private static boolean isBroken(ClashNode n) {
		return n.latency == -2;
	}

	private static boolean matchesCountry(ClashNode n, String cc) {
		String nodeCc = (n.country == null || n.country.isEmpty()) ? GeoIp.UNKNOWN : n.country;
		return nodeCc.equals(cc);
	}

	/* 节点的代理类型（ss / vmess / trojan / ...），未知时为空串。 */
	private static String nodeType(ClashNode n) {
		return n.type == null ? "" : n.type;
	}

	/* 节点的国家码；还没解析出来时返回 GeoIp.UNKNOWN。 */
	private static String countryCode(ClashNode n) {
		return (n.country == null || n.country.isEmpty()) ? GeoIp.UNKNOWN : n.country;
	}

	/* 排序权重：可用的按延迟排前面，然后是未测速，最后是不可用。 */
	private static long rank(ClashNode n) {
		if (n.latency >= 0)
		  return n.latency;
		if (n.latency == -2)
		  return Long.MAX_VALUE;
		return Long.MAX_VALUE - 1;
	}

	private final Comparator<ClashNode> latencyComparator = new Comparator<ClashNode>() {
		@Override
		public int compare(ClashNode a, ClashNode b) {
			long ra = rank(a);
			long rb = rank(b);
			if (ra < rb)
			  return -1;
			if (ra > rb)
			  return 1;
			return 0;
		}
	};

	/* **当前筛选**（国家 + 协议）能通过的节点。页面上每个数字描述的都是这个范围 ——
	   "共 N 个"必须指用户选中的那些节点，而不是整个合并池。
	   "可用性"筛选**刻意不算**在内：即使「显示不可用节点」关着，
	   统计也得能说出"3 个不可用"。 */
	private List<ClashNode> filteredNodes() {
		List<ClashNode> out = new ArrayList<ClashNode>();
		for (ClashNode n : nodes) {
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
			  continue;
			if (!filterProto.isEmpty() && !filterProto.equals(nodeType(n)))
			  continue;
			out.add(n);
		}
		return out;
	}

	private void updateStats() {
		List<ClashNode> scoped = filteredNodes();
		int ok = 0;
		int bad = 0;
		for (ClashNode n : scoped) {
			if (isAvailable(n))
			  ok++;
			else if (isBroken(n))
			  bad++;
		}
		StringBuilder sb = new StringBuilder(
			getString(R.string.sub_stats, ok, bad, scoped.size()));
		if (shown.size() != scoped.size())
		  sb.append("  ·  ").append(getString(R.string.sub_visible, shown.size()));
		if (TestProgress.pending() > 0) {
			sb.append("  ·  ").append(getString(R.string.sub_testing,
				TestProgress.done(), TestProgress.total(), TestProgress.percent()));
			/* 桥一次只带一个探测，所以一轮的耗时=各探测之和；把实测出来的速度显示出来，
			   用户就知道该等多久，而不是盯着一条几乎不动的进度条。 */
			long eta = TestProgress.etaMs();
			if (eta >= 1000)
			  sb.append("  ·  ").append(getString(R.string.sub_testing_eta, eta / 1000));
		}
		textview_stats.setText(sb.toString());
	}

	private void refreshSubNames() {
		subNames.clear();
		for (Subscription s : prefs.getSubscriptions())
			subNames.put(s.id, s.name);
	}

	/* 每种代理类型固定一种颜色，让列表一眼可读。 */
	private static int protoColor(String type) {
		if (type == null)
		  return 0xFF757575;
		switch (type.toLowerCase()) {
			case "socks5":     return 0xFF607D8B;
			case "ss": case "shadowsocks": return 0xFF009688;
			case "vmess":      return 0xFFFF9800;
			case "vless":      return 0xFF4CAF50;
			case "trojan":     return 0xFFF44336;
			case "hysteria": case "hysteria2": return 0xFF9C27B0;
			case "tuic":       return 0xFF00BCD4;
			case "wireguard":  return 0xFF3F51B5;
			default:           return 0xFF757575;
		}
	}



	/* 重建国家标签行：最前面是「全部」（= 整个节点池），之后每个国家一个标签并带
	   节点数，按节点多的排前面。整行可横向滑动，所以国家再多也不会把布局挤变形。 */
	private void refreshCountryChips() {
		filterCountryCodes.clear();
		filterCountryCodes.add("");
		countryChips.removeAllViews();
		countryChips.addView(makeCountryChip(getString(R.string.sub_country_all), ""));

		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		Map<String, Integer> avail = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			String cc = countryCode(n);
			Integer c = counts.get(cc);
			counts.put(cc, c == null ? 1 : c + 1);
			/* 只有通过延迟测试的节点才算"可用"。 */
			if (n.latency >= 0) {
				Integer a = avail.get(cc);
				avail.put(cc, a == null ? 1 : a + 1);
			}
		}
		List<Map.Entry<String, Integer>> entries =
			new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue().compareTo(a.getValue());
			}
		});
		for (Map.Entry<String, Integer> e : entries) {
			filterCountryCodes.add(e.getKey());
			int total = e.getValue();
			int ok = avail.containsKey(e.getKey()) ? avail.get(e.getKey()) : 0;
			countryChips.addView(makeCountryChip(
				Country.displayWithAvail(e.getKey(), ok, total), e.getKey()));
		}

		int idx = filterCountryCodes.indexOf(filterCountry);
		if (idx < 0) {
			/* 记住的那个国家没了（订阅变了）：退回「全部」，
			   而不是留下一个空列表。 */
			filterCountry = "";
			idx = 0;
		}
		Chip chip = (Chip) countryChips.getChildAt(idx);
		if (chip != null)
		  chip.setChecked(true);
	}

	private Chip makeCountryChip(String label, final String code) {
		Chip chip = new Chip(this);
		chip.setText(label);
		chip.setCheckable(true);
		chip.setClickable(true);
		chip.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				filterCountry = code;
				prefs.setSubCountryFilter(filterCountry);
				/* 国家现在**就是**测速范围：测速内核会用新的池重建
				   （"测速范围 = 你看到的列表"），所以缓存下来的节点名映射必须重新拉取
				   （它会在 UI 线程之外被读，所以才用 volatile 字段）。 */
				proxyNameCache = null;
				nameCacheFailed = false;
				coreNodeCount = 0;
				applyView();
				refreshProtoFilterSpinner();
				/* 隧道的节点池是**启动时**烘焙进 config.yaml 的
				   （MihomoConfig.mergedConfig），所以正在跑的隧道不管本页显示什么，
				   用的都还是**旧**国家的节点。这里直接就地重建它的配置，而不是让用户
				   手动重连 —— 那条"请手动重连"的提示正是流量看起来无视新国家的原因。 */
				if (prefs.getEnable()) {
					startService(new Intent(SubscribeActivity.this, TProxyService.class)
						.setAction(TProxyService.ACTION_RECONNECT));
					Toast.makeText(SubscribeActivity.this,
						R.string.sub_country_filter_applying, Toast.LENGTH_LONG).show();
				}
			}
		});
		return chip;
	}

	/* 用解析到的节点类型重建协议筛选下拉框。第一项是"全部协议"；其余是各代理类型，
	   并带上数量，多的排前面。
	   数量统计的是**列表实际显示的内容**（见下面那段循环）：先按选中的国家标签收窄，
	   再按「显示不可用节点」过滤 —— 关着时只有可用节点计入，开着时不可用 / 未测速的
	   也计入，所以它和用户眼前那份列表永远一致。 */
	private void refreshProtoFilterSpinner() {
		filterProtoTypes.clear();
		filterProtoLabels.clear();
		filterProtoTypes.add("");
		filterProtoLabels.add(getString(R.string.sub_proto_all));
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		for (ClashNode n : nodes) {
			/* 与 applyView() 完全一致地按选中的国家收窄范围。 */
			if (!filterCountry.isEmpty() && !matchesCountry(n, filterCountry))
				continue;
			String t = nodeType(n);
			if (t.isEmpty())
			  continue;
			/* 统计"列表实际显示的内容"：「显示不可用节点」打开时不可用节点也会列出来，
			   所以它们也该计入协议数量。 */
			if (n.latency < 0 && !prefs.getShowUnavailable())
			  continue;
			Integer c = counts.get(t);
			counts.put(t, c == null ? 1 : c + 1);
		}
		List<Map.Entry<String, Integer>> entries =
			new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
		Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
			@Override
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue().compareTo(a.getValue());
			}
		});
		for (Map.Entry<String, Integer> e : entries) {
			filterProtoTypes.add(e.getKey());
			filterProtoLabels.add(e.getKey() + " (" + e.getValue() + ")");
		}
		protoFilterAdapter.notifyDataSetChanged();
		int idx = filterProtoTypes.indexOf(filterProto);
		if (idx < 0)
		  idx = 0;
		spinner_proto_filter.setSelection(idx);
	}



	/* 测速结果属于节点来源的那份订阅，所以按来源分组写回各自的缓存。
	   实现上是**单趟分桶 + 一次写入**：原来对每份订阅都把 nodes 全扫一遍（订阅数 × 节点数），
	   并且每份订阅各 commit 一次 —— 而一轮测速每 15 个节点就会调一次这里。 */
	private void saveNodes() {
		Map<String, List<ClashNode>> bySub = new LinkedHashMap<String, List<ClashNode>>();
		for (Subscription sub : prefs.getSubscriptions())
		  bySub.put(sub.id, new ArrayList<ClashNode>());
		for (ClashNode n : nodes) {
			List<ClashNode> mine = bySub.get(n.subId);
			if (mine != null)
			  mine.add(n);
		}
		Map<String, String> encoded = new LinkedHashMap<String, String>();
		for (Map.Entry<String, List<ClashNode>> e : bySub.entrySet())
		  encoded.put(e.getKey(), ClashNode.encode(e.getValue()));
		prefs.setSubNodesAll(encoded);
	}

	private void testAll() {
		if (nodes.isEmpty()) {
			updateTestProgress();
			return;
		}
		/* 先把"页面已销毁、永远报不出最后一个节点"的那一轮收掉，再判断是否有活着的
		   一轮在跑。状态是进程级的，所以即使上一轮属于一个已经不存在的 Activity 实例，
		   这里也能正确处理。 */
		if (TestProgress.stale())
		  TestProgress.finish();
		if (TestProgress.running()) {
			updateTestProgress();
			TProxyService.log("测速: 上一轮还没结束，拒绝开始新的测速");
			Toast.makeText(this, R.string.sub_test_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		/* 测的正是统计所描述的那个范围（见 filteredNodes）：国家标签 + 协议下拉框。
		   「全部」= 所有节点，选某国 = 该国节点，而测速内核也是用**同一个**池构建的 ——
		   所以"你看到的就是被测的"，列表里的节点绝不会"在内核里找不到"
		   （这也是旧的「测速并入全量节点」开关被去掉的原因）。 */
		List<ClashNode> toTest = filteredNodes();
		if (toTest.isEmpty()) {
			/* 这里要是静默返回，按钮看起来就像坏了。 */
			updateTestProgress();
			TProxyService.log("测速: 当前筛选（"
				+ (filterCountry.isEmpty() ? "全部" : filterCountry) + "）没有匹配到节点");
			Toast.makeText(this, R.string.sub_test_empty_scope, Toast.LENGTH_SHORT).show();
			return;
		}
		/* 已知最快的先测：节点有几百个时预算**一定**会用完，这样用户关心的节点能先
		   拿到结果。 */
		Collections.sort(toTest, latencyComparator);
		/* 分组快测要把结果回填到节点上，所以把这轮的范围留给工作线程。 */
		roundScope = toTest;
		/* 启动这个**进程级**的测速轮次。它是**异步**的：离开本页、甚至本 Activity 被
		   重建，都不会让它停下；用户回来时进度仍在。 */
		/* 第三参数是"时间预算"：传 0 表示不限（TestProgress.isCurrent 把 d<=0 当作不限），
		   整轮只受单节点超时控制，几千上万个节点也能一轮测完。 */
		final int gen = TestProgress.begin(toTest.size(), 0, true);
		if (gen < 0) {
			updateTestProgress();
			Toast.makeText(this, R.string.sub_test_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		/* 运行中的配置可能已经变了（订阅被编辑 / 重新选择过）：所以节点名映射要重建，
		   api 主机也要从头再探一次。 */
		proxyNameCache = null;
		nameCacheFailed = false;
		apiHost = null;
		apiHostChecked = false;
		coreNodeCount = 0;
		passNotFound.set(0);
		passSkipped.set(0);
		passTimeoutFail.set(0);
		corePrepared = false;
		lastTestUiUpdate = 0;
		lastSavedDone = 0;
		probeMsTotal.set(0);
		probeCount.set(0);
		slowestMs.set(0);
		slowestName = null;
		passLogged.clear();
		updateTestProgress();
		/* 轮次头：足够还原"某个节点从可用翻成不可用"那一轮的上下文（哪个内核应答的、
		   等了多久、用的哪个地址、这轮最多能跑多久）。
		   批量测速下每个探测只在**失败**时记一行（见 CoreTestHost）：否则上千个节点就是
		   上千行，而每行都要开关一次文件 —— 光是这点 I/O 就足以造成那种"卡住"的观感。 */
		CoreTestHost.setVerbose(false);
		int capSec = prefs.getProxyTestTimeout();
		TProxyService.log("=== 测速开始：" + toTest.size() + " 个节点 · VPN="
			+ (prefs.getEnable() ? "已连接" : "未连接")
			+ " · 单次探测超时 " + capSec + "s"
			+ " · 时间预算 不限制（仅按单节点超时控制） · 测速地址 "
			+ prefs.getAutoTestUrl() + " · 内置目标 " + PROXY_TEST_URLS.length + " 个"
			+ " · 国家筛选=" + (prefs.getSubCountryFilter().isEmpty()
				? "全部" : prefs.getSubCountryFilter())
			+ " · 无响应=" + (prefs.getProbeTimeoutAsFail() ? "不可用" : "未测速") + " ===");
		for (ClashNode n : toTest)
		  testNode(n, true, gen);
		/* 卡住看门狗。旧版只是 60 秒查一次、而且只在"一个结果都没有"时才触发，
		   所以跑到几个结果之后才卡住的那种（用户报的"卡在 7%"）会永远僵在那里、
		   按钮也一直是灰的。这一版盯的是**完成数**：连续 `stallMs` 没有新结果就算卡住，
		   不管这一轮已经跑到哪儿。 */
		armStallWatchdog(gen);
	}

	/* 测速进行期间每 15 秒重新排一次。`stallMs` 由"单次探测超时"推导而来，所以一个
	   合理地慢的节点（在超时之内）绝不会被误判成卡住。相关字段只在 UI 线程上访问。 */
	private long watchdogDone = -1;
	private long watchdogAt = 0;

	private void armStallWatchdog(final int gen) {
		/* 下限给到 120s：几千上万个代理时，测速内核加载并应用大配置可能要几十秒，
		   首个结果出来前别误判成"卡住"。单节点正常探测 ≤ 单次探测超时，不会触发这里。 */
		final long stallMs = Math.max(120000L, prefs.getProxyTestTimeout() * 1000L + 15000L);
		ui.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (!TestProgress.running() || TestProgress.pending() <= 0)
				  return;                     /* 这一轮已经结束 */
				if (!TestProgress.isCurrent(gen))
				  return;                     /* 这一轮已被替换 / 已过期 */
				if (TestProgress.done() != watchdogDone) {
					watchdogDone = TestProgress.done();
					watchdogAt = System.currentTimeMillis();
				} else if (System.currentTimeMillis() - watchdogAt >= stallMs) {
					TProxyService.log("测速: " + (stallMs / 1000L)
						+ " 秒没有新结果（已完成 " + TestProgress.done() + "/"
						+ TestProgress.total() + "）→ 判定卡住，结束本轮，剩余按未测速处理"
						+ "（看上面的 bridge/delay 行：内核或桥没有回应）");
					releasePass();
					updateTestProgress();
					Toast.makeText(SubscribeActivity.this, R.string.sub_test_stalled,
						Toast.LENGTH_LONG).show();
					return;
				}
				armStallWatchdog(gen);
			}
		}, 15000);
	}

	/* 一轮结束：释放那个进程级标志并恢复正常日志级别。最后一个节点上报后立即调用，
	   看门狗会调用它，页面被重建时 onResume 发现轮次失联也会调用它。 */
	private void releasePass() {
		TestProgress.finish();
		CoreTestHost.setVerbose(true);
	}

	/* 进度：卡片里的进度条 + 统计行；测速进行中直接把 FAB 置灰禁用
	   （它本身不带文字标签）。 */
	private void updateTestProgress() {
		/* 从进程级的状态里读，这样在测速途中被重建的页面显示的是**真实**进度，
		   而不是从 0% 开始。 */
		boolean running = TestProgress.pending() > 0;
		fab_test_all.setEnabled(!running);
		if (progress_bar != null) {
			if (running) {
				progress_bar.setMax(Math.max(1, TestProgress.total()));
				progress_bar.setProgress(TestProgress.done());
				progress_bar.setVisibility(View.VISIBLE);
			} else {
				progress_bar.setVisibility(View.GONE);
			}
		}
		updateStats();
	}

	/* 加载/刷新那个无 TUN 的测速内核，好让延迟测试用上内核的**真实转发**延迟。
	   本进程里有可用的测速内核时返回 true。跑在线程池的线程上（加载内核要花点时间）。
	   **连不连 VPN 都走它**：只有它的节点池是按**当前**国家筛选重建的；隧道内核的池是
	   连接那一刻烘焙进去的，换过国家标签之后就和列表对不上了。 */
	private boolean prepareTestCore() {
		try {
			boolean ok = CoreTestHost.ensureReady(this, prefs);
			if (ok) {
				logOnce("core-path", "测速: 使用测试内核（跟随当前国家筛选）");
				return true;
			}
			/* 测速内核不可用：退回通过控制接口走隧道内核 —— 至少能覆盖它启动时那一个
			   节点池。 */
			if (prefs.getEnable()) {
				logOnce("core-path", "测速: 测试内核不可用 → 回退隧道内核的 /delay（其节点池"
					+ "为连接时的那一份，换过国家筛选可能不全）");
				return false;
			}
			logOnce("core-path", "测速: 测试内核不可用（未连接 VPN，无回退）→ 本次按未测速处理");
			return false;
		} catch (Throwable e) {
			TProxyService.log("测速: 准备测试内核异常 " + e);
			return false;
		}
	}

	/* 在工作线程池启动**之前**串行做一次探测，用的是第一个真正能解析成内核节点的节点。
	   它在单线程上把三件事定下来：桥的载荷容器、testDelay 的参数形状、节点名映射 ——
	   三者都是共享的**一次性闩锁**，抢跑在单节点测试里看不出来，却会让整批全废。
	   当有人报"测速不准/全废"时，它的结果也是日志里最清楚的一行。 */
	private void delaySelfTest(boolean coreOk) {
		try {
			if (nodes.isEmpty())
			  return;
			ensureProxyNameCache();
			ClashNode pick = null;
			for (ClashNode x : nodes) {
				if (realNodeName(x) != null) {
					pick = x;
					break;
				}
			}
			if (pick == null)
			  pick = nodes.get(0);
			String name = realNodeName(pick);
			if (name == null)
			  name = pick.name;
			if (name == null || name.isEmpty())
			  return;
			/* 自检要短：它只需要证明"管道通了"，不必去测列表里最慢的那个节点。
			   它跑在**任何**节点被测之前，所以这里多花的每一秒，都是进度条冻在 0% 的一秒。 */
			int probeMs = prefs.getProxyTestTimeout() * 1000;
			long t0 = System.currentTimeMillis();
			Long d = CoreTestHost.testDelay(name, prefs.getAutoTestUrl(), probeMs);
			TProxyService.log("测速自检：" + (d == null
				? "无判定（看上面 bridge/delay 行，多为参数或内核问题）"
				: (d >= 0 ? ("可用 " + d + "ms") : "该节点不可用(-2)"))
				+ " · 节点 " + name + " · 内核=" + (coreOk ? "测试内核" : "隧道内核(REST)")
				+ " · 可测节点 " + coreNodeCount + " 个 · 耗时 "
				+ (System.currentTimeMillis() - t0) + "ms");
		} catch (Throwable e) {
			TProxyService.log("测速自检异常 " + e);
		}
	}

	/* 分组快测（见 CoreTestHost.testGroups）：让内核**并发**拨测整池 —— 一次调用拿回
	   一整组的延迟。桥只允许一个在途回调，所以逐节点探测天然串行（一轮≈各节点之和）；
	   这一步能把"大多数活着的节点"用几秒到几十秒一次量完，剩下的（失败 / 未命中 /
	   内核不支持分组）仍旧走原来的逐节点流程。

	   关键：这里**只补充"可用"结论** —— 不写任何"不可用"，所以判定口径与结果语义
	   跟以前完全一致，只是省掉了大部分串行探测。 */
	private void fastGroupPhase(int gen, boolean coreOk) {
		List<ClashNode> scope = roundScope;
		if (!coreOk || scope == null || scope.isEmpty())
		  return;
		/* 快测组只存在于**测试内核**里；走隧道内核（REST）时没有它们。 */
		int probeMs = prefs.getProxyTestTimeout() * 1000;
		long t0 = System.currentTimeMillis();
		java.util.Map<String, Long> hits =
			CoreTestHost.testGroups(prefs.getAutoTestUrl(), probeMs, gen);
		if (hits == null || hits.isEmpty()) {
			TProxyService.log("测速: 分组快测没拿到结果 → 全部走逐个探测");
			return;
		}
		int matched = 0;
		int coreFailed = 0;
		for (ClashNode n : scope) {
			if (!TestProgress.isCurrent(gen))
			  break;
			/* 组里的成员名是内核配置里的名字（可能被去重成 "name (2)"），所以先用
			   现有的映射把它换出来；换不到再按原名试一次。 */
			String key = realNodeName(n);
			Long d = hits.get(key == null ? n.name : key);
			if (d == null)
			  d = hits.get(n.name);
			if (d == null)
			  continue;
			if (d <= 0) {
				/* 内核自己判为失败（0 = 该成员的组健康检查没过）。**不**据此写结论 ——
				   组的期望状态码与 testDelay 的口径不完全一致，这里只统计个数，让下面的
				   日志能说清"剩下的串行尾巴有多长"。 */
				coreFailed++;
				continue;
			}
			n.latency = d;
			n.testedGen = gen;
			matched++;
		}
		TProxyService.log("测速: 分组快测命中 " + matched + "/" + scope.size() + " 个节点 · 耗时 "
			+ (System.currentTimeMillis() - t0) + "ms · 内核判为失败待复核 " + coreFailed
			+ " 个（它们仍会逐个探测；判定口径不变）");
	}

	/* batch = 属于"测速全部"；gen = 这个节点所属的轮次，这样失联轮次残留的活儿会自己停下。 */
	private void testNode(final ClashNode n, final boolean batch, final int gen) {
		Runnable task = new Runnable() {
			@Override
			public void run() {
				/* 这一轮可能已经结束了（预算耗尽、看门狗收尾，或被新的一轮替换掉）：
				   这时**不探测**，直接把这个节点报成未测速，让这轮快速排空，
				   而不是去跟一个它已经不再拥有的内核较劲。 */
				final boolean expired = !TestProgress.isCurrent(gen);
				/* 确保有内核可用于"真实转发"测试：未连接时（或用户要求测全部节点时）
				   用那个无 TUN 的测速内核，它同时会刷新自己的节点集。
				   只有这一轮的第一个任务会真正干活。 */
				if (!expired && !corePrepared) {
					synchronized (SubscribeActivity.this) {
						if (!corePrepared) {
							long t0 = System.currentTimeMillis();
							boolean coreOk = prepareTestCore();
							/* 在工作线程池铺开**之前**，由**单线程**把桥的载荷容器、
							   delay 动作的参数形状和节点名映射定下来。这三者都是共享的
							   一次性闩锁，抢跑它们正是"单节点测速好好的、测速全部全废"
							   的原因。 */
							delaySelfTest(coreOk);
							/* 内核就绪后先做一次**分组快测**：让内核并发把整池量一遍
							   （一次调用一组），比逐节点串行探测快一个数量级。它只补充
							   "可用"结论，失败/未命中的节点照旧走逐个探测。 */
							if (batch)
							  fastGroupPhase(gen, coreOk);
							corePrepared = true;
							/* 要记一行，因为这是用户眼中"什么都没发生"的**死时间**：
							   加载 + 应用配置（几百个节点）再加上自检探测。 */
							TProxyService.log("测速: 内核准备耗时 "
								+ (System.currentTimeMillis() - t0) + "ms");
						}
					}
				}
				/* 判定**只**来自内核真的把一个请求从**这个**节点推出去（即隔离式的
				   "testDelay" 探测）。这才能证明代理确实在转发流量 —— 光是 TCP 端口能连上
				   **不算**，所以那个 socket 探测被删掉了。真实测试跑不起来时（没有内核，
				   或该节点不在已加载的配置里），就让它保持"未验证"（-1），而不是
				   "不可用"。 */
				long probeT0 = System.currentTimeMillis();
				/* 分组快测（见 fastGroupPhase）已经量过这个节点就直接采用它的结果 ——
				   同样是内核自己测出的真实转发延迟，只是由内核并发完成，不占桥的串行槽位。 */
				Long real = (n.testedGen == gen && n.latency >= 0)
					? Long.valueOf(n.latency)
					: (expired ? null : proxyDelayMs(n));
				long probeCost = System.currentTimeMillis() - probeT0;
				if (!expired) {
					probeCount.incrementAndGet();
					probeMsTotal.addAndGet(probeCost);
					if (probeCost > slowestMs.get()) {
						slowestMs.set(probeCost);
						slowestName = n.name;
					}
					/* 只记异常慢的那些，这样日志仍然可读，但又能回答"某个节点为什么慢"。 */
					if (probeCost >= 2000)
					  TProxyService.log("测速: " + n.name + " 探测耗时 " + probeCost
						+ "ms（含多目标尝试与救援）");
				}
				if (real != null)
				  n.latency = real;        // >=0 可用，-2 无法建立隧道
				else
				  n.latency = -1;          // 无法验证 -> 未知，而不是"可用"
				if (expired) {
					passSkipped.incrementAndGet();
					logOnce("expired", "测速: 本轮已结束（被新轮替换或已停止），剩余节点按未测速处理");
				}
				/* 逐节点判定。判为"不可用"的**每个**节点都会记一行（那正是要追查的
				   现象）；而"未测速"每轮只记一次，因为它通常是**整轮级**的情况（没有内核），
				   否则会每个节点都打一遍。 */
				if (real != null && real < 0)
				  TProxyService.log("测速结果: " + n.name + " [" + n.server + ":" + n.port
					+ " " + (n.type == null ? "?" : n.type) + "] -> 不可用(-2)");
				else if (real == null)
				  logOnce("untested", "测速结果: 无判定 → 未测速，例如 " + n.name + " ["
					+ n.server + ":" + n.port + "]");
				/* **先**上报结果，**再**查国别。国别查询需要 DNS，而一个慢解析器以前会把
				   整个"0% → 什么都不动"的状态顶住：进度计数只由下面的 post 推进，
				   所以域名解析慢爬时进度条就冻着。国别只是装饰（国旗 + 标签），延迟才是正事。 */
				ui.post(new Runnable() {
					@Override
					public void run() {
						/* 即使本页已经消失，也要在**进程级**状态里计数：这一轮仍在继续
						   （这正是设计目的），而它下次被哪个页面展示，进度都必须是对的。 */
						TestProgress.nodeDone(gen);
						/* 测速过程中持续更新共享缓存：切页或进程被杀，都不能把已经测出来的
						   成果全丢掉。（按**计数差**判断而不是取模：4 个 worker 并发完成节点，
						   N 的整数倍可能被直接跳过。） */
						int doneNow = TestProgress.done();
						if (doneNow - lastSavedDone >= INCREMENTAL_SAVE_EVERY) {
							lastSavedDone = doneNow;
							saveNodes();
						}
						/* 页面可能已经销毁（切页时这一轮还在跑）：**进程级**收尾不能因此
						   跳过，见下面 releasePass() 的说明。 */
						final boolean pageGone = isFinishing() || isDestroyed();
						if (!pageGone) {
							updateTestProgress();
							if (batch) {
								/* 每出一个结果就显示一个；而完整重筛（含排序）做了节流。 */
								long now = System.currentTimeMillis();
								if (now - lastTestUiUpdate >= 400) {
									lastTestUiUpdate = now;
									applyView();
								} else {
									adapter.notifyDataSetChanged();
									updateStats();
								}
							}
						}
						if (TestProgress.pending() > 0)
						  return;               /* 还有节点没报完 */
						/* 最后一个节点已上报：**不管页面还在不在**都要把这一轮收干净 ——
						   releasePass() 会恢复 CoreTestHost 的日志级别并清掉进程级的 RUNNING
						   标记；少了它，下次进页面会被"上一轮还没结束"挡住，而且之后的测速
						   也不再写逐节点日志（那是排查问题的唯一线索）。 */
						saveNodes();
						releasePass();
						if (pageGone)
						  return;               /* 页面没了：下面全是重绘与提示 */
						refreshCountryChips();
						refreshProtoFilterSpinner();
						applyView();
						updateTestProgress();
						if (batch) {
							int ok = 0;
							int bad = 0;
							int un = 0;
							/* 与屏幕上显示的数字用**同一个范围**。 */
							for (ClashNode x : filteredNodes()) {
								if (x.latency >= 0)
								  ok++;
								else if (x.latency == -2)
								  bad++;
								else
								  un++;
							}
							int skipped = passSkipped.get();
							TProxyService.log("=== 测速结束：可用 " + ok + " / 不可用 "
								+ bad + " / 未测速 " + un + (skipped > 0
									? ("（其中 " + skipped + " 个因本轮提前结束未测）") : "")
								+ " · 详见上面每行「测速:」 ===");
							/* 时间都花在哪了。动作桥一次只带**一个**在途调用，所以一轮
							   的耗时=各探测之和 —— 这一行就是证据。 */
							int cnt = probeCount.get();
							TProxyService.log("测速耗时: 本轮共 "
								+ (TestProgress.elapsedMs() / 1000) + "s · 实际探测 " + cnt
								+ " 次/共 " + (probeMsTotal.get() / 1000) + "s · 平均 "
								+ (cnt > 0 ? (probeMsTotal.get() / cnt) : 0) + "ms/节点 · 最慢 "
								+ (slowestName == null ? "-" : slowestName) + " "
								+ slowestMs.get() + "ms（桥为单回调，整轮≈各探测之和）");
							/* 一个都没测出来：要明确说出来，而不是给用户留一个毫无变化的
							   列表、连个原因都没有。 */
							int missing = passNotFound.get();
							int timedOut = passTimeoutFail.get();
							if (ok == 0 && bad > 0 && timedOut >= bad) {
								/* **所有**失败都来自"完全没有应答"：那是整轮的管道问题
								   （内核 / 桥），而不是几百个节点全死了。 */
								TProxyService.log("测速: 本轮 " + bad + " 个「不可用」全部来自"
									+ "「无响应」（按设置判为不可用）——整轮无一节点有过响应，"
									+ "通常是内核/桥异常，而不是所有节点都死了；"
									+ "看上面的 bridge/delay 行确认");
								if (!pageGone)
								  Toast.makeText(SubscribeActivity.this,
									R.string.sub_test_all_timeout, Toast.LENGTH_LONG).show();
							} else if (ok == 0 && bad == 0 && un > 0) {
								if (!pageGone)
								  Toast.makeText(SubscribeActivity.this,
									R.string.sub_test_no_core, Toast.LENGTH_LONG).show();
							} else if (missing > 0) {
								if (!pageGone)
								  Toast.makeText(SubscribeActivity.this,
									getString(R.string.sub_test_missing_nodes, missing),
									Toast.LENGTH_LONG).show();
							} else if (skipped > 0) {
								if (!pageGone)
								  Toast.makeText(SubscribeActivity.this,
									getString(R.string.sub_test_budget_out, skipped),
									Toast.LENGTH_LONG).show();
							}
						}
					}
				});
				/* 国别放**最后**，而且放到**独立线程**上：它只是装饰（国旗 + 标签），
				   但每台主机可能耗掉好几秒（3 秒 DNS 上限再加两次 HTTP 兜底）。之前在这个
				   工作线程上做，等于让**下一个**节点等它 —— 几百个节点光这一项就把一轮
				   拖长几分钟。只在国别仍未知时才解析；重复失败在 GeoIp 内部有退避。 */
				if (!expired && (n.country == null || n.country.isEmpty()
						|| GeoIp.UNKNOWN.equals(n.country)))
				  resolveCountryAsync(n);
			}
		};
		/* 提交必须防一手：页面销毁时 onDestroy() 会 shutdownNow() 掉这个池，而**正在
		   跑的**任务仍会走到这里（以及 resolveCountryAsync 里的 geoPool）。往已关闭的池
		   提交会抛 RejectedExecutionException —— 它是 RuntimeException，无论从主线程还是
		   从工作线程逃出去都是**进程级未捕获异常**，直接闪退。这正是"测速全部时切到别的
		   页面就闪退"的原因，所以这里必须接住。 */
		try {
			testPool.execute(task);
		} catch (Throwable e) {
			TProxyService.log("测速: 任务被拒（线程池已关闭，页面已销毁）→ 跳过节点 "
				+ n.name + " · " + e);
		}
	}

	/* 在专用的解析线程上解析一个节点的国别，得到结果后再重绘，
	   这样刚测完这个节点的工作线程能立刻去测下一个。 */
	private void resolveCountryAsync(final ClashNode n) {
		Runnable task = new Runnable() {
			@Override
			public void run() {
				String resolved;
				try {
					resolved = GeoIp.countryOf(prefs, n.server, true);
				} catch (Throwable e) {
					/* 解析线程被 shutdownNow() 中断、或 DNS / HTTP 层抛出意外异常：
					   国别只是装饰（国旗 + 标签），绝不该让工作线程的未捕获异常把整个
					   进程带走。 */
					TProxyService.log("测速: 国别解析异常 " + e + " host=" + n.server);
					return;
				}
				final String cc = resolved;
				if (cc == null || cc.isEmpty() || GeoIp.UNKNOWN.equals(cc)
						|| cc.equals(n.country))
				  return;
				ui.post(new Runnable() {
					@Override
					public void run() {
						if (isFinishing() || isDestroyed())
						  return;
						n.country = cc;
						/* 先重绘本行的国旗；**落盘 + 刷新国家标签**要等这一轮结束再做，
						   因为本次查询进行期间，收尾流程可能已经保存过一遍了。 */
						adapter.notifyDataSetChanged();
						if (!TestProgress.running()) {
							refreshCountryChips();
							saveNodes();
							/* 国别写入是合并式的（约每 2 秒才写一次 preferences）；
							   现在这一轮结束了，把剩余的补写出去，好让下次生成配置时每个节点
							   都能分到正确的国家。 */
							prefs.flushCountryMap();
						}
					}
				});
			}
		};
		try {
			geoPool.execute(task);
		} catch (Throwable e) {
			/* geoPool 已被 onDestroy 关闭：丢掉这次解析即可 —— 国别下次进页面会重新解析
			   或用缓存，没有任何损失，但让异常逃出去就是一次闪退。 */
		}
	}

	/* 把所有可用节点（延迟 >= 0，跨全部订阅）备份。目的地分两种：
	   - WebDAV 已配置（开关开**且**填了地址）→ 上传到远端（见 WebDav.uploadBackup）；
	     远端失败则回退到本地订阅，免得数据丢了。
	   - 否则 → 落一条本地订阅（原行为）：节点各自的原始 clash 块，不依赖机场地址，
	     默认停用以免和来源订阅合并后节点翻倍。 */
	private void backupAvailableNodes() {
		List<ClashNode> available = new ArrayList<ClashNode>();
		for (ClashNode n : nodes)
		  if (n.latency >= 0)
			available.add(n);
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
			new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
				.format(new java.util.Date()));
		if (prefs.webdavReady()) {
			uploadBackupRemote(sb.toString(), kept, missed, name);
		} else {
			/* 本地备份：存好后直接问要不要"用它覆盖当前订阅"，把"备份 → 恢复"一步走完。 */
			final String id = Subscription.newId();
			saveBackupLocal(sb.toString(), id, name, kept, missed);
			offerApplyBackup(id, name, kept.size());
		}
	}

	/* 本地备份刚存好：问用户是"仅保存"还是"应用此备份（覆盖当前订阅）"。 */
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
			.show();
	}

	/* 用某条本地备份覆盖当前订阅：启用它、停用其它订阅，于是节点池只由它提供。
	   运行中的隧道若已连接，立即重建配置让覆盖生效。 */
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

	/* 备份管理：列出所有本地备份（本地内容订阅），点一条可"恢复（覆盖当前订阅）"或"删除"；
	   顶部按钮可"备份当前可用节点"。这样备份不再是一锤子买卖，事后还能挑某份恢复。 */
	private void showBackupManager() {
		List<Subscription> all = prefs.getSubscriptions();
		List<Subscription> backups = new ArrayList<Subscription>();
		for (Subscription s : all)
		  if (s.local)
			backups.add(s);

		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setTitle(R.string.backup_manager_title);
		b.setNegativeButton(android.R.string.cancel, null);
		b.setPositiveButton(R.string.backup_now, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface d, int which) {
				/* 备份逻辑自带提示/询问，这里直接调起。 */
				backupAvailableNodes();
			}
		});
		if (backups.isEmpty()) {
			b.setMessage(R.string.backup_manager_empty);
		} else {
			CharSequence[] items = new CharSequence[backups.size()];
			for (int i = 0; i < backups.size(); i++) {
				Subscription s = backups.get(i);
				int count = ClashNode.decode(prefs.getSubNodes(s.id)).size();
				items[i] = s.label() + (count > 0 ? "  ·  " + count + " 个节点" : "");
			}
			final List<Subscription> fixed = backups;
			b.setItems(items, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					showBackupItemMenu(fixed.get(which));
				}
			});
		}
		b.show();
	}

	/* 单条备份的后续操作：恢复（覆盖当前订阅）或删除。 */
	private void showBackupItemMenu(final Subscription sub) {
		int count = ClashNode.decode(prefs.getSubNodes(sub.id)).size();
		new AlertDialog.Builder(this)
			.setTitle(sub.label() + (count > 0 ? "  ·  " + count + " 个节点" : ""))
			.setItems(new CharSequence[] {
				getString(R.string.backup_restore),
				getString(R.string.backup_delete)
			}, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface d, int which) {
					if (which == 0)
					  applyLocalOverride(sub.id, sub.name);
					else
					  deleteBackup(sub.id);
				}
			})
			.show();
	}

	/* 删除一条本地备份：同时清掉它的原始内容缓存与节点缓存。 */
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
		/* 删完重新打开管理列表，让用户立刻看到变化。 */
		showBackupManager();
	}

	/* 本地备份：原行为。把可用节点存成一条默认停用的本地订阅，并顺手写好节点缓存
	   （副本，不入 subId 改动页面的标签），让「订阅配置」立刻显示 "本地内容 · N 个节点"。 */
	private void saveBackupLocal(String yml, String id, String name, List<ClashNode> kept, int missed) {
		List<Subscription> list = prefs.getSubscriptions();
		list.add(new Subscription(id, name, "", false, true));
		prefs.setSubscriptions(list);
		prefs.setSubRaw(id, yml);
		/* 顺手存好节点缓存（**副本**，不拿页面上的对象去改 subId —— 那会让列表里的
		   "来自哪份订阅"标签跟着变），这样在「订阅配置」里立刻显示 "本地内容 · N 个节点"，
		   不必先启用再拉取。 */
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

	/* 远程备份：工作线程上传到 WebDAV；失败则回退本地并提示（避免数据丢失）。
	   成功时**只**传远端、不写本地订阅 —— 否则两端各一份、列表里会重复。 */
	private void uploadBackupRemote(final String yml, final List<ClashNode> kept,
			final int missed, final String name) {
		Toast.makeText(this, R.string.backup_uploading, Toast.LENGTH_SHORT).show();
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					final String fileName = WebDav.uploadBackup(prefs, yml);
					runOnUiThread(new Runnable() {
						@Override public void run() {
							TProxyService.log("订阅备份: 已上传 " + kept.size() + " 个可用节点到 WebDAV · "
								+ fileName + (missed > 0 ? ("（" + missed + " 个被跳过）") : ""));
							Toast.makeText(SubscribeActivity.this,
								getString(R.string.backup_upload_done, fileName, kept.size()),
								Toast.LENGTH_LONG).show();
						}
					});
				} catch (final IOException e) {
					/* 回退：远端没存成，先把本地订阅建好，再告诉用户远端失败了。 */
					runOnUiThread(new Runnable() {
						@Override public void run() {
							saveBackupLocal(yml, Subscription.newId(), name, kept, missed);
							Toast.makeText(SubscribeActivity.this,
								getString(R.string.backup_upload_failed, e.getMessage()),
								Toast.LENGTH_LONG).show();
						}
					});
				}
			}
		}).start();
	}

	/* 订阅节点的长按菜单：把它加入手动服务器列表 —— 可以只保存，也可以保存并设为当前
	   使用的上游。 */
	private void showNodeMenu(final ClashNode n) {
		final String[] items = {
			getString(R.string.sub_add_server_only),
			getString(R.string.sub_add_server_enable),
		};
		new AlertDialog.Builder(this)
			.setTitle(n.name)
			.setItems(items, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					addNodeToServers(n, which == 1);
				}
			})
			.show();
	}

	/* 把一个订阅节点复制进手动服务器列表。SOCKS5 用普通的表单字段即可；其它协议必须
	   原样保留它的 clash 节点块（这是保住 uuid / sni / ws-opts / ... 的唯一办法），
	   而节点块是从订阅的原始内容里还原出来的。
	   enableNow 还会把它设为**当前使用的上游**（此时订阅会被忽略），
	   所以正在跑的隧道会立即重建。 */
	private void addNodeToServers(final ClashNode n, boolean enableNow) {
		List<SocksServer> list = prefs.getSocksServers();
		SocksServer existing = null;
		for (SocksServer s : list) {
			if (n.name.equals(s.name) && n.server.equals(s.addr) && n.port == s.port) {
				existing = s;
				break;
			}
		}
		if (existing == null) {
			boolean socks = "socks5".equals(n.type == null ? "" : n.type);
			String raw = socks ? "" : findRawProxy(n);
			if (!socks && (raw == null || raw.isEmpty())) {
				/* 失败要说清是**哪一步**失败（订阅原文里找不到这一段）。日志里带上
				   subId 与原文长度，好区分"订阅没拉取/被清空"和"节点名对不上"。 */
				String src = prefs.getSubRaw(n.subId);
				TProxyService.log("服务器: 无法还原节点「" + n.name + "」的原始定义 · type="
					+ n.type + " · subId=" + n.subId + " · 该订阅原文="
					+ (src == null ? "缺失" : src.length() + " 字节"));
				Toast.makeText(this, R.string.sub_add_to_server_failed, Toast.LENGTH_LONG).show();
				return;
			}
			existing = new SocksServer(SocksServer.newId(), n.name, n.server, n.port,
				n.username, n.password, socks ? "socks5" : n.type, raw);
			list.add(existing);
			prefs.setSocksServers(list);
			/* 记一行：在 设置 → 日志 里能确认这一步到底搬走了什么 ——
			   原始块 0 字节 = 按 SOCKS5 表单收的，非 0 = 整块原样搬运。 */
			TProxyService.log("服务器: 已加入「" + n.name + "」（type="
				+ (socks ? "socks5" : n.type) + "，原始块 "
				+ (raw == null ? 0 : raw.length()) + " 字节）");
		}
		if (!enableNow) {
			Toast.makeText(this, getString(R.string.sub_add_to_server, n.name),
				Toast.LENGTH_LONG).show();
			return;
		}
		/* 启用后上游交给这台服务器：从此订阅被忽略，所以正在跑的隧道必须重建配置。 */
		prefs.setActiveSocksId(existing.id);
		if (prefs.getEnable()) {
			startService(new Intent(this, TProxyService.class)
				.setAction(TProxyService.ACTION_RECONNECT));
			Toast.makeText(this, getString(R.string.sub_add_server_enabled_now, n.name),
				Toast.LENGTH_LONG).show();
		} else {
			Toast.makeText(this, getString(R.string.sub_add_server_enabled, n.name),
				Toast.LENGTH_LONG).show();
		}
	}

	/* 还原一个节点原始的那段 clash 配置。先在它自己的订阅里找，再找其它订阅：
	   否则一个在记录 subId 之前（或来自旧缓存）解析出来的节点，会在这里莫名其妙地失败。
	   找不到时返回 ""。 */
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

	/* 在订阅正文里定位某个节点的 clash 配置块：先按**名字**（内核注册的就是它），
	   再按 server:port —— 这样即使机场改过名字也还能找到。 */
	private String findRawProxyIn(String raw, ClashNode n) {
		if (raw == null || raw.isEmpty())
		  return "";
		/* 两个正则只跟被查节点 n 有关，所以提到循环**外**编译一次。
		   原来是每检查一个候选节点就 Pattern.compile 两次 —— 订阅里几百个节点，
		   长按一次"加入服务器"就要编译上千次正则。 */
		java.util.regex.Pattern serverPat = null;
		java.util.regex.Pattern portPat = null;
		if (n.server != null && !n.server.isEmpty()) {
			serverPat = java.util.regex.Pattern.compile(
				"server\\s*:\\s*[\"']?" + java.util.regex.Pattern.quote(n.server));
			portPat = java.util.regex.Pattern.compile(
				"port\\s*:\\s*[\"']?" + n.port + "(?![0-9])");
		}
		String byAddr = "";
		for (ClashParser.ProxyDef p : ClashParser.extractProxies(raw)) {
			if (n.name != null && n.name.equals(p.name))
			  return p.text;
			/* 宽松的地址匹配：容忍引号、多行块风格和单行 flow 风格。 */
			if (byAddr.isEmpty() && serverPat != null && p.text != null && p.text.contains(":")
					&& serverPat.matcher(p.text).find()
					&& portPat.matcher(p.text).find())
			  byAddr = p.text;
		}
		return byAddr;
	}

	/* 真实延迟测试的候选目标。只要能通**任意一个**，节点就是活的；只有全部目标都失败
	   才判为不可用。特意分散在不同服务商（Google / Microsoft / Cloudflare）上，
	   这样某一个被墙或地区受限的地址就不会把一条完全正常的节点误判成不可用 ——
	   正是要避开"代理本来正常却显示不可用"那个坑。 */
	private static final String[] PROXY_TEST_URLS = {
		"http://www.gstatic.com/generate_204",
		"http://www.google.com/generate_204",
		"http://www.msftconnecttest.com/connecttest.txt",
		"http://cp.cloudflare.com",
	};

	/* 请 mihomo 把请求从**这个**节点推出去，并报回真实延迟。与 FlClash 一样，
	   "可用与否"的判定**只**来自 mihomo 的 /proxies/{name}/delay 接口 —— 既没有单独的
	   TCP 探测，也没有那种"选中该节点再发真实请求"的兜底（后者会翻转全局选择器，
	   把好节点误判成不可用）。节点能通返回延迟（>=0），不能通返回 -2，
	   测试根本跑不起来（隧道未连接 / 节点不在运行配置里）返回 null。
	   所有协议都适用，因为握手是 mihomo 做的。 */
	private Long proxyDelayMs(ClashNode n) {
		/* 两种情况下可用：隧道已连接（隧道内核），或本进程里已经加载了无 TUN 的测速内核
		   （CoreTestHost）。 */
		if (!prefs.getEnable() && !CoreTestHost.isReady()) {
			logOnce("no-core", "测速: 没有可用内核（VPN 未连接 / 测试内核未就绪）→ 未测速");
			return null;
		}
		if (!ensureProxyNameCache()) {
			logOnce("no-proxies", "测速: 取 /proxies 失败，无法把节点映射成内核里的名字 → 未测速");
			return null;
		}
		String name = realNodeName(n);
		if (name == null) {
			/* 被内核直接拒绝的节点（REALITY short-id 非法等）已被排除在它的配置之外，
			   所以任何探测都够不到它：说明一下就走，别在一轮里为它烧掉六次探测。 */
			if (CoreTestHost.isRejected(n.name)) {
				logOnce("rejected:" + n.name,
					"测速: 内核拒绝该节点（配置非法，已从内核配置排除）→ 未测速 name=" + n.name);
				return null;
			}
			/* /proxies 映射里没有这个节点 —— 但这**不**证明内核里没有它：mihomo 可能在
			   `server` 里报一个**解析后的 IP**（于是地址键对不上），而我们生成的名字就是
			   订阅里的原名。所以直接用那个名字试一次；反正配置是我们自己生成的。
			   猜错只损失一次失败的探测（动作会回 "proxy not exist"）并让节点留作未测速，
			   没有任何损失。 */
			if (n.name == null || n.name.isEmpty()) {
				passNotFound.incrementAndGet();
				logOnce("noname:" + n.server + ":" + n.port + ":" + n.type,
					"测速: 节点没有名字且在 /proxies 映射里找不到 → 未测速 [addr="
					+ n.server + ":" + n.port + " " + (n.type == null ? "?" : n.type)
					+ "]（内核报告可测节点 " + coreNodeCount + " 个）");
				return null;
			}
			name = n.name;
			logOnce("guess:" + n.name, "测速: /proxies 映射里没有该节点，改用节点名直试 name="
				+ n.name + " addr=" + n.server + ":" + n.port + "（内核报告可测节点 "
				+ coreNodeCount + " 个）");
		}
		/* 进程内探测**完全不需要** HTTP 主机，只有 REST 那条路才需要。以前在这里强制要求
		   一个主机，导致只要回环探测碰巧失败，整轮就变成"未测速"。 */
		String host = resolveApiHost();
		if (host == null && !CoreTestHost.isReady()) {
			logOnce("no-api", "测速: 控制接口不可达（127.0.0.1 探测失败）→ 未测速");
			return null;
		}
		/* 先用用户配置的目标，然后是内置兜底目标。 */
		List<String> targets = new ArrayList<String>();
		String userUrl = prefs.getAutoTestUrl();
		if (userUrl != null && !userUrl.isEmpty())
		  targets.add(userUrl);
		for (String u : PROXY_TEST_URLS)
		  if (!targets.contains(u))
			targets.add(u);
		/* 「延迟测试超时」= 单个节点这一次探测最多等回包多久。已移除「每节点测速上限」：
		   现在每个节点**只探一次**——用这个超时作为等待，超时/失败即判不可用，不再有救援、
		   也不再对同一节点反复测。这样「延迟测试超时」就是唯一的时间旋钮，语义不重复。 */
		int timeoutMs = prefs.getProxyTestTimeout() * 1000;
		/* 不再受「每节点上限」约束：给桥留足回包余量（探测自身超时 + 缓冲），
		   避免客户端在等内核那次探测时先超时。 */
		long maxWaitMs = timeoutMs + 6000L;

		/* 每个节点只测一次：取第一个验证目标（用户配置的「测速地址」优先，否则内置兜底），
		   探一次即可——通即活，超时/失败即判不可用，不再换目标继续试探。
		   要最大化抗误判，请在设置里把「测速地址」填成可靠的 URL（例如你自己可达的地址）。 */
		String target = targets.get(0);
		if (!TestProgress.running()) {
			logOnce("round-end:" + name, "测速: 本轮已结束 → " + name + " 按未测速处理");
			return null;                  /* 是这一轮被放弃了，而不是这个节点有问题 */
		}
		Long d = delayProbe(name, target, timeoutMs, maxWaitMs);
		if (d != null && d >= 0)
		  return d;                      /* 通 = 节点活 */
		TProxyService.log("测速: " + name + " @" + target + " -> "
			+ (d == null ? ("无响应（" + (timeoutMs / 1000) + "s 内未判定）")
				: "失败(" + d + ")"));
		/* 一次探测超时/失败即判定该节点不可用（「无响应判为不可用」开着时就是不可用），
		   不再继续试探其它目标 —— 这就是"每个节点只测一次"。 */
		return noVerdict(true);
	}

	/* 所有探测都没拿到延迟时的**最终判定**。
	   nodeLevel = 这个失败是**关于该节点**的（探测发出去了却始终没应答，或者它的额度
	   用尽）—— 这时由 设置 → 订阅 → 「无响应判为不可用」决定：开（默认）=> 不可用
	   (-2)，这正是用户理解的"超时"；关 => 未测速 (-1)，即保守模式。
	   nodeLevel = false 表示与节点无关的情况（压根没发过探测、这一轮已被放弃）：
	   一律「未测速」。 */
	private Long noVerdict(boolean nodeLevel) {
		if (nodeLevel && prefs.getProbeTimeoutAsFail()) {
			passTimeoutFail.incrementAndGet();
			return Long.valueOf(-2L);
		}
		return null;
	}



	/* 一次 /delay 探测。成功返回延迟（>=0）；内核明确报探测失败（504/408）返回 -2；
	   测试根本没能跑起来（传输不通 / 鉴权 / 路由 / 其它状态码）返回 null ——
	   后者**绝不能**被报成"不可用"。 */
	private Long delayProbe(String name, String target, int timeoutMs, long maxWaitMs) {
		/* 首选：通过动作桥用内核自己的隔离式探测。只要本进程里加载了内核（也就是那个
		   无 TUN 的测速内核）就可用，所以 VPN 关着、没有任何 HTTP 也能测延迟。
		   本进程没有内核时返回 null。
		   maxWaitMs = 这次探测愿意最多等多久回包（单次探测超时 + 缓冲），仅用于给桥留足
		   等待余量，不再受「每节点上限」约束。 */
		Long viaCore = CoreTestHost.testDelay(name, target, timeoutMs, maxWaitMs);
		if (viaCore != null)
		  return viaCore;
		/* 本进程里有那个无 TUN 的测速内核，它才是**权威**探测。下面的 REST 会打到
		   :native 里的**隧道内核**，而它的节点池可能是另一套（按国家筛过的）——
		   在那边找不到并不说明这个节点有问题，而且在 45 个节点的批量里，每个探测还多一次
		   往返、日志里刷满 502。所以这里直接报"无判定"，交给调用方判断。 */
		if (CoreTestHost.isReady()) {
			TProxyService.log("测速: " + name + " 测试内核无判定 @" + target
				+ "（已有本进程内核，不再回退隧道内核 REST）");
			return null;
		}
		/* 兜底：走 App 内嵌控制接口提供的 REST /delay
		   （只有 :native 的隧道内核可用时才会走到这里）。 */
		try {
			String path = "/proxies/" + encodePath(name)
				+ "/delay?timeout=" + timeoutMs + "&url=" + URLEncoder.encode(target, "UTF-8");
			/* 通过**绕过 VPN** 的 socket 访问回环控制接口；否则 App 自己的 socket 会被
			   隧道抓走。回包要等内核自己那次探测跑完才来，所以客户端的等待必须比探测超时
			   更长 —— 用默认的 6 秒会把一个 30 秒的设置悄悄卡掉；同时也不能超过这个节点
			   自己的剩余额度，等得更久毫无意义。 */
			int sockMs = (int) Math.max(1000L, Math.min((long) timeoutMs + 6000L, maxWaitMs));
			TProxyService.ApiResult r = TProxyService.bridgeApi("GET", TProxyService.apiBaseHost(),
				MihomoConfig.API_PORT, path, null, prefs.getSecret(), sockMs);
			if (r.code == 200) {
				JSONObject o = new JSONObject(r.body == null ? "{}" : r.body);
				if (!o.has("delay")) {
					/* 200 但没有 "delay" 字段是**畸形回包**：把它当成 0 ms 成功，
					   会让一个死节点排到列表最前面。 */
					TProxyService.log("测速: " + name + " REST /delay 200 但没有 delay 字段 -> "
						+ r.body + " @" + target + "（非节点判定 → 未测速）");
					return null;
				}
				long d = o.getLong("delay");
				if (d <= 0) {
					TProxyService.log("测速: " + name + " REST /delay 返回 delay=" + d
						+ " @" + target + "（非节点判定 → 未测速）");
					return null;
				}
				return d;
			}
			if (r.code == 504 || r.code == 408) {
				TProxyService.log("测速: " + name + " REST /delay 内核判定失败 HTTP "
					+ r.code + " @" + target + " timeout=" + timeoutMs + "ms");
				return -2L;             /* genuine: node could not complete it */
			}
			if (r.code == -1) {
				TProxyService.log("测速: " + name + " REST /delay 控制接口不可达 @"
					+ target + " → 未测速");
				return null;            /* controller unreachable -> cannot test */
			}
			TProxyService.log("测速: " + name + " REST /delay HTTP " + r.code
				+ (r.error == null ? "" : (" " + r.error)) + " @" + target
				+ "（非节点判定 → 未测速）");
			return null;                /* auth/route/other: not a node verdict */
		} catch (Exception e) {
			return null;
		}
	}

	/* 按需拉取运行配置里的节点列表，并建立 server|port|type -> 真实（去重后）名字 的索引，
	   这样就能**按地址**定位节点，而不依赖它那个可能已被改过的名字。
	   控制接口不可达时返回 false。 */
	private boolean ensureProxyNameCache() {
		if (proxyNameCache != null)
		  return true;
		if (nameCacheFailed)
		  return false;      /* 本轮已经试过并失败了 */
		/* 每轮只拉一次：所有 worker 会同时到这里，而每次拉取都要走那个单回调的桥，
		   并发多份只会拖慢这一轮、并且在日志里刷很多遍。 */
		synchronized (nameCacheLock) {
			if (proxyNameCache != null)
			  return true;
			if (nameCacheFailed)
			  return false;
			boolean ok = fetchProxyNameCache();
			/* 拉取失败后不能每个节点都重试：少了这一步，每个节点都要为 /proxies 再付一次
			   经过桥的往返。 */
			if (!ok)
			  nameCacheFailed = true;
			return ok;
		}
	}

	private boolean fetchProxyNameCache() {
		/* 隧道内核**或**本进程里已加载的无 TUN 测速内核（CoreTestHost）都能用。 */
		if (!prefs.getEnable() && !CoreTestHost.isReady())
		  return false;
		String host = resolveApiHost();
		if (host == null)
		  return false;
		try {
			TProxyService.ApiResult r = TProxyService.bridgeApi("GET", TProxyService.apiBaseHost(),
				MihomoConfig.API_PORT, "/proxies", null, prefs.getSecret());
			if (r.code != 200 || r.body == null) {
				logOnce("proxies-code", "测速: /proxies HTTP " + r.code
					+ (r.error == null ? "" : (" " + r.error)) + " → 无法建立节点名映射");
				return false;
			}
			String body = r.body;
			JSONObject o = new JSONObject(body);
			JSONObject proxies = o.optJSONObject("proxies");
			if (proxies == null) {
				logOnce("proxies-shape", "测速: /proxies 响应里没有 proxies 对象 → 无法建立映射，前 200 字："
					+ (body.length() > 200 ? body.substring(0, 200) : body));
				return false;
			}
			StringBuilder topKeys = new StringBuilder();
			java.util.Iterator<String> kk = o.keys();
			while (kk.hasNext()) {
				if (topKeys.length() > 0)
				  topKeys.append(',');
				topKeys.append(kk.next());
			}
			java.util.Map<String, String> map = new java.util.HashMap<String, String>();
			java.util.Iterator<String> it = proxies.keys();
			int total = 0;
			int withAddr = 0;
			StringBuilder sample = new StringBuilder();
			/* 原始样本：不必再往返一次，就能回答"内核的配置里到底有没有这些节点"、
			   以及它们长什么形状。 */
			String firstObj = null;
			String firstAddressed = null;
			while (it.hasNext()) {
				String pname = it.next();
				Object pv = proxies.opt(pname);
				if (!(pv instanceof JSONObject)) {
					if (firstObj == null)
					  firstObj = pname + " -> " + pv;
					continue;
				}
				JSONObject p = (JSONObject) pv;
				total++;
				if (firstObj == null)
				  firstObj = pname + " -> " + p;
				String type = p.optString("type", "");
				String server = p.optString("server", "");
				int port = p.optInt("port", 0);
				/* 较新的 mihomo 已经从 /proxies 里**去掉了** server/port —— 一条记录
				   现在只有 {"type","name","alive",...} —— 所以不能再靠地址来区分"节点"
				   和"组"。改为按**类型**判断，并给每个真实节点按**名字**建索引
				   （这个名字正是我们写进生成配置里的那个）。只有内置项
				   （DIRECT / REJECT / COMPATIBLE）和配置自己的组会被跳过。 */
				if (isBuiltinOrGroup(pname, type))
				  continue;
				withAddr++;
				if (firstAddressed == null)
				  firstAddressed = pname + " -> " + p;
				if (!map.containsKey("name:" + pname))
				  map.put("name:" + pname, pname);
				if (!server.isEmpty() && port != 0) {
					String base = server.toLowerCase() + "|" + port;
					/* 地址键仍然保留：一是给那些还会报地址的内核用，二是作为兜底 ——
					   当节点来自别处（手动服务器列表）而名字对不上时还能用。 */
					if (!map.containsKey(base))
					  map.put(base, pname);
					map.put(base + "|" + type.toLowerCase(), pname);
				}
				if (sample.length() < 300) {
					if (sample.length() > 0)
					  sample.append(", ");
					sample.append(pname).append('/').append(type)
						.append(server.isEmpty() ? "" : ("=" + server + ":" + port));
				}
			}
			proxyNameCache = map;
			coreNodeCount = withAddr;
			/* 即使成功也要记：在界面上"配置里没有节点"和"这个节点不在配置里"看起来完全
			   一样，而这一行能让人一眼分辨。 */
			TProxyService.log("测速: 内核 /proxies 共 " + total + " 项，可测节点 "
				+ withAddr + " 个（映射 " + map.size() + " 键）· 国家筛选="
				+ (prefs.getSubCountryFilter().isEmpty() ? "全部" : prefs.getSubCountryFilter())
				+ (sample.length() == 0 ? "" : (" · 示例 " + sample)));
			/* 值得看的两样东西：内核**真实**上报的一条节点对象，以及它真实使用的顶层键。
			   两者合起来就能说明：配置到底有没有带上节点、以及字段命名是否和我们查找的一致。 */
			if (withAddr == 0)
			  TProxyService.log("测速: 内核 /proxies 里没有任何可测节点 → 配置里可能真的没有代理。"
				+ "响应顶层键=" + topKeys + " · 首项 " + shortText(firstObj));
			else
			  TProxyService.log("测速: 内核首个可测节点 " + shortText(firstAddressed));
			return true;
		} catch (Exception e) {
			logOnce("proxies-ex", "测速: 解析 /proxies 失败 " + e);
			return false;
		}
	}

	private String realNodeName(ClashNode n) {
		if (proxyNameCache == null)
		  return null;
		/* 1) 先按节点**自己的名字**匹配：运行中的内核是我们配置的，所以它的节点名就是
		   订阅里的名字（加上我们自己的去重后缀），这个匹配不会被"内核上报了解析后的 IP"
		   或"协议名拼写不同"之类的情况破坏。 */
		if (n.name != null && !n.name.isEmpty()) {
			String byName = proxyNameCache.get("name:" + n.name);
			if (byName != null)
			  return byName;
		}
		String server = n.server == null ? "" : n.server.toLowerCase();
		String base = server + "|" + n.port;
		String name = proxyNameCache.get(base);
		if (name != null)
		  return name;
		String type = n.type == null ? "" : n.type.toLowerCase();
		return proxyNameCache.get(base + "|" + type);
	}

	/* clash-api **永远**绑在回环（external-controller: 127.0.0.1），而在 allow-lan
	   关闭时 mihomo 只服务回环客户端 —— 所以设备自己的 IP 根本到不了它。以前会退回
	   用设备 IP，结果 latch 到一个没有监听的地址上，让所有 /proxies 和 /delay 调用
	   全部失败（也就是"测速全都不通"）。所以**只**用 127.0.0.1。 */
	private String resolveApiHost() {
		if (apiHost != null)
		  return apiHost;
		if (apiHostChecked)
		  return null;      /* 本轮已经探过，它没有应答 */
		apiHostChecked = true;
		if (probeApi("127.0.0.1")) {
			apiHost = "127.0.0.1";
			return apiHost;
		}
		return null;
	}

	private boolean probeApi(String host) {
	/* 可达性**优先**由进程内桥判定，这样即使 clash-api 的 HTTP 监听（9090）从没绑上，
	   选择器和测速逻辑也能继续。只有桥不可用时（例如内核还没加载）才退回 HTTP 的
	   /version 探测。 */
	if (TProxyService.isCoreReachable())
	  return true;
	TProxyService.ApiResult r = TProxyService.bridgeApi("GET", host,
		MihomoConfig.API_PORT, "/version", null, prefs.getSecret());
	/* 2xx = 健康。401 = 监听起来了但鉴权不对：仍算"可达"，所以就把这个主机定下来，
	   让真正的那次调用去暴露 401，而不是退回到一个死地址上。 */
	return (r.code >= 200 && r.code < 300) || r.code == 401;
	}

	/* 设备上第一个"非回环、非 TUN"的 IPv4，留作兜底 —— 以防 127.0.0.1 回环哪天被 TUN
	   捕获。VPN 隧道网卡（tun*）会被跳过，免得选中那条被捕获的隧道。
	   都没有时退回 127.0.0.1。 */
	private String deviceHost() {
		try {
			java.util.Enumeration<java.net.NetworkInterface> en =
				java.net.NetworkInterface.getNetworkInterfaces();
			while (en.hasMoreElements()) {
				java.net.NetworkInterface nif = en.nextElement();
				if (nif.isLoopback() || !nif.isUp())
				  continue;
				String n = nif.getName();
				if (n != null && (n.startsWith("tun")
						|| n.startsWith("ppp") || n.contains("tun")))
				  continue;
				java.util.Enumeration<java.net.InetAddress> adds = nif.getInetAddresses();
				while (adds.hasMoreElements()) {
					java.net.InetAddress a = adds.nextElement();
					if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress())
					  return a.getHostAddress();
				}
			}
		} catch (Throwable ignore) { }
		return "127.0.0.1";
	}

	/* clash-api URL 的路径安全编码：先编码，再把 "+" 换回 "%20" ——
	   否则节点名里的空格会被当成字面上的加号。 */
	private static String encodePath(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return s;
		}
	}

	private static String readApiBody(HttpURLConnection c) {
		try {
		InputStream in = (c.getResponseCode() >= 400) ? c.getErrorStream() : c.getInputStream();
			if (in == null)
			  return "";
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int n;
			while ((n = in.read(buf)) > 0)
			  bos.write(buf, 0, n);
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private void useNode(final ClashNode n) {
		/* 自动选择开着时点某个节点：url-test 组还是会自己挑最快的，所以这次点击只是
		   一个提示。以前会在这里把自动选择**关掉**，等于悄悄丢掉了用户的选择
		   （"我明明开了，回来一看又关了"）。现在自动选择保持开启并持久化，
		   点击只给一条提示作为回应。 */
		boolean auto = prefs.getAutoSelect();
		prefs.setSubSelected(n.name);
		/* 选择订阅节点等于把上游交回给订阅，所以要停用已启用的服务器。 */
		prefs.setActiveSocksId("");
		String msg;
		if (prefs.getEnable()) {
			/* 由服务把它应用到运行中的内核；如果该节点不在内核当前跑的配置里，
			   服务会自己重建配置（见 applySelector/rebuildForSelection），
			   所以不需要"手动重连"。 */
			startService(new Intent(this, TProxyService.class)
				.setAction(TProxyService.ACTION_SELECT));
			msg = auto
				? getString(R.string.sub_auto_pick, n.name)
				: getString(R.string.sub_switched_now, n.name);
		} else {
			msg = auto
				? getString(R.string.sub_auto_pick, n.name)
				: getString(R.string.sub_applied_hint, n.name);
		}
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
		adapter.notifyDataSetChanged();
	}

	/* 自动选择：由 mihomo 的 url-test 组挑最快节点，并每 `interval` 秒重测一次。
	   关闭表示"严格使用列表里点的那个节点"。 */
	private void setupAutoSelect() {
		switch_auto.setChecked(prefs.getAutoSelect());
		switch_auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton button, boolean checked) {
				prefs.setAutoSelect(checked);
				updateAutoUi();

				/* 组的类型是**隧道启动时**烘焙进配置的，所以正在跑的隧道在重连之前
				   仍保持旧的行为。 */
				if (prefs.getEnable())
				  Toast.makeText(SubscribeActivity.this,
					R.string.sub_auto_restart, Toast.LENGTH_LONG).show();
			}
		});
		updateAutoUi();
	}

	private void updateAutoUi() {
		boolean auto = prefs.getAutoSelect();
		/* setChecked() 只在值**真的**变化时才触发监听器，所以这里不会递归。 */
		switch_auto.setChecked(auto);
		textview_auto_hint.setText(auto
			? getString(R.string.sub_auto_on_hint)
			: getString(R.string.sub_manual_hint));
	}



	private void showHelp() {
		new AlertDialog.Builder(this)
			.setTitle(R.string.sub_help_title)
			.setMessage(R.string.sub_help_text)
			.setPositiveButton(android.R.string.ok, null)
			.show();
	}

	/* 单节点重测：与"测速全部"共用同一套**单飞**规则（同一份进程级状态），
	   否则批量测速期间来一次重测，会把整轮弄坏。抽成方法是为了让行内的按钮
	   只挂一个复用的监听器（见 NodeAdapter）。 */
	private void startSingleTest(final ClashNode n) {
		if (TestProgress.stale())
		  TestProgress.finish();
		int one = TestProgress.begin(1,
			System.currentTimeMillis() + 120000, false);
		if (one < 0) {
			Toast.makeText(this, R.string.sub_test_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		corePrepared = false;
		/* 重置轮次日志键：单节点重测必须能打自己的原因，
		   哪怕同样的原因在之前那次"测速全部"里已经打过。 */
		passLogged.clear();
		/* 单节点测试保留用户设的超时和详细日志；只是轮次的记账方式不同。 */
		CoreTestHost.setVerbose(true);
		/* 同样显示进度行/进度条（0/1 -> 1/1），
		   让单节点重测也能看得见"正在跑"。 */
		updateTestProgress();
		testNode(n, false, one);
	}

	/* 行内控件的缓存（ViewHolder）。测速期间每 400ms 就会通知一次数据变化，
	   原来每绑一行都要做 9 次 findViewById 树遍历、并新建 2 个点击监听器。 */
	private static class Row {
		MaterialCardView card;
		TextView flag, name, detail, proto, source, status, badge;
		Button use, test;
		/* 这一行当前绑定的节点。行的长按要用它 —— **不能**把节点挂在 card 的 tag 上：
		   card 就是 convertView，它的 tag 已经被 ViewHolder（本类）占用了。 */
		ClashNode node;
	}

	private class NodeAdapter extends ArrayAdapter<ClashNode> {
		private final android.view.LayoutInflater inflater;
		private final int colorOk;
		private final int colorBad;
		private final int colorIdle;
		private final int colorSelected;
		/* getView 每行都要判断"是不是当前选中的节点"，而 prefs.getSubSelected()
		   每次都要拼键再读偏好；数据刷新时读一次即可（选中的变化总会伴随一次刷新）。 */
		private String selectedName = "";

		@Override
		public void notifyDataSetChanged() {
			selectedName = prefs.getSubSelected();
			super.notifyDataSetChanged();
		}

		NodeAdapter() {
			super(SubscribeActivity.this, R.layout.subscribelistitem, shown);
			inflater = getLayoutInflater();
			colorOk = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorPrimary, 0);
			colorBad = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorError, 0);
			colorIdle = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorOutline, 0);
			colorSelected = MaterialColors.getColor(SubscribeActivity.this,
				com.google.android.material.R.attr.colorPrimaryContainer, 0);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			Row r;
			if (convertView == null) {
				convertView = inflater.inflate(R.layout.subscribelistitem, parent, false);
				r = new Row();
				r.card = (MaterialCardView) convertView;
				r.flag = (TextView) convertView.findViewById(R.id.item_flag);
				r.name = (TextView) convertView.findViewById(R.id.item_name);
				r.detail = (TextView) convertView.findViewById(R.id.item_detail);
				r.proto = (TextView) convertView.findViewById(R.id.item_proto);
				r.source = (TextView) convertView.findViewById(R.id.item_sub);
				r.status = (TextView) convertView.findViewById(R.id.item_status);
				r.badge = (TextView) convertView.findViewById(R.id.item_badge);
				r.use = (Button) convertView.findViewById(R.id.item_use);
				r.test = (Button) convertView.findViewById(R.id.item_test);
				/* 点击监听器只创建一次：当前行的节点挂在按钮的 tag 上，点击时再取。 */
				r.use.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						ClashNode node = (ClashNode) v.getTag();
						if (node != null)
						  useNode(node);
					}
				});
				r.test.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						ClashNode node = (ClashNode) v.getTag();
						if (node != null)
						  startSingleTest(node);
					}
				});
				/* 行本身也挂一份长按（菜单与 ListView 的那份完全相同，见 onCreate）：
				   两个按钮会吃掉落在它们身上的触摸，落在卡片空白处才轮到 ListView；而
				   一旦卡片被主题样式设成 clickable，整行的触摸都会由卡片消费，ListView
				   的 OnItemLongClickListener 就永远不触发 —— 症状正是"长按没反应"。
				   两条路都通向同一个菜单，多挂一份没有副作用。 */
				r.card.setOnLongClickListener(new View.OnLongClickListener() {
					@Override
					public boolean onLongClick(View v) {
						/* 节点从 ViewHolder 里取（见 Row.node），不是从 tag —— card 的
						   tag 存的就是这个 Row 本身。 */
						Object t = v.getTag();
						if (t instanceof Row) {
							ClashNode node = ((Row) t).node;
							if (node != null)
							  showNodeMenu(node);
						}
						return true;
					}
				});
				convertView.setTag(r);
			} else {
				r = (Row) convertView.getTag();
			}
			final ClashNode n = getItem(position);
			r.use.setTag(n);
			r.test.setTag(n);
			r.node = n;

			String cc = countryCode(n);
			r.flag.setText(Country.flag(cc));
			r.name.setText(n.name);
			/* "国家 · host:port"：已知国家时把国家放前面，而被省略的是**地址**
			   （从中间省略，这样端口能保留下来）。 */
			String detailText = n.server + ":" + n.port;
			if (!GeoIp.UNKNOWN.equals(cc))
			  detailText = Country.name(cc) + "  ·  " + detailText;
			r.detail.setText(detailText);
			/* 协议单独一个标签：以前和地址挤在同一行，主机名一长就会把协议省略掉。 */
			if (n.type == null || n.type.isEmpty()) {
				r.proto.setVisibility(View.GONE);
			} else {
				r.proto.setText(n.type);
				r.proto.setBackgroundColor(protoColor(n.type));
				r.proto.setTextColor(Color.WHITE);
				r.proto.setVisibility(View.VISIBLE);
			}
			/* 订阅来源标签：说明这个节点来自哪个池。 */
			String src = n.subId == null ? null : subNames.get(n.subId);
			if (src != null && !src.isEmpty()) {
				r.source.setText(src);
				r.source.setVisibility(View.VISIBLE);
			} else {
				r.source.setVisibility(View.GONE);
			}

			if (n.latency >= 0) {
				r.status.setText(n.latency + " ms");
				r.status.setTextColor(colorOk);
			} else if (n.latency == -2) {
				r.status.setText(getString(R.string.sub_status_fail));
				r.status.setTextColor(colorBad);
			} else {
				r.status.setText(getString(R.string.sub_status_untested));
				r.status.setTextColor(colorIdle);
			}

			boolean selected = n.name != null && n.name.equals(selectedName);
			r.card.setCardBackgroundColor(selected ? colorSelected : Color.TRANSPARENT);
			r.badge.setVisibility(selected ? View.VISIBLE : View.GONE);
			return convertView;
		}
	}
}
