/*
 ============================================================================
 文件名  : TProxyService.java
 作者    : hev <r@hev.cc>
 版权    : Copyright (c) 2024 xyz
 说明    : 隧道服务（VpnService）：建 TUN、拉起 mihomo 内核、生成配置、统计流量、
           维护常驻通知。运行在独立的 :native 进程里。
 ============================================================================
*/

package com.tunvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;

import androidx.core.app.NotificationCompat;

import android.system.Os;
import android.system.OsConstants;
import java.lang.reflect.Method;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.widget.Toast;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.TunInterface;
import io.github.oviron.libmihomo.InvokeInterface;

public class TProxyService extends VpnService {
	public static final String ACTION_CONNECT = "tunvpn.CONNECT";
	public static final String ACTION_DISCONNECT = "tunvpn.DISCONNECT";
	public static final String ACTION_RECONNECT = "tunvpn.RECONNECT";
	/* 隧道保持运行的情况下切换所选节点。 */
	public static final String ACTION_SELECT = "tunvpn.SELECT";

	/* 流量统计 */
	private static final int NOTIFY_ID = 1;
	private static final String NOTIFY_CHANNEL = "socks5";
	private static final long STATS_INTERVAL = 1000;

	private Handler statsHandler = null;
	private Runnable statsTask = null;
	/* 采样循环必须离开主线程：accumulateProxy() 会同步访问内核控制接口，而主线程
	   上任何网络调用都会抛 NetworkOnMainThreadException。该异常以前被吞掉，body 为
	   null，"代理专属流量"永远是 0 —— 这正是当初"统计一直不动"的原因。 */
	private HandlerThread statsThread = null;
	/* 由 statsThread 写、由通知 / 测速 / 选择器线程读：加 volatile，避免读到写了一半的值。 */
	private volatile Preferences statsPrefs = null;
	/* App 自建的回环 HTTP 控制接口（libmihomo 自己从不绑定 external-controller）。
	   生命周期与隧道一致。 */
	private ClashApiServer clashApiServer = null;
	private Preferences prefs = null;
	private long lastTx, lastRx, lastTime;
	/* 下面两组由 statsThread 写、由**其它线程**读（测试/选择器线程调用的
	   buildNotification、以及给 Activity 用的 getter）：加 volatile，避免 long 撕裂或读到旧值。 */
	private volatile long sessionTx, sessionRx;
	private long sessionBaseTx, sessionBaseRx; /* 会话开始时的内核累计值 */
	private long baseTx, baseRx;
	private volatile long totalTx, totalRx;
	private volatile long txRate, rxRate;
	private boolean trafficPrimed = false; /* 首个有效样本用来定基准，避免 9G/s 的假尖峰 */
	private String lastNotifyText = null;
	/* 通知栏那两个跳转目标的内容是固定的，而通知每秒都会重建；
	   这里只创建一次，省掉每秒两次跨 Binder 的 PendingIntent 创建（见 buildNotification）。 */
	private volatile PendingIntent notifyContentPi = null;
	private volatile PendingIntent notifyStopPi = null;
	private int trafficSamples = 0;

	/* --- 代理专属流量统计 ---------------------------------------------------
	   内核自己的计数器包含它处理的一切（含直连）。要报"真正经节点走了多少"，就得
	   轮询连接列表，把链路上不是 DIRECT 的连接增量累加起来。两次轮询之间建立又结束的
	   连接会被漏掉，所以这是近似值，不是精确值。 */
	private final Map<String, long[]> connSeen = new HashMap<String, long[]>();
	/* "最近请求"记录器：一条连接从 /connections 里消失时就记到这里，让 UI 事后也能
	   看到"什么流量去了哪里"。connInfo 跟踪每条活动连接的元信息与最新计数；
	   recentRequests 是**有上限**的历史，序列化后写进 Preferences。 */
	private final Map<String, ConnInfo> connInfo = new HashMap<String, ConnInfo>();
	private final List<RecentRequest> recentRequests = new ArrayList<RecentRequest>();
	private long lastRecentFlush = 0;
	private static final int MAX_RECENT_REQUESTS = 200;

	/* 正在为"历史记录"跟踪的一条活动连接。 */
	private static class ConnInfo {
		long startMs;
		String target;
		String rule;
		String chain;
		String process;
		long up;
		long down;
	}
	/* 一条已关闭、可以直接展示的连接。 */
	private static class RecentRequest {
		long startMs;
		long endMs;
		String target;
		String rule;
		String chain;
		String process;
		long up;
		long down;
	}
	/* /connections 连续轮询失败的次数：这样"统计一直卡在 0"这种情况会被报出来，
	   而不是无声无息。 */
	private int connFailStreak = 0;
	/* **解析**快照（而非抓取快照）的连续失败次数：同样只报一次 —— 两种情况的结果
	   都是计数器为 0。 */
	private int proxyParseFails = 0;
	/* 探测控制接口时最后见到的异常：这样探测失败能区分"根本没人监听（拒绝连接）"
	   还是"在跑但不回应"。 */
	private volatile String lastControllerError = null;
	/* clash-api **实际**应答的主机地址，由 isControllerUp() 解析出来。 */
	private volatile String controllerHost = "127.0.0.1";
	private volatile String proxyTestStatus = "";
	/* 选择器 PUT 失败后自动重建配置的去抖时间（见 rebuildForSelection），
	   以及周期性刷新"当前真正在用哪个节点"的计数（见 sampleStats）。 */
	private volatile long lastSelectRebuildMs = 0;
	private int nodeSyncTick = 0;
	/* 正在应用**用户刚点选**的节点（ACTION_SELECT）时为 true；隧道启动过程中那次
	   自动应用为 false。只有前者在"节点不在运行配置里"时才值得重建配置
	   （见 rebuildForSelection）。 */
	private volatile boolean selectUserAction = false;
	private static final String PROXY_TEST_URL = "http://www.gstatic.com/generate_204";
	/* 延迟测试候选地址：节点只要能通任意一个，就给出干净的“可用”。Clash 自己的
	   /delay 语义是“代理回了任何 HTTP 响应即节点管线通”，502 只说明该目标被节点
	   出口拦截，不算代理死。 */
	private static final String[] PROXY_TEST_URLS = {
		PROXY_TEST_URL,
		"http://www.google.com/generate_204",
		"http://www.msftconnecttest.com/connecttest.txt",
	};
	private volatile String lastTestUrl = "";
	private volatile String lastTestError = "";
	private long proxyBaseTx, proxyBaseRx;
	private long proxySessionTx, proxySessionRx;
	private long lastProxyTx, lastProxyRx;
	private long proxyRateTx, proxyRateRx;
	private long lastProxyTime;
	private boolean proxyPrimed = false; /* 第一个有效的代理样本用来定基准 */

	/* 直接往日志文件追加一行（绕过 fd 1/2 的重定向），这样即使原生库加载失败或重定向
	   没成功，启动阶段的诊断信息也一定能被记下来。真正的文件处理在 TestLog 里，
	   那些拿不到 Service 的类也通过它写日志。 */
	private void appendLog(String s) {
		TestLog.append(s);
	}

	/* 从静态上下文打日志（例如没有 Service 引用的 ClashApiServer）到同一个文件。
	   还没有任何东西绑定到服务时也是安全的：日志行仍然会同步到 logcat。 */
	static void log(String s) {
		TestLog.append(s);
	}

	/* 把进程的 stdout/stderr（fd 1/2）重定向到文件，好让原生隧道里的 printf/fprintf
	   日志在 Android 上也能被捕获（否则它们会被直接丢掉）。

	   android.system.Os 的方法签名在不同 SDK / 设备构建上不一样（open() 可能返回
	   int 也可能返回 FileDescriptor；dup2()/close() 可能收 int 也可能收
	   FileDescriptor），所以这里用反射取到**实际存在**的那个方法，并在运行时适配参数
	   类型。这样无论设备上是哪种变体，代码都能编译并运行。 */
	private void redirectStdioToLog(File log) {
		try {
			int flags = OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_APPEND;
			Method open = Os.class.getMethod("open", String.class, int.class, int.class);
			Object fdObj = open.invoke(null, log.getAbsolutePath(), flags, 0644);

			Method dup2 = null;
			for (Method m : Os.class.getMethods()) {
				if (m.getName().equals("dup2") && m.getParameterTypes().length == 2) {
					dup2 = m;
					break;
				}
			}
			Class<?> p0 = dup2.getParameterTypes()[0];
			if (p0 == int.class) {
				int logFd = (Integer) fdObj;
				dup2.invoke(null, logFd, 1);
				dup2.invoke(null, logFd, 2);
			} else {
				FileDescriptor logFd = (FileDescriptor) fdObj;
				dup2.invoke(null, logFd, FileDescriptor.out);
				dup2.invoke(null, logFd, FileDescriptor.err);
			}

			Method close = null;
			for (Method m : Os.class.getMethods()) {
				if (m.getName().equals("close") && m.getParameterTypes().length == 1) {
					close = m;
					break;
				}
			}
			close.invoke(null, fdObj);
		} catch (Exception e) {
		}
	}

	/* 交给内核的配置参数，保留下来是为了在内核拒绝某个节点时能把它剔掉再重新应用
	   （见 retryWithoutRejectedProxy）。 */
	private volatile String coreInitParams = null;
	private volatile String coreSetupParams = null;
	private volatile File coreConfigFile = null;
	private volatile boolean coreCustomConfig = false;
	/* 内核拒绝过的节点名。之后每次重建都会排除它们，并在进程生命周期内一直记住。 */
	private final java.util.Set<String> rejectedNodes =
		java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
	private volatile int configRetry = 0;
	/* 正在重建配置进行重试时为 true，避免同步的"配置坏了"检查与它抢跑。 */
	private volatile boolean configRetryRunning = false;
	private static final int MAX_CONFIG_RETRIES = 8;

	private ParcelFileDescriptor tunFd = null;
	/* 内核在加载生成的配置时报了问题就非空。这种情况下控制接口**永远不会**绑定成功，
	   所以把这个错误留着记日志（并暴露给界面），而不是让失败悄无声息。 */
	private volatile String quickSetupError = null;
	/* 启动失败已被处理过就置位：这样异步的 quickSetup 回调与同步检查不会**都**去触发
	   一次中止。 */
	private volatile boolean startupAborted = false;
	/* 隧道正在拆除时置位。那些否则会跑上**好几分钟**的辅助线程（选择器重试循环、
	   控制接口校验、连通性探测、延迟启动 clash-api）都会检查它，所以断开连接能把它们
	   停掉，而不是让它们在 VPN 早已消失之后还在干活、还占着这个 Service。 */
	private volatile boolean tunnelStopping = false;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		sInstance = this;
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			stopService();
			return START_NOT_STICKY;
		}
		/* 只在 establish() 时读取的设置（按应用分流范围、全局模式）被实时修改时：
		   用一个动作"停掉旧 fd + 重建"，而不销毁服务 —— 避免 DISCONNECT-then-CONNECT
		   的竞态（新隧道被还在排队的 stopSelf() 拆掉）。 */
		if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
			rebuildTunnel();
			return START_STICKY;
		}
		/* mihomo 支持运行中切换选择器，所以新选的节点**立即**生效，不必等重启。 */
		if (intent != null && ACTION_SELECT.equals(intent.getAction())) {
			if (tunFd != null) {
				/* 标记为"用户自己的选择"：只有这种情况，节点不在运行配置里时才该
				   用重建配置来兜底（见 rebuildForSelection）。 */
				selectUserAction = true;
				applySelectedNode(new Preferences(this));
			}
			return START_STICKY;
		}
		startService();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		tunnelStopping = true;
		/* 系统可以在没有断开指令的情况下把服务拆掉，所以必须确保统计轮询不会比服务活得更久。
		   stopStats() 是幂等的，因此正常停止之后再调一次也无害。 */
		stopStats();
		stopEmbeddedApi();
		sInstance = null;
		super.onDestroy();
	}

	@Override
	public void onRevoke() {
		sInstance = null;
		stopService();
		super.onRevoke();
	}

	public void startService() {
		if (tunFd != null)
		  return;
		/* 全新隧道：让辅助线程在上一次停止之后能重新跑起来。 */
		tunnelStopping = false;

		prefs = new Preferences(this);

		/* 日志：先绑定共享日志文件，再为本会话起一份新文件；之后所有追加
		   （包括来自 ClashApiServer 和各个 Activity 的）都写进「日志」页读的那个文件。 */
		TestLog.init(this);
		File tproxy_log = new File(getCacheDir(), "tproxy.log");
		if (tproxy_log.exists())
		  tproxy_log.delete();
		appendLog("=== tunVPN start (pid " + android.os.Process.myPid() +
			" logging=" + prefs.getLogEnabled() + ") ===");
		if (prefs.getLogEnabled()) {
			redirectStdioToLog(tproxy_log);
		}

		/* 加载内嵌的 mihomo 内核（libclash.so + libmihomo-jni.so）。 */
		try {
			Clash.INSTANCE.load(getApplicationInfo().nativeLibraryDir);
			appendLog("mihomo core loaded OK (bridge ABI " + Clash.INSTANCE.bridgeABI() + ")");
		} catch (Throwable e) {
			failStartup("内核加载失败：" + e);
			return;
		}

		/* 写配置**之前**先给 clash-api 挑一个空闲的回环端口：端口被别的进程占着
		   （残留隧道、别的代理、adb forward、模拟器）会让 mihomo 绑定控制接口时
		   **静默失败**，症状就是"已连接，但 /connections 连接被拒、所有计数恒为 0"。 */
		int apiPort = MihomoConfig.pickApiPort();
		appendLog("config: clash-api port = " + apiPort);

		/* 配置：要么是用户手工编辑的那份（自定义模式），要么是按当前设置新生成的。 */
		File configFile = new File(getFilesDir(), "config.yaml");
		try {
			if (prefs.getCustomConfig() && configFile.exists()) {
				appendLog("config: 使用自定义 config.yaml（已关闭自动生成）");
				ensureControlApi(configFile, prefs);
			} else {
				/* 内核已经拒绝过的节点**不能**在重连时带回来，
				   否则每次都会因为同样的原因把配置搞失败。 */
				configFile = MihomoConfig.build(this, prefs, rejectedNodes);
				appendLog("config: " + configFile.getAbsolutePath());
				appendLog("routing: " + MihomoConfig.describe(prefs)
					+ (rejectedNodes.isEmpty() ? ""
						: ("（已排除内核拒绝的节点 " + rejectedNodes.size() + " 个）")));
			}
			/* 只记一行而不是整个文件：这几个键决定节点选择器和流量统计到底能不能够到
			   内核。 */
			appendLog("config: " + configKeySummary(configFile));
		} catch (Throwable e) {
			failStartup("生成配置失败：" + e.getMessage());
			return;
		}

		/* VPN 网卡。路由全部由 mihomo 接管，所以我们把每个包都送进隧道，
		   由它的规则引擎决定走代理还是直连。 */
		boolean ipv4 = prefs.getIpv4();
		boolean ipv6 = prefs.getIpv6();
		/* 网卡地址要放在 fake-ip 池（198.18.0.0/16）之外，
		   否则某个域名可能被分配到一个**就是隧道自己**的地址。 */
		String tunAddr = "172.19.0.1";
		String tunAddr6 = "fc00::1";
		int tunPrefix = 30;

		VpnService.Builder builder = new VpnService.Builder();
		builder.setBlocking(false);
		builder.setMtu(prefs.getTunnelMtu());
		if (ipv4) {
			builder.addAddress(tunAddr, tunPrefix);
			builder.addDnsServer("223.5.5.5");
			builder.addRoute("0.0.0.0", 0);
		}
		if (ipv6) {
			builder.addAddress(tunAddr6, 64);
			builder.addDnsServer("2400:3200::1");
			builder.addRoute("::", 0);
		}

		/* 控制端 App 自己的流量**绝不能**被送进它自己建的隧道。它要访问 mihomo 的回环
		   监听：127.0.0.1:9090 上的 external-controller API（选节点、首页的连接计数）
		   以及首页"是否走代理"探测用的 mixed-port —— 这些 socket 一旦被 VPN 抓走，就
		   永远到不了本地监听。症状正是当初反馈的那样：被选中的应用代理正常（那是隧道的
		   本职工作），但界面没有任何流量统计、还报"代理不通"。所以无论全局还是部分应用
		   模式，都要把自己排除在外。 */
		/* VpnService 禁止在同一个 builder 上混用 addAllowedApplication 与
		   addDisallowedApplication，所以两种范围用不同的调用：
		   - 全局：只把我们自己排除掉，其它应用全部走隧道；
		   - 部分应用：只允许选中的应用。我们本来就**不在**这个列表里，所以自己的流量
		     （external-controller、mixed-port）天然绕过隧道 —— 不需要显式排除，
		     而且一旦加了会抛 IllegalArgumentException，把整个应用范围设置废掉。 */
		if (prefs.getGlobal()) {
			/* 默认：其它应用全部走隧道，只把我们自己的 socket 排除在外。 */
			try {
				builder.addDisallowedApplication(getApplicationContext().getPackageName());
			} catch (NameNotFoundException e) {
			}
		} else {
			for (String appName : prefs.getApps()) {
				try {
					builder.addAllowedApplication(appName);
				} catch (NameNotFoundException e) {
				}
			}
		}
		builder.setSession("tunVPN/mihomo");
		/* 值得记一行：如果 ipv4/ipv6 都关着，就根本没有路由进隧道；而如果范围是
		   "N 个应用"，就只有这些应用会被抓 —— 这两种情况看起来都跟"已连接但没走代理"
		   一模一样。 */
		appendLog("vpn: ipv4=" + ipv4 + " ipv6=" + ipv6 + " mtu=" + prefs.getTunnelMtu()
			+ " scope=" + (prefs.getGlobal() ? "all apps" : prefs.getApps().size() + " app(s)")
			+ " excludeSelf=" + prefs.getGlobal());
		/* 部分应用模式下一个应用都没勾，等于什么都不抓：隧道会显示"已连接"，
		   但实际代理流量为零。这种情况要在日志里写明白。 */
		if (!prefs.getGlobal() && prefs.getApps().isEmpty())
		  appendLog("WARN: 部分应用模式未选择任何应用，将没有任何流量进入隧道（等于不代理）。"
			+ "请到「规则 → 应用」勾选程序，或开启「全局模式」。");
		tunFd = builder.establish();
		if (tunFd == null) {
			failStartup("建立 VPN 接口失败（未授权或被其他 VPN 占用）");
			return;
		}

		/* 初始化 mihomo。它会加载 <homeDir>/config.yaml，而选中的节点本可以在这里用
		   selected-map 直接应用。
		   InitParams 用的是 "home-dir"；同时也带上 "homeDir"，好让老版本内核也能认识
		   （未知字段会被忽略）。 */
		String homeDir = getFilesDir().getAbsolutePath();
		/* clash-api 到底从哪来：在这个 SDK 构建（libmihomo-android v0.3.3）上，
		   InitParams 结构体只带 HomeDir/Version —— 放进 initParams 的
		   external-controller/secret 一律**被忽略**，所以控制接口**必须**由配置
		   （config.yaml）提供，而我们本来就在 MihomoConfig.build() 里写了。实测
		   mixed-port（同样来自配置）能起，但某些构建在 quickSetup 时会跳过
		   external-controller 的监听；遇到这种情况 kickClashApi() 会用 UpdateConfig
		   动作重新应用 external-controller 把它拉起来。这里仍保留 home-dir/homeDir
		   （以及会被忽略的 external-controller/secret），以兼容那些确实会读它们的构建。 */
		String secret = prefs.getSecret();
		String initParams = "{\"home-dir\":\"" + homeDir + "\"," +
			"\"homeDir\":\"" + homeDir + "\"," +
			"\"external-controller\":\"127.0.0.1:" + apiPort + "\"," +
			"\"secret\":\"" + secret + "\"}";
		/* selected-map 故意留空：它会把选中的节点名经 JNI 桥传进内核，而桥把
		   Java 字符串按 "modified UTF-8" 交给内核，会破坏补充平面字符（节点名里
		   的国旗 emoji），内核随即报 "proxy ... not found"，甚至整份配置加载失败
		   （external-controller 因此没监听，REST 全部连不上）。
		   节点改在下面用 REST API 选择，全程标准 UTF-8，名字能精确匹配。 */
		String setupParams = "{\"selected-map\":{}," +
			"\"profile\":\"" + configFile.getAbsolutePath() + "\"}";
		/* 在 quickSetup **之前**就把 mihomo 的日志/事件流接进我们的日志文件，这样内核
		   关于"控制接口为何绑定失败"的原话（例如 "failed to start clash api:
		   listen tcp 127.0.0.1:9090: bind: address already in use"）会落进
		   tproxy.log，而不是被静默丢掉。以前监听器是在 quickSetup **之后**才挂上的，
		   那时绑定早就发生、错误也早就过去了。 */
		try {
			Clash.INSTANCE.setEventListener(new InvokeInterface() {
				@Override
				public void onResult(String result) {
					if (result != null && !result.isEmpty())
					  appendLog("mihomo: " + result);
				}
			});
		} catch (Throwable e) {
		}

		/* 留给"剔掉坏节点再重试"那条路径用（见 retryWithoutRejectedProxy）。 */
		coreInitParams = initParams;
		coreSetupParams = setupParams;
		coreConfigFile = configFile;
		coreCustomConfig = prefs.getCustomConfig();
		configRetry = 0;
		try {
			quickSetupCore();
		} catch (Throwable e) {
			failStartup("启动内核失败：" + e.getMessage());
			return;
		}
		/* quickSetup 的回调可能就在**调用线程**上跑；如果它跑了、而且配置确实坏了，
		   那么重试路径已经处理掉了（或已安排好中止），所以绝不会带着一份死配置走到
		   startTUN。如果重试还在另一个线程上跑，就让它跑完。 */
		if (quickSetupError != null && looksLikeError(quickSetupError)
				&& !configRetryRunning) {
			startupAborted = true;
			failStartup("内核配置错误：" + configErrorReason(quickSetupError));
			return;
		}

		/* 在刚建好的 VPN fd 上把 TUN 拉起来。TunInterface 会把 socket 保护转发给
		   VpnService.protect()，这样内核自己的对外流量永远不会绕回 VPN。 */
		String address = (ipv4 ? tunAddr + "/" + tunPrefix : "") +
			(ipv6 ? (ipv4 ? "," : "") + tunAddr6 + "/64" : "");
		String dns = "223.5.5.5,119.29.29.29";
		final TunInterface tunInterface = new TunInterface() {
			@Override
			public void protect(int fd) {
				TProxyService.this.protect(fd);
			}
			@Override
			public String resolverProcess(int protocol, String source, String target, int uid) {
				return "";
			}
		};

		/* 先用 gvisor：整个协议栈都在用户态，也是其它 Android 客户端默认自带的。
		   system 栈依赖一些并非每个 Android 内核都可靠的 tun 特性，所以把它作为兜底，
		   而不是让整条隧道直接失败。 */
		String[] stacks = { "gvisor", "system" };
		String started = null;
		Throwable lastError = null;
		for (String candidate : stacks) {
			try {
				Clash.INSTANCE.startTUN(tunFd.getFd(), tunInterface, "tunvpn",
					candidate, address, dns, prefs.getTunnelMtu());
				started = candidate;
				break;
			} catch (Throwable e) {
				lastError = e;
				appendLog("startTUN with stack=" + candidate + " failed: " + e);
			}
		}
		if (started == null) {
			failStartup("启动 TUN 失败：" + lastError);
			return;
		}
		appendLog("Clash.startTUN OK (fd=" + tunFd.getFd() + ", stack=" + started
			+ ", addr=" + address + ", mtu=" + prefs.getTunnelMtu() + ")");

		/* 全新的隧道还没选中任何节点：先清掉上一次运行记下的节点，这样在下面那次
		   选择真正生效之前，首页 / 通知栏不会显示一个过期名字。 */
		prefs.setActiveNode("");
		/* 尽力而为：应用用户在订阅里选中的节点。这**不算**一次用户操作 —— 配置刚刚
		   就是按同一份偏好生成的（见 rebuildForSelection）。 */
		selectUserAction = false;
		applySelectedNode(prefs);
		/* 与选择无关：确认控制接口**真的**能应答，不能的话把原因记下来。没有这一步，
		   内核的配置错误只存在于它自己的日志流里、很容易被漏掉 —— 隧道看着已连接，
		   9090 却已经死了。 */
		verifyController();
		/* 主动去催一下 clash-api。quickSetup 会把 mixed-port 起起来，但某些构建会跳过
		   external-controller 的监听，于是即便隧道代理正常，REST API（选择器、
		   /connections、切节点）仍然是死的。kickClashApi() 用 UpdateConfig 动作重新
		   应用 external-controller，把它（重新）拉起来。 */
		new Thread(() -> {
			try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
			if (tunnelStopping)
			  return;              /* the tunnel was torn down meanwhile */
			kickClashApi();
		}).start();
		/* FlClash 式的真实连通性：真的通过选中节点推一个请求并测延迟，而不是只看
		   "API 有应答"（那种检查可能是绿的，流量却一点没走）。 */
		testProxyConnectivity(prefs);

		prefs.clearLastError();
		prefs.setEnable(true);
		QSTileService.requestUpdate(this);

		initNotificationChannel(NOTIFY_CHANNEL);
		createNotification();
		startStats(prefs);
		/* 在回环上暴露控制接口（内核自己**不**绑它）。 */
		startEmbeddedApi();
	}

	/* 所有启动失败都汇总到这里。除了停掉服务，还有两件事很重要：
	     - Enable 必须改回 false。MainActivity 是先把 Enable 置 true 再来要求我们启动的，
	       所以少了这一步，UI 会一直显示一条**从没起来**的隧道为"已连接"。
	     - 失败原因要持久化，让 UI 能说出**为什么**失败，而不是默默回到"未连接"。 */
	private void failStartup(String reason) {
		appendLog("FATAL: " + reason);
		Preferences p = new Preferences(this);
		p.setLastError(reason);
		p.setEnable(false);
		QSTileService.requestUpdate(this);

		/* 配置错误是**异步**报出来的 —— 也就是在 VPN fd 已经建好之后 —— 所以这里也要
		   把它拆掉，否则系统会一直挂着一条已经死掉的 VPN。 */
		if (tunFd != null) {
			try {
				Clash.INSTANCE.stopTun();
			} catch (Throwable e) {
			}
			try {
				tunFd.close();
			} catch (IOException e) {
			}
			tunFd = null;
		}

		Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
		stopForeground(true);
		stopSelf();
	}

	/* 内核的 quickSetup 结果是否表示出错了。成功时桥返回空结果，但这里仍保持保守，
	   免得某个状态字符串被误当成致命的配置错误。 */
	private static boolean looksLikeError(String s) {
		if (s == null || s.isEmpty())
		  return false;
		String t = s.toLowerCase();
		return t.contains("error") || t.contains("not found") || t.contains("fail")
			|| t.contains("invalid") || t.contains("yaml") || t.contains("unsupported")
			|| t.contains("cannot") || t.contains("no such") || t.contains("unknown")
			|| t.contains("proxy") || t.contains("group");
	}

	/* 把当前配置交给内核。单独抽出来是因为"节点被拒"时要重写配置再调一次。 */
	private void quickSetupCore() {
		final String init = coreInitParams;
		final String setup = coreSetupParams;
		if (init == null || setup == null)
		  return;
		Clash.INSTANCE.quickSetup(init, setup, new InvokeInterface() {
			@Override
			public void onResult(String result) {
				if (result == null || result.isEmpty()) {
					appendLog("mihomo quickSetup OK");
					quickSetupError = null;
					configRetryRunning = false;
					return;
				}
				/* 非空结果 = 内核在报告它拿到的那份配置有问题（未知的节点 / 组、
				   规则不对 ……）。这种情况下整个会话都没法工作 —— 没有组、没有规则、
				   也没有控制接口 —— 所以把文本留下来，如果内核点明了某个具体节点，
				   就尝试恢复。 */
				quickSetupError = result;
				appendLog("mihomo quickSetup: " + result);
				if (looksLikeError(result))
				  retryWithoutRejectedProxy(result);
			}
		});
	}

	/* mihomo 是**一次性**校验整份配置的：只要有一个非法节点
	   （"proxy 645: invalid REALITY short ID"），它就会拒绝**整份**文件 —— 隧道起不来，
	   所有延迟测试也一起废。所以在放弃之前，精确地剔掉那个节点、重写配置再交一次。
	   有次数上限，且只对**自动生成**的配置生效：手工编辑的自定义配置绝不去动。 */
	private void retryWithoutRejectedProxy(final String err) {
		if (coreCustomConfig || configRetry >= MAX_CONFIG_RETRIES) {
			abortOnConfigError(err);
			return;
		}
		configRetryRunning = true;
		String bad = MihomoConfig.badProxyNameFromError(err, readTextFile(coreConfigFile));
		if (bad == null || bad.isEmpty() || !rejectedNodes.add(bad)) {
			configRetryRunning = false;
			abortOnConfigError(err);
			return;
		}
		configRetry++;
		appendLog("config: 内核拒绝节点「" + bad + "」（" + err
			+ "）→ 已排除该节点，重新生成配置并重试（第 " + configRetry + " 次）");
		try {
			MihomoConfig.build(this, prefs, rejectedNodes);
		} catch (Throwable e) {
			appendLog("config: 重新生成配置失败：" + e);
			configRetryRunning = false;
			abortOnConfigError(err);
			return;
		}
		quickSetupCore();
	}

	/* 整份读取我们刚写出的配置（不大、UTF-8）。 */
	private static String readTextFile(File f) {
		if (f == null || !f.exists())
		  return null;
		/* 用 try-with-resources：以前读取抛异常会泄漏 fd，而这段在每次配置重试时都跑。 */
		try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
			  bos.write(buf, 0, n);
			return new String(bos.toByteArray(), "UTF-8");
		} catch (Throwable e) {
			return null;
		}
	}

	/* 带着内核自己的报错信息停掉服务。必须在**主线程**跑，因为 quickSetup 回调可能来自
	   后台线程，而 failStartup 会弹 Toast；同时做了保护，最多只触发一次。 */
	private void abortOnConfigError(final String reason) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (startupAborted)
				  return;
				startupAborted = true;
				failStartup("内核配置错误：" + configErrorReason(reason));
			}
		});
	}

	/* 把内核的配置错误变成"可以采取行动"的信息。最常见的情况是手工编辑的配置坏了：
	   它引用了不存在的节点/组，于是就别再继续用它（否则每次重连都以同样方式失败），
	   让下次连接自动重新生成一份可用的文件。 */
	private String configErrorReason(String reason) {
		String msg = reason;
		Preferences p = new Preferences(this);
		if (p.getCustomConfig()) {
			p.setCustomConfig(false);
			msg += "\n自定义配置有误，已关闭「使用自定义配置」，下次连接会按当前设置自动重新生成。";
		}
		return msg;
	}

	/* 节点选择器、代理流量统计、"是否走代理"的检查都要通过回环控制接口找内核，所以
	   加载的配置必须把它暴露出来。手工编辑的自定义配置常常没有 —— 这正是"隧道明明在
	   跑，首页计数却一直是 0"的原因。这里把缺的键补上（并记一笔），而不是默默报 0。 */
	private void ensureControlApi(File configFile, Preferences prefs) {
		try {
			byte[] buf = new byte[(int) configFile.length()];
			int n;
			try (java.io.FileInputStream in = new java.io.FileInputStream(configFile)) {
				n = in.read(buf);
			}
			String text = new String(buf, 0, n, "UTF-8");

			String ec = topLevelLine(text, "external-controller:");
			/* 监听地址必须是回环：在本构建上 mihomo 绑 0.0.0.0 会**静默失败**
			   （"9090 从没监听"，浏览器也连不上），而绑 127.0.0.1 却正常。
			   所以任何非回环 / 端口不对的行都改写成 127.0.0.1:<端口>。 */
			boolean needEc = (ec == null)
				|| !ec.contains("127.0.0.1:" + MihomoConfig.API_PORT);
			boolean needMp = !hasTopLevelKey(text, "mixed-port:")
				&& !hasTopLevelKey(text, "port:");
			/* 采用自定义配置里手工设的 secret，好让 App 的控制请求能用它通过鉴权；
			   没有的话就注入 App 自己生成的那个令牌。两种情况都保证**配置与客户端用同一
			   个 bearer 令牌**。 */
			String existingSecret = topLevelLine(text, "secret:");
			String secretVal = null;
			if (existingSecret != null) {
				String v = existingSecret.trim();
				/* 先去掉 "secret:" 这个键名前缀，再去掉两边的引号，这样
				   "secret: \"\"" 会得到 null（空），而不是那个被截坏的值
				   "secret: \"" —— 后者会被持久化进 Preferences，
				   让之后每一次鉴权都失败。 */
				if (v.startsWith("secret:")) v = v.substring("secret:".length());
				v = v.trim();
				if (v.startsWith("\"")) v = v.substring(1);
				if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
				v = v.trim();
				if (!v.isEmpty()) secretVal = v;
			}
			boolean needSecret = (secretVal == null);
			if (secretVal != null)
			  prefs.setSecret(secretVal);
			if (!needEc && !needMp && !needSecret)
				return;

			/* 只重建一次文件：把 external-controller 行改写成选定的回环端口（没有就追加），
			   对齐 secret（没有就追加），最后在缺 mixed-port 时补上。重复的顶层键会让
			   YAML 非法，所以一律**原地替换**（见 docs/内核接口参考.md §5）。 */
			StringBuilder out = new StringBuilder();
			boolean ecWritten = false;
			boolean secretWritten = false;
			for (String line : text.split("\n", -1)) {
				if (needEc && topLevelLine(line + "\n", "external-controller:") != null) {
					out.append("external-controller: 127.0.0.1:")
					   .append(MihomoConfig.API_PORT).append('\n');
					ecWritten = true;
				} else if (needSecret && topLevelLine(line + "\n", "secret:") != null) {
					out.append("secret: \"").append(prefs.getSecret()).append("\"\n");
					secretWritten = true;
				} else {
					out.append(line).append('\n');
				}
			}
			if (needEc && !ecWritten)
				out.append("external-controller: 127.0.0.1:")
					.append(MihomoConfig.API_PORT).append('\n');
			if (needSecret && !secretWritten)
				out.append("secret: \"").append(prefs.getSecret()).append("\"\n");
			if (needMp)
				out.append("mixed-port: ").append(prefs.getProxyPort()).append('\n');

			try (java.io.FileOutputStream fos =
					new java.io.FileOutputStream(configFile, false)) {
				fos.write(out.toString().getBytes("UTF-8"));
			}
			appendLog("config: 自定义配置已对齐 App 必需项（external-controller=127.0.0.1:"
				+ MihomoConfig.API_PORT
				+ (needMp ? ", mixed-port=" + prefs.getProxyPort() : "")
				+ (needSecret ? ", secret" : "") + "）");
		} catch (Exception e) {
			appendLog("config: 检查自定义配置失败：" + e);
		}
	}

	/* App 自己依赖的那几个键的一行摘要：这样"隧道能跑但计数/选择器是死的"就能追溯到
	   配置上。 */
	private static String configKeySummary(File configFile) {
		try {
			byte[] buf = new byte[(int) configFile.length()];
			java.io.FileInputStream in = new java.io.FileInputStream(configFile);
			int n = in.read(buf);
			in.close();
			String text = new String(buf, 0, n, "UTF-8");
			String ec = topLevelLine(text, "external-controller:");
			String mp = topLevelLine(text, "mixed-port:");
			if (mp == null)
			  mp = topLevelLine(text, "port:");
			return "external-controller="
				+ (ec == null ? "缺失!" : ec.substring("external-controller:".length()).trim())
				+ ", mixed-port="
				+ (mp == null ? "缺失!" : mp.substring(mp.indexOf(':') + 1).trim());
		} catch (Exception e) {
			return "(读取配置失败: " + e + ")";
		}
	}

	/* "key" 作为**顶层** YAML 键（第 0 列）出现时为 true。 */
	private static boolean hasTopLevelKey(String text, String key) {
		return topLevelLine(text, key) != null;
	}

	/* 以 `key` 开头的整行顶层内容；没有则返回 null。 */
	private static String topLevelLine(String text, String key) {
		int idx = 0;
		while ((idx = text.indexOf(key, idx)) >= 0) {
			int lineStart = text.lastIndexOf('\n', idx) + 1;
			if (idx - lineStart == 0) {
				int end = text.indexOf('\n', idx);
				return end < 0 ? text.substring(idx).trim()
					: text.substring(idx, end).trim();
			}
			idx += key.length();
		}
		return null;
	}

	/* 顶层 select 组应默认指向的 url-test 子组；配置里已经选好了、无需再选时返回 null。 */
	private static String autoTargetGroup(Preferences prefs) {
		/* 生成的配置已经把顶层组默认指向 GLOBAL_GROUP（即整个"按国家筛选后的"池的
		   url-test 组），所以自动模式下没有什么需要再选的。 */
		return null;
	}

	/* 让 mihomo 在我们自建的组里选中指定的节点/子组。自动模式下选的是该国 url-test
	   子组（之后由它自己的健康检查持续挑出该国最快的节点）；手动模式下用户点哪个就
	   选哪个。 */
	private void applySelectedNode(Preferences prefs) {
		if (prefs.getAutoSelect()) {
			String target = autoTargetGroup(prefs);
			/* null = "自动选最佳国家"：生成的配置已经把该国的 url-test 组排在第一位，
			   所以无需再选。 */
			if (target != null)
			  selectInGroup(MihomoConfig.GROUP, target);
			return;
		}
		String sel = prefs.getSubSelected();
		if (sel == null || sel.isEmpty())
		  return;
		selectInGroup(MihomoConfig.GROUP, sel);
	}

	/* 让 mihomo 在我们自建的组里选中指定的节点/组。这里走 external-controller 的
	   REST API（回环 :9090），**不用** JNI 的 invokeAction 桥：桥把 Java 字符串按
	   "modified UTF-8" 交给内核，会破坏补充平面字符（节点名里的国旗 emoji），于是
	   changeProxy 报 "proxy not exist"。REST 全程标准 UTF-8，名字与配置注册的完全一致。 */
	private void selectInGroup(String group, String proxy) {
		appendLog("selected: " + proxy + " in group " + group);
		/* 尽力而为且有网络等待，所以放到别的线程上跑。 */
		new Thread(() -> {
			/* 内核是先起 TUN、之后才绑 external-controller 的 HTTP 监听；订阅很大时
			   解析配置会让那个监听多关好几秒。只探一次就会报"未就绪"并**静默丢掉**
			   用户的选择 —— 而隧道其实已经在代理了（流量会继续走默认节点），所以这种
			   失败极不容易被察觉。因此这里一直重试到控制接口真的在监听（或隧道被拆掉）
			   为止，而不是探一小会儿就放弃。 */
			for (int attempt = 1; attempt <= 120; attempt++) {
				if (startupAborted || tunnelStopping)
				  return;
				if (waitForController()) {
					applySelector(group, proxy);
					return;
				}
				if (attempt == 1)
				  appendLog("selector: 控制接口 " + controllerHost + ":" + MihomoConfig.API_PORT
						+ " 未就绪，后台持续重试中");
				if (attempt % 20 == 0)
				  appendLog("selector: 仍等待 " + controllerHost + ":" + MihomoConfig.API_PORT
						+ "（" + lastControllerError + "）");
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (startupAborted)
			  return;
			appendLog("selector set failed: 控制接口 " + controllerHost + ":"
				+ MihomoConfig.API_PORT + " 未就绪（内核可能未监听）：" + lastControllerError);
		}).start();
	}

	/* 控制接口可达之后，把选中的节点 PUT 进 `group`。抽成独立方法是为了让
	   selectInGroup 能重试它，而不必重复一遍"解析 + PUT"的逻辑。 */
	private void applySelector(String group, String proxy) {
		try {
			String target = null;
			try {
				target = resolveMember(group, proxy);
			} catch (Throwable ignore) {
			}
			if (target == null)
			  target = proxy; /* 最后的办法：直接用原始名字试一次 */
			int code = putSelector(group, target);
			boolean ok = (code >= 200 && code < 300);
			String note = ok ? "ok" : ("http " + code);
			if (!target.equals(proxy))
			  note += " (resolved to " + target + ")";
			appendLog("selector set: " + note);
			if (ok) {
				/* 这次选择**已经生效**：记下内核真正在用的节点，这样首页 / 通知栏
				   就不会继续显示一个过期节点。 */
				syncActiveNode(group);
			} else {
				/* 这个节点不在内核当前运行的组里 —— 通常是因为节点池是更早烘焙进去的
				   （用户刚改过国家筛选，或者配置早于这次选择）。mihomo 切不过去，
				   所以让这次选择真正生效的**唯一**办法是重建配置：只改偏好等于让旧节点
				   继续承载流量，而界面显示的是新节点。 */
				rebuildForSelection("目标节点不在当前内核配置的组里");
			}
			testProxyConnectivity(new Preferences(this));
		} catch (Throwable e) {
			appendLog("selector set skipped: " + e);
		}
	}

	/* 记录内核为 `group` **真正选中**的节点（自动模式下还会往下钻到该国
	   url-test 组背后的那个节点）。存进 Preferences，这样 UI 进程和本服务的通知栏
	   显示的都是事实。 */
	private void syncActiveNode(String group) {
		try {
			String secret = new Preferences(this).getSecret();
			String name = nowOf(group, 0, secret);
			if (name == null || name.isEmpty())
			  return;
			Preferences p = new Preferences(this);
			if (!name.equals(p.getActiveNode())) {
				p.setActiveNode(name);
				appendLog("active node: " + name);
				updateNotification();
			}
		} catch (Throwable e) {
			appendLog("active node: 读取失败 " + e);
		}
	}

	/* GET /proxies/{group} 并顺着 `now` 往下钻（自动模式选中的是某个国家的
	   url-test 组，真正干活的节点在它下面由健康检查决定）。API 读不到时返回 null。 */
	private String nowOf(String group, int depth, String secret) throws IOException {
		ApiResult r = bridgeApi("GET", apiHost, MihomoConfig.API_PORT,
			"/proxies/" + encodePath(group), null, secret);
		if (r.code == -1 || r.body == null)
		  return null;
		JSONObject o;
		try {
			o = new JSONObject(r.body);
		} catch (JSONException e) {
			return null;
		}
		String now = o.optString("now", "");
		if (now.isEmpty())
		  return "";
		/* 代理组的应答里有 `all` 字段，真正的节点没有。 */
		if (depth < 2 && o.has("all")) {
			String inner = nowOf(now, depth + 1, secret);
			if (inner != null && !inner.isEmpty())
			  return inner;
		}
		return now;
	}

	/* 这次选择没法应用到运行中的内核上：重建一次配置（配置是启动时烘焙好的，
	   新的选择/国家只存在于**新生成**的那份里）。有去抖，因为重建会再次调用
	   applySelectedNode —— 否则一个根本不可能生效的选择会无限循环下去。 */
	private void rebuildForSelection(final String why) {
		long now = System.currentTimeMillis();
		/* 只有**用户主动**的选择才值得重建：启动时那次自动应用读的就是刚刚用同一份
		   偏好生成的配置，重建什么都不会变，只会白白拆掉一条正在工作的隧道。 */
		if (!selectUserAction) {
			appendLog("selector: " + why + "（启动时自动应用，重建无意义，仅提示）");
			return;
		}
		if (prefs != null && prefs.getCustomConfig()) {
			appendLog("selector: 使用自定义配置，所选节点不在其中，无法自动切换；"
				+ "请在自定义配置里包含该节点");
			return;
		}
		if (now - lastSelectRebuildMs < 60000) {
			appendLog("selector: " + why + " → 60s 内已重建过一次，跳过自动重建");
			return;
		}
		if (tunFd == null) {
			appendLog("selector: " + why + "（隧道未在运行，无需重建）");
			return;
		}
		lastSelectRebuildMs = now;
		appendLog("selector: " + why + " → 自动重建隧道配置，使这次选择立即生效");
		/* rebuildTunnel() 会拆掉 fd 再重建，所以必须跑在 onStartCommand 干活的那个
		   线程上：主线程。 */
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (tunFd != null)
				  rebuildTunnel();
			}
		});
	}

	/* 轮询内核的控制接口直到它应答，这样启动后紧接着的选择器 PUT 就不会和内核自己的
	   HTTP 监听抢跑。窗口期内始终没起来则返回 false（此时日志会写明原因，
	   而不是让这次失败无声无息）。 */
	private boolean waitForController() {
		for (int i = 0; i < 24; i++) {
			if (isControllerUp())
			  return true;
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				return false;
			}
		}
		return false;
	}

	/* 确认控制接口已就绪。mihomo 只有在把 TUN 拉起来、并解析完（可能很大的）配置之后
	   才会绑定 clash-api 的 HTTP 监听，所以这里**轮询 90 秒**，而不是 6 秒就放弃 ——
	   过早地报"未就绪"正是我们反复遇到的误导信号。如果它真的从没监听，就明确说出来，
	   并指向内核自己的日志。 */
	private void verifyController() {
		new Thread(() -> {
			boolean ok = false;
			for (int i = 0; i < 90; i++) {
				if (isControllerUp())
				  { ok = true; break; }
				if (tunnelStopping)
				  return;           /* 断开连接必须能结束这个轮询，而不是让用户干等 */
				try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
			}
			if (ok) {
				appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT + " ready");
				return;
			}
			if (tunnelStopping)
			  return;
			/* 内核把 mixed-port 起起来了，却跳过了 external-controller 的监听。
			   先用 UpdateConfig 动作强推一次，然后再轮询一遍；如果这样就起来了，
			   就不必输出那堆吓人的"never ready"转储。 */
			kickClashApi();
			boolean recovered = false;
			for (int i = 0; i < 30; i++) {
				if (isControllerUp()) { recovered = true; break; }
				if (tunnelStopping)
				  return;
				try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
			}
			if (recovered) {
				appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT
					+ " ready（经 UpdateConfig 兜底启动）");
				return;
			}
			appendLog("controller: " + controllerHost + ":" + MihomoConfig.API_PORT
				+ " NOT ready（90s 内未监听，核心未启动 clash-api）");
			if (lastControllerError != null)
			  appendLog("controller: 最后一次探测：" + lastControllerError);
			if (quickSetupError != null && !quickSetupError.isEmpty())
			  appendLog("controller: 内核配置加载报错：" + quickSetupError);
			/* 某些 Android 构建只把 IPv6 回环暴露给 App 进程；如果 mihomo 绑的是 [::1]，
			   那么即使 API 活着，IPv4 探测也会失败 —— 所以也探一下它，好把两种情况
			   区分开。 */
			if (probeHost("::1"))
			  appendLog("controller: 但 [::1]:" + MihomoConfig.API_PORT
				  + " 通了 —— 核心绑在 IPv6 回环，App 走 IPv4 才失败");
			/* 确定到底是"选的 API 端口确实还被占着"（残留隧道 / adb forward / 模拟器
			   占着 9090），还是"端口空着但 mihomo 拒绝绑定"（配置 / 权限问题）。
			   排"9090 起不来"时，这一行信息量最大。 */
			boolean portFree = false;
			java.net.ServerSocket probe = null;
			try {
				probe = new java.net.ServerSocket();
				probe.setReuseAddress(true);
				probe.bind(new java.net.InetSocketAddress("127.0.0.1", MihomoConfig.API_PORT));
				portFree = true;
			} catch (Throwable e) {
				appendLog("controller: 端口 127.0.0.1:" + MihomoConfig.API_PORT
					+ " 仍被占用（" + e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : (": " + e.getMessage()))
					+ "）—— 这就是控制接口起不来的直接原因");
			} finally {
				if (probe != null) {
					try { probe.close(); } catch (Throwable ignore) { }
				}
			}
			if (portFree)
			  appendLog("controller: 127.0.0.1:" + MihomoConfig.API_PORT
				+ " 当前空闲但仍未监听 —— 不是端口占用，而是 mihomo 绑定/配置问题（见上方 mihomo: 日志）");
			if (probeHost(deviceHost()))
			  appendLog("controller: 但 " + deviceHost() + ":" + MihomoConfig.API_PORT
				+ " 通了 —— 核心绑在网卡/LAN 地址，App 应改连该地址（见下 listen: 行）");
			dumpListeningPorts();
			dumpControllerLog();
			dumpRawLog();
			dumpMihomoLog();
			dumpConfigTail();
			}).start();
			}

			/* 强制把 clash-api（external-controller）的监听拉起来。在某些
			   libmihomo-android 构建上，quickSetup 会把 mixed-port 起起来，却从不启动
			   external-controller 的监听 —— 即便配置里明明有 external-controller + secret
			   —— 于是隧道代理正常，而 REST API（选择器、/connections、切节点）却是死的。
			   mihomo 的 UpdateConfig 动作会重新应用通用配置并（重新）启动那个监听。
			   启动后主动调用一次，verifyController 里也作为兜底调用；本来就已经起来的话
			   没有任何副作用。 */
			private void kickClashApi() {
				/* 尽力而为：用进程内的 startListener 动作（重新）启动内核的各个监听。
				   在 libmihomo-android v0.3.3 上，quickSetup 会起 mixed-port，却把
				   clash-api（9090）丢下不管；startListener 会重建每一个监听（含
				   external-controller），这样 REST API（选择器、/connections、切节点）
				   对外部客户端也可用。当然，App 内的统计一直都能走 apiAction 桥，与此无关。 */
				try {
					String json = "{\"id\":\"\",\"method\":\"startListener\",\"data\":null}";
					appendLog("clash-api: 尝试用 startListener 拉起监听（含 external-controller 9090）");
					Clash.INSTANCE.invokeAction(json, new InvokeInterface() {
						@Override
						public void onResult(String result) {
							appendLog("clash-api: startListener 结果: "
								+ (result == null || result.isEmpty() ? "ok" : result));
						}
					});
				} catch (Throwable e) {
					appendLog("clash-api: invokeAction 失败: " + e);
				}
			}

		/* 一锤定音：读 /proc/net/tcp[6]，报出我们关心的端口（API 9090、mixed 7890）
		   到底有没有在 LISTEN、绑在哪个地址。这样就能确定是"mihomo 压根没绑 clash-api"
		   （即"9090 从没监听"那个症状）还是纯粹的连通性问题，并给出确切绑定地址。 */
			private void dumpListeningPorts() {
			for (String file : new String[] { "/proc/net/tcp", "/proc/net/tcp6" }) {
				java.io.File f = new java.io.File(file);
				if (!f.exists()) continue;
				try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
					String l; boolean header = true;
					while ((l = r.readLine()) != null) {
						if (header) { header = false; continue; }
						String[] c = l.trim().split("\\s+");
						if (c.length < 4) continue;
						if (!"0A".equalsIgnoreCase(c[3])) continue; /* 0A = LISTEN */
						String local = c[1];
						int colon = local.indexOf(':');
						if (colon < 0) continue;
						int port;
						try { port = Integer.parseInt(local.substring(colon + 1), 16); }
						catch (Throwable e) { continue; }
						if (port == MihomoConfig.API_PORT || port == 7890)
						  appendLog("listen: " + file + " " + local + " (port " + port + ")");
					}
				} catch (Throwable ignore) { }
			}
			appendLog("listen: 以上为 9090/7890 的实际 LISTEN 状态（无条目=该端口未监听）");
			}

			/* 对 host:API_PORT 做一次快速 TCP 连接探测，用来区分"只是 IPv4 失败"和
	   "API 起来了但在另一个回环地址上"。 */
	private boolean probeHost(String host) {
		java.net.Socket s = null;
		try {
			s = new java.net.Socket();
			protectLocalSocket(s);
			s.connect(new java.net.InetSocketAddress(host, MihomoConfig.API_PORT), 800);
			return true;
		} catch (Throwable e) {
			return false;
		} finally {
			if (s != null) {
				try { s.close(); } catch (Throwable ignore) { }
			}
		}
	}

	/* API 始终绑不上时，第一个该怀疑的就是生成的配置（external-controller 行写坏、
	   多出一个重复键、缩进错位）。这里把 config.yaml 的尾部转储出来 —— 也就是 App
	   追加的那些段（dns/log/tun/external-controller/...）—— 这样日志本身就够诊断，
	   不必再从设备里把文件捞出来。 */
	private void dumpConfigTail() {
		File f = new File(getFilesDir(), "config.yaml");
		if (!f.exists()) {
			appendLog("controller: 配置未生成（config.yaml 不存在）");
			return;
		}
		try {
			java.util.List<String> lines = new java.util.ArrayList<String>();
			try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
				String l;
				while ((l = r.readLine()) != null)
				  lines.add(l);
			}
			int start = Math.max(0, lines.size() - 50);
			StringBuilder sb = new StringBuilder("controller: config.yaml 尾部（共 ")
				.append(lines.size()).append(" 行）：\n");
			for (int i = start; i < lines.size(); i++)
			  sb.append("  ").append(lines.get(i)).append('\n');
			appendLog(sb.toString());
		} catch (Throwable e) {
			appendLog("controller: 读取配置失败：" + e);
		}
	}

	/* 对控制接口做一次快速可达性探测（不重试）。verifyController 用它；选择器 /
	   测速的循环用的是内部会重试的 waitForController。 */
	/* clash-api 绑的是回环（external-controller: 127.0.0.1），而且在 allow-lan 关闭时
	   只服务回环客户端 —— 所以设备 IP **永远**不是有效的控制器地址。以前把它当兜底
	   去探，结果会 latch 到一个根本没有监听的地址上，导致每次测试都判失败；这里固定
	   用 127.0.0.1。 */
	private boolean isControllerUp() {
		/* 主判据：进程内的动作桥**不需要**任何 HTTP 监听就能到达内核，所以只要
		   getConnections 有应答，控制端就算"就绪" —— 不管 mihomo 到底有没有在 9090 上
		   绑 external-controller（这个构建上就没有）。下面的 HTTP 探测只是尽力而为的
		   兜底，用来给**外部客户端**解析出确切的绑定地址；连不上也不再算失败，
		   这样就消除了那个误导性的"9090 NOT ready"大转储。 */
		if (isCoreReachable())
		  return true;
		for (String h : new String[] { "127.0.0.1", deviceHost(), "::1" }) {
			if (h == null) continue;
			if (probeVersion(h)) {
				controllerHost = h;
				apiHost = h;
				appendLog("controller: " + h + ":" + MihomoConfig.API_PORT + " ready");
				return true;
			}
		}
		return false;
	}

	/* === 走"绕过 VPN"的 socket 访问本地控制接口 ==============================
	   本服务创建的 VPN 会把 App 自己的 socket 也抓走，它们就永远到不了 mihomo 的回环
	   监听（也就是"9090 从没监听"那个症状）。protect() 把每个 socket 从 VPN 路由里
	   摘出来，App 才能和自己的内核通话。各个 Activity 通过静态钩子复用 localApi()。 */
	private static TProxyService sInstance;
	/* mihomo 的 clash-api **实际**绑定到的主机（127.0.0.1 / 局域网 IP / [::1]），
	   启动时由 isControllerUp 解析出来，这样无论内核决定在哪监听，每次 localApi
	   调用都能找到它。 */
	private String apiHost = "127.0.0.1";
	static String apiBaseHost() {
		return sInstance != null ? sInstance.apiHost : "127.0.0.1";
	}
	static boolean protectLocalSocket(java.net.Socket s) {
		return sInstance != null && sInstance.protect(s);
	}
	/* 把记录下来的"最近请求"从内存列表和持久化存储里一起清空。由「最近请求」页的
	   "清空"操作调用；不清内存的话，后台的下一次落盘就会把列表又写回来。
	   lastRecentFlush 也一并重置，免得随后那次落盘被 2 秒的节流跳过。 */
	public static void clearRecentRequests() {
		if (sInstance == null)
		  return;
		synchronized (sInstance.recentRequests) {
			sInstance.recentRequests.clear();
			sInstance.lastRecentFlush = 0;
		}
		sInstance.flushRecentRequests(android.os.SystemClock.elapsedRealtime());
	}
	/* 首页「重置累计」：把**内存里**的流量计数器也一起归零并重新定基准。
	   必须在**采样线程**上做（post 到 statsHandler）—— 否则下一秒的 sampleStats()
	   会立刻用旧值把 preferences 再写回去，症状正是"界面上当时清空了、实际没重置"。
	   服务没在跑时无需处理：下次 startStats() 会从 preferences 重新读基准（已经是 0）。 */
	static void resetTraffic() {
		TProxyService inst = sInstance;
		if (inst == null)
		  return;
		Handler h = inst.statsHandler;
		if (h != null)
		  h.post(new Runnable() {
			@Override public void run() {
				TProxyService i = sInstance;
				if (i != null)
				  i.zeroTraffic();
			}
		});
	}
	/* 统计轮询抓到的**最新** /connections 快照；隧道没运行 / 还没轮询过时为 null。
	   「连接」页面读它，从而不必自己发一次桥调用。 */
	static String lastConnectionsSnapshot() {
		return sInstance != null ? sInstance.connSnapshot : null;
	}
	static final class ApiResult {
		int code = -1;   /* -1 = 传输失败（够不到 API） */
		String body;
		long rtt;
		/* code == -1 时的异常细节：用来区分"还没人监听"（预热阶段的连接被拒）和
		   "socket 被 VPN 抓走了 / protect 没生效"（超时）。据此才能把错误说准。 */
		String error;
	}
	/* 在 host:port 上做 GET/PUT，socket **绕过 VPN**。protect() 是官方推荐的、
	   把 App 自己的 socket 从它自建的隧道里摘出去的办法。读 body 是按**字节**读的，
	   这样多字节（中文）JSON 不会被 Content-Length 截断。 */
	static ApiResult localApi(String method, String host, int port, String path,
			String body, String secret) {
		return localApi(method, host, port, path, body, secret, 6000);
	}

	/* 同上，但显式指定 socket 超时。有些回包合理地会超过默认的 6 秒 ——
	   GET /proxies/{name}/delay 会在内核里跑满它自己的 `timeout` ——
	   提前掐断会让一个"能用但慢"的节点完全拿不到判定。 */
	static ApiResult localApi(String method, String host, int port, String path,
			String body, String secret, int timeoutMs) {
		ApiResult r = new ApiResult();
		long t0 = System.currentTimeMillis();
		java.net.Socket s = null;
		boolean prot = false;
		try {
			s = new java.net.Socket();
			prot = protectLocalSocket(s);
			/* 连接要么很快成立、要么根本没戏，所以连接超时给短一点，
			   把调用方的额度留给"等回包"。 */
			s.connect(new java.net.InetSocketAddress(host, port),
				Math.min(6000, Math.max(2000, timeoutMs)));
			s.setSoTimeout(Math.max(2000, timeoutMs));
			java.io.InputStream is = s.getInputStream();
			java.io.OutputStream os = s.getOutputStream();
			byte[] bodyBytes = body == null ? new byte[0]
				: body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder();
			head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
				.append("Host: ").append(host).append(':').append(port).append("\r\n");
			if (secret != null && !secret.isEmpty())
			  head.append("Authorization: Bearer ").append(secret).append("\r\n");
			head.append("Accept: */*\r\n").append("Connection: close\r\n");
			if (body != null) {
				head.append("Content-Type: application/json\r\n")
					.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
			}
			head.append("\r\n");
			os.write(head.toString().getBytes(StandardCharsets.UTF_8));
			if (body != null) os.write(bodyBytes);
			os.flush();
			String status = readLineBytes(is);
			if (status == null) { r.rtt = System.currentTimeMillis() - t0; return r; }
			try { r.code = Integer.parseInt(status.split(" ")[1]); } catch (Throwable ignore) {}
			int contentLength = -1; boolean chunked = false; String line;
			while ((line = readLineBytes(is)) != null && !line.isEmpty()) {
				String ll = line.toLowerCase();
				if (ll.startsWith("content-length:")) {
					try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Throwable ignore) {}
				} else if (ll.startsWith("transfer-encoding:") && ll.contains("chunked"))
				  chunked = true;
			}
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			if (chunked) {
				while (true) {
					String sz = readLineBytes(is); if (sz == null) break;
					int i = sz.indexOf(';'); if (i >= 0) sz = sz.substring(0, i);
					int len; try { len = Integer.parseInt(sz.trim(), 16); } catch (Throwable e) { len = 0; }
					if (len <= 0) break;
					byte[] buf = new byte[len]; int got = 0;
					while (got < len) { int n = is.read(buf, got, len - got); if (n < 0) break; got += n; }
					bos.write(buf, 0, got);
					readLineBytes(is); /* 每个分块后面跟着的 CRLF */
				}
			} else if (contentLength >= 0) {
				byte[] buf = new byte[2048]; int total = 0;
				while (total < contentLength) {
					int n = is.read(buf, 0, Math.min(buf.length, contentLength - total));
					if (n < 0) break; bos.write(buf, 0, n); total += n;
				}
			} else {
				byte[] buf = new byte[2048]; int n;
				while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
			}
			r.body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Throwable e) {
			r.code = -1;
			r.error = (prot ? "" : "[protect未生效] ")
				+ e.getClass().getSimpleName()
				+ (e.getMessage() != null ? (": " + e.getMessage()) : "");
		} finally {
			if (s != null) try { s.close(); } catch (Throwable ignore) {}
		}
		r.rtt = System.currentTimeMillis() - t0;
		return r;
	}

	/* 把所有 apiAction() 桥调用**串行化** —— 原生桥一次只支持一个在途回调，
	   并发调用会丢结果（见 apiAction）。用 static 是因为调用方散落在服务、各个
	   Activity 和内嵌的 clash-api 服务里。 */
	private static final Object API_LOCK = new Object();
	/* 动作桥要的 `data` 是**字符串形式的 JSON**（FlClash 那种形状）还是内联的 JSON
	   对象：-1 未知、1 字符串、0 对象。由 apiActionRaw 实测一次后定下来 ——
	   见那里的说明。 */
	private static volatile int dataAsString = -1;
	/* 串行化那个"只决定一次"的过程（见 apiActionRaw）。 */
	private static final Object DETECT_LOCK = new Object();
	/* accumulateProxy() 在 statsThread 上抓到的**最新** /connections 快照
	   （留给进程内的调用方用）。 */
	private volatile String connSnapshot = null;
	/* 那份快照的**紧凑形式**，发布到 SharedPreferences，好让「连接」页跨进程读到它 ——
	   那个页面跑在**主进程**，够不到桥，也拿不到 sInstance。有长度上限，
	   而且只在内容真的变化时才重写。 */
	private static final int MAX_CONN_SNAPSHOT = 200;
	private volatile String lastConnSnapshotJson = "";

	/* === 进程内的控制桥 ======================================================
	   libmihomo-android v0.3.3 的 quickSetup 会把 mixed-port（7890）起起来，却常常
	   根本不绑 external-controller（clash-api）在 9090 上的 HTTP 监听
	   （"9090 从没监听"）—— 哪怕配置里明明有 external-controller + secret。于是隧道
	   代理正常，而所有 REST 端点（/connections、/proxies、切选择器）全废。
	   该 SDK 还通过 Clash.INSTANCE.invokeAction（"action" 机制）暴露了**同一批**动作：
	   传入 {"id","method","data"}，返回 ActionResult {"id","method","data","code"}。
	   这些都在**进程内**跑、完全不需要 HTTP 监听，所以无论 9090 如何，连接 / 节点 /
	   流量都够得到。我们在异步回调上阻塞等待（回调在 JNI 线程上触发，不会死锁）。 */
	static String apiAction(String method, String data) {
		return apiAction(method, data, 6000);
	}

	/* 同上，但显式指定回调等待时长。有些动作合理地会超过默认的 6 秒 —— 隔离式节点
	   延迟探测会跑满它自己的 `timeout`（最长 30 秒）—— 过早放弃以前会**完全丢掉**
	   答案（调用方于是退回兜底或把节点报成未测速），所以这类调用方必须把等待延长到
	   动作自身期限之后。 */
	static String apiAction(String method, String data, long waitMs) {
		String raw = apiActionRaw(method, data, waitMs);
		if (raw == null)
		  return null;
		try {
			JSONObject r = new JSONObject(raw);
			if (r.optInt("code", -1) != 0)
			  return null;
			Object d = r.opt("data");
			return (d == null || d == JSONObject.NULL) ? "" : d.toString();
		} catch (Throwable e) {
			return null;
		}
	}

	/* **原始**的 {"id","method","data","code"} 应答；完全没有应答时为 null。
	   需要它才能区分"内核**拒绝**了这组参数"（code != 0，例如字段 JSON 类型不对时的
	   "invalid data type"）和"没有应答" —— 上面那个便利封装表达不了这个区别，
	   因为两者都表现为 null。
	   原生动作桥只保留**一个**在途回调：两次重叠的 invokeAction() 会让先到的结果被
	   投递给错误的等待者，于是那个等待者永远醒不来、最后超时返回 null。流量轮询
	   （每 1 秒）与某个 Activity 自己的轮询（每 1.5 秒）一直在重叠 —— 这正是"连接页
	   返回空、而后台统计却看得到连接"的原因。所以在这里串行化。 */
	static String apiActionRaw(String method, String data, long waitMs) {
		if (!Clash.INSTANCE.isLoaded())
		  return null;   /* 本进程没有内核（例如主进程）—— 这不是错误 */
		if (data == null)
		  return invokeAction(method, null, false, waitMs);
		/* `data` 该怎么携带**没有任何文档**，而内核在分发之前会先校验容器：装错了就回
		   code=-1 "invalid data type"。装错会**静默地**弄坏**每一个带参数**的动作 ——
		   延迟测试、切节点 —— 而不带参数的（getConnections / getProxies）照常工作，
		   这正是它极难被发现的原因。所以先试字符串形式（FlClash 就是这么发的：内核自己
		   反序列化字符串），失败再退回内联对象，并把成功的那种记住。 */
		if (dataAsString == 1)
		  return invokeAction(method, data, true, waitMs);
		if (dataAsString == 0)
		  return invokeAction(method, data, false, waitMs);
		/* 还没定：由**单线程**定下来。这个闩锁是所有探测共享的，多个并发探测抢它可能
		   锁住**错误的**那种形式，然后全体一起失败 —— 也就是"单个测速能用、测速全部
		   全废"那个现象。 */
		synchronized (DETECT_LOCK) {
			if (dataAsString == 1)
			  return invokeAction(method, data, true, waitMs);
			if (dataAsString == 0)
			  return invokeAction(method, data, false, waitMs);
			String raw = invokeAction(method, data, true, waitMs);
			/* 只有**确定的拒绝**才能证明字符串形式不对。"完全没有应答"（null）不是证据：
			   据此锁死成对象形式会让之后**每一次**调用都失败（包括后来的单节点测试），
			   直到进程重启。 */
			if (raw == null || !badDataType(raw)) {
				if (raw != null) {
					dataAsString = 1;
					TestLog.append("bridge: data 采用字符串形式（内核接受）");
				}
				return raw;
			}
			dataAsString = 0;
			TestLog.append("bridge: data 字符串形式被拒 " + truncate(raw) + " → 改用内联对象");
			return invokeAction(method, data, false, waitMs);
		}
	}

	/* 内核拒绝的是**参数容器**，而不是这个节点。 */
	private static boolean badDataType(String raw) {
		return raw != null && raw.contains("invalid data type");
	}

	/* 一次桥调用。`asString` 为真时把 `data` 作为 **JSON 字符串**携带（由内核自己
	   反序列化），而不是内联 JSON 对象。用 API_LOCK 串行化：原生动作桥只保留**一个**
	   在途回调，重叠的 invokeAction() 会让先到的结果投递给错误的等待者，后者于是永远
	   醒不来、超时返回 null。 */
	private static String invokeAction(String method, String data, boolean asString,
			long waitMs) {
		synchronized (API_LOCK) {
			final boolean[] done = { false };
			/* 留给下面的诊断日志用："完全没有应答"（超时）和"内核回了错误码"是两种
			   很不一样的失败，而只看返回值区分不开。 */
			final int[] code = { Integer.MIN_VALUE };
			final String[] raw = { null };
			try {
				JSONObject j = new JSONObject();
				j.put("id", "");
				j.put("method", method);
				if (data == null)
				  j.put("data", JSONObject.NULL);
				else if (asString)
				  j.put("data", data);
				else {
					try { j.put("data", new JSONObject(data)); }
					catch (Throwable e) { j.put("data", data); }
				}
				Clash.INSTANCE.invokeAction(j.toString(), new InvokeInterface() {
					@Override
					public void onResult(String result) {
						raw[0] = result;
						if (result != null) {
							try {
								code[0] = new JSONObject(result).optInt("code", -1);
							} catch (Throwable ignore) { }
						}
						synchronized (done) { done[0] = true; done.notifyAll(); }
					}
				});
				synchronized (done) {
					long end = System.currentTimeMillis() + Math.max(500, waitMs);
					while (!done[0] && System.currentTimeMillis() < end)
					  done.wait(Math.max(1, end - System.currentTimeMillis()));
					if (!done[0]) {
						TestLog.append("bridge: 动作 " + method + " 等待 " + waitMs
							+ "ms 未收到回调（内核无响应 / 该动作不返回）");
					} else if (code[0] != 0) {
						TestLog.append("bridge: 动作 " + method + " 返回 code=" + code[0]
							+ " raw=" + truncate(raw[0]));
					}
				}
			} catch (Throwable e) {
				TestLog.append("bridge: 动作 " + method + " 异常 " + e);
				return null;
			}
			return raw[0];
		}
	}

	/* 原始桥应答的短形式，给日志用。 */
	private static String truncate(String s) {
		if (s == null)
		  return "null";
		s = s.replace('\n', ' ');
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}

	/* 签名与 localApi() 一致，但走**进程内桥**。动作返回的 `data` 与 REST 的响应体
	   逐字节相同，所以调用方照旧解析即可。无法表达成动作的路径会退回 HTTP 监听 ——
	   这样在 9090 **确实**可用时行为完全不变。 */
	static ApiResult bridgeApi(String method, String host, int port, String path,
			String body, String secret) {
		return bridgeApi(method, host, port, path, body, secret, 6000);
	}

	/* 同上，但为那些会**落到 HTTP 监听**的路径显式指定超时 —— 尤其是 /delay，
	   它的回包要等内核自己的探测跑完才来。 */
	static ApiResult bridgeApi(String method, String host, int port, String path,
			String body, String secret, int timeoutMs) {
		ApiResult r = new ApiResult();
		r.code = -1;
		try {
			/* 每个映射成动作的路径，在进程内桥**什么都没返回**时都会**落到** HTTP
			   监听上 —— 在**主进程**里永远如此，因为那里没有加载内核。正因如此，这些
			   调用才能从 Activity 里发出（例如手动测速），而 :native 进程继续走桥。 */
			if ("GET".equals(method) && "/connections".equals(path)) {
				String d = apiAction("getConnections", null);
				if (d != null) { r.code = 200; r.body = d; return r; }
			} else if ("DELETE".equals(method) && "/connections".equals(path)) {
				String d = apiAction("closeAllConnections", null);
				if (d != null) { r.code = 204; r.body = d; return r; }
			} else if ("GET".equals(method) && "/proxies".equals(path)) {
				String d = apiAction("getProxies", null);
				if (d != null) { r.code = 200; r.body = d; return r; }
			} else if ("GET".equals(method) && path.startsWith("/proxies/")
					&& path.indexOf("/delay") > 0) {
				/* /delay 在内核里是异步的，也没有对应的桥动作：只能走 HTTP 监听。 */
				return localApi(method, host, port, path, body, secret, timeoutMs);
			} else if ("GET".equals(method) && path.startsWith("/proxies/")) {
				String group = path.substring("/proxies/".length());
				String d = apiAction("getProxies", null);
				if (d != null) {
					JSONObject all = new JSONObject(d).optJSONObject("proxies");
					JSONObject g = all == null ? null : all.optJSONObject(group);
					if (g != null) { r.code = 200; r.body = g.toString(); return r; }
				}
			} else if ("PUT".equals(method) && path.startsWith("/proxies/")) {
				String group = path.substring("/proxies/".length());
				String proxy = "";
				if (body != null) {
					try { proxy = new JSONObject(body).optString("name", ""); } catch (Throwable ignore) {}
				}
				String d = apiAction("changeProxy",
					"{\"group-name\":\"" + group.replace("\\", "\\\\").replace("\"", "\\\"")
					+ "\",\"proxy-name\":\"" + proxy.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
				if (d != null) { r.code = 204; r.body = d; return r; }
			}
		} catch (Throwable e) {
			r.code = -1;
		}
		return localApi(method, host, port, path, body, secret, timeoutMs);
	}

	/* 内核能否通过进程内桥够到（不需要 9090）。用它作为"可达性闸门"，
	   这样即使 HTTP 监听从没绑上，选择器 / 测速逻辑也能继续往下走。 */
	static boolean isCoreReachable() {
		return apiAction("getConnections", null) != null;
	}

	private static String readLineBytes(java.io.InputStream is) throws java.io.IOException {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		int b;
		while ((b = is.read()) != -1) {			if (b == '\r') {
				int c = is.read();
				if (c == '\n') break;
				bos.write('\r');
				if (c != -1) bos.write(c);
			} else if (b == '\n') {
				break;
			} else {
				bos.write(b);
			}
		}
		/* null = "流已结束且什么都没读到"：真正的 EOF，与 ClashApiServer.readLine
		   的约定一致。以前返回 "" 会让调用方的 null 判断变成死代码，并把
		   "对端没应答就关了连接"变成 split(" ")[1] 上的数组越界。 */
		if (b == -1 && bos.size() == 0)
		  return null;
		return new String(bos.toByteArray(), StandardCharsets.UTF_8);
	}

	private boolean probeVersion(String host) {
		ApiResult r = localApi("GET", host, MihomoConfig.API_PORT,
			"/version", null, prefs.getSecret());
		if (r.code == -1) {
			/* 按**真实原因**措辞，而不是一律甩锅给 VPN：连接被拒说明 API 还没绑上
			   （预热中），超时说明 socket 多半被抓走了（或 protect 没生效），
			   其它情况原样带出来。 */
			String d = r.error == null ? "" : r.error;
			if (d.contains("refused") || d.contains("ECONNREFUSED"))
			  lastControllerError = "控制接口尚未监听（核心预热中，clash-api 还没 bind）";
			else if (d.contains("timed out") || d.contains("SocketTimeout")
					|| d.contains("timeout") || d.contains("保护"))
			  lastControllerError = "连接控制接口超时（疑似 VPN 捕获回环 / protect 未生效）";
			else
			  lastControllerError = "transport error（" + d + "）";
			return false;
		}
		/* 2xx = 健康；401 = 监听起来了但鉴权不对，仍算**可达**。 */
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

	/* mihomo 的启动 / 绑定行走的是 **STDOUT**（启动时由 dup2 重定向抓进 tproxy.log），
	   **不**写 cache/mihomo.log（这个构建从没创建过它），也**不**经过
	   setEventListener（它对 API 相关的事一言不发）。所以 clash-api 绑定失败
	   （例如 "Failed to start API: listen tcp 127.0.0.1:9090: bind: ..."）是以**原始行**
	   的形式躺在 tproxy.log 里，既不是 "mihomo:" 事件，也不是
	   "corelog:"/"mihomolog:" 行。这里把尾部原样转储出来，**并且**把所有
	   api/controller/bind 行单独以 "rawlog-api:" 前缀再报一遍，让原因无法被忽略。 */
	private void dumpRawLog() {
		File f = new File(getCacheDir(), "tproxy.log");
		if (!f.exists()) return;
		try (java.io.BufferedReader r =
				new java.io.BufferedReader(new java.io.FileReader(f))) {
			java.util.List<String> all = new java.util.ArrayList<String>();
			String line;
			while ((line = r.readLine()) != null) all.add(line);
			int start = Math.max(0, all.size() - 60);
			for (int i = start; i < all.size(); i++)
			  appendLog("rawlog: " + all.get(i));
			/* 把**整个文件**再扫一遍，找出内核关于 API / controller / bind 的只言片语，
			   用专属前缀单独打印 —— 免得那一句决定性的行（"Failed to start API"、
			   "listen tcp ... bind: ..."、"RESTful API listening at ..."）
			   淹没在 60 行无关的原始日志里。 */
			java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
			for (String l : all) {
				String low = l.toLowerCase();
				if (low.contains("api") || low.contains("controller") || low.contains("bind")
						|| low.contains("listen") || low.contains("external") || low.contains("clash")
						|| low.contains("fail") || low.contains("error") || low.contains("panic")
						|| low.contains("secret") || low.contains("9090")) {
					String t = l.trim();
					if (seen.add(t))
					  appendLog("rawlog-api: " + t);
				}
			}
			if (seen.isEmpty())
			  appendLog("rawlog-api: 无 api/controller/bind 相关行（核心未打印原因，或日志已被截断）");
		} catch (Throwable ignore) { }
	}

	private void dumpControllerLog() {
		/* mihomo 把启动 / 绑定行写进 log.file 指定的文件（cache/mihomo.log），
		   而不是 tproxy.log —— 所以先读它，好让控制接口"静默绑定失败"真的现形。 */
		boolean found = dumpLog(new File(getCacheDir(), "mihomo.log"));
		if (!found)
		  dumpLog(new File(getCacheDir(), "tproxy.log"));
	}

	private boolean dumpLog(File f) {
		if (f == null || !f.exists()) return false;
		int shown = 0;
		try (java.io.BufferedReader r =
				new java.io.BufferedReader(new java.io.FileReader(f))) {
			String line;
			while ((line = r.readLine()) != null && shown < 40) {
				String l = line.toLowerCase();
				if (l.contains("controller") || l.contains("bind") || l.contains("listen")
						|| l.contains("external") || l.contains("fail") || l.contains("error")
						|| l.contains("panic") || l.contains("api")) {
					appendLog("corelog: " + line.trim());
					shown++;
				}
			}
		} catch (Throwable ignore) { }
		return shown > 0;
	}

	/* 一旦配置里设了 log.file，mihomo 就把自己的日志（含 clash-api 的启动 / 绑定行、
	   以及任何 panic）写进 <cacheDir>/mihomo.log。这里把能解释"控制接口为何起不来"的
	   行挑出来。 */
	private void dumpMihomoLog() {
		File f = new File(getCacheDir(), "mihomo.log");
		if (!f.exists()) {
			appendLog("mihomolog: 未生成（config 未设置 log.file 或核心未写日志）");
			return;
		}
		try (java.io.BufferedReader r =
				new java.io.BufferedReader(new java.io.FileReader(f))) {
			java.util.List<String> all = new java.util.ArrayList<String>();
			String line;
			int shown = 0;
			while ((line = r.readLine()) != null) {
				all.add(line);
				String l = line.toLowerCase();
				if (l.contains("controller") || l.contains("clash-api") || l.contains("bind")
						|| l.contains("listen") || l.contains("external") || l.contains("api")
						|| l.contains("fail") || l.contains("error") || l.contains("panic")
						|| l.contains("secret")) {
					appendLog("mihomolog: " + line.trim());
					shown++;
				}
			}
			/* 尾部始终原样输出：上面那个"筛选视图"可能是空的，即使内核确实打了
			   "Started API server" 之类的启动行 —— 而那一行恰恰是判断 9090 到底有没有
			   起来的**缺失线索**。 */
			int from = Math.max(0, all.size() - 30);
			appendLog("mihomolog-tail: 末尾 " + (all.size() - from) + " 行（共 " + all.size() + " 行）");
			for (int i = from; i < all.size(); i++)
			  appendLog("mihomolog-tail: " + all.get(i).trim());
			if (shown == 0 && all.isEmpty())
			  appendLog("mihomolog: 文件为空（核心未写日志）");
		} catch (Throwable e) {
			appendLog("mihomolog: 读取失败：" + e);
		}
	}

	/* FlClash 式的真实连通性检查，**不依赖控制接口**：把请求打进内核自己的本地
	   mixed-port（127.0.0.1:<代理端口>），让字节真的从选中节点出去再回来。语义与
	   Clash 自己的 /delay 一致 —— 从代理拿到**任何** HTTP 响应就说明节点管线是活的
	   （隧道已通）；5xx 只说明所选测试目标被节点出口拦了，并不代表代理死了。
	   所以：有响应 => 可达/可用，并带实测延迟；只有**连接级**失败（完全没有响应）
	   才 => 不可用。 */
	private void testProxyConnectivity(Preferences prefs) {
		final int port = prefs.getProxyPort();
		new Thread(() -> {
			for (int i = 0; i < 40; i++) {
				if (startupAborted || tunnelStopping)
				  return;
				int[] res = proxyTestOnce(port);
				if (res[0] > 0) { // 拿到 HTTP 响应 => 节点管线通
					int code = res[0], rtt = res[1];
					if (code >= 200 && code < 400) {
						proxyTestStatus = "代理实测可用（延迟 " + rtt + "ms）";
						appendLog("proxy test: 可用（" + rtt + "ms，经 127.0.0.1:" + port
							+ " 实测 " + lastTestUrl + "）");
					} else {
						/* 节点回了响应（如 502）即说明隧道已通，只是该测试目标被
						   节点出口拦截/不可达；这仍算“可达”，但如实标注状态码。 */
						proxyTestStatus = "代理可达（延迟 " + rtt + "ms，节点响应 " + code
							+ "，测试目标可能被节点侧拦截）";
						appendLog("proxy test: 节点可达但目标返回 " + code + "（延迟 " + rtt
							+ "ms，经 127.0.0.1:" + port + "，" + lastTestUrl
							+ "，隧道应已可用）");
					}
					updateNotification();
					return;
				}
				proxyTestStatus = "代理实测不可用：" + lastTestError;
				appendLog("proxy test: 经 127.0.0.1:" + port + " 无任何 HTTP 响应（" + lastTestError + "）");
				updateNotification();
				try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
			}
			proxyTestStatus = "代理实测多次失败：节点可能失效或 TUN 未真正捕获流量";
			appendLog("proxy test: 多次尝试仍不可用，代理可能未真正连通（节点失效 / TUN 未捕获流量 / 127.0.0.1:" + port + " 未监听）");
			updateNotification();
		}).start();
	}

	/* 逐个候选 URL 经本地 mixed-port 实测。返回首个拿到 2xx 的 {code, rtt}；若所有
	   URL 都只拿到 5xx，也返回最后一个 5xx（节点已应答，证明隧道通）；仅当连接级
	   失败（拒绝/超时，代理本身不可达）才返回 {-1,-1}。 */
	private int[] proxyTestOnce(int port) {
		int last5xx = -1, lastRtt = -1;
		for (String url : PROXY_TEST_URLS) {
			lastTestUrl = url;
			ApiResult r = proxyFetch(port, url);
			if (r.code == -1) {
				lastTestError = "transport（代理不可达）";
				return new int[] { -1, -1 };
			}
			if (r.code >= 200 && r.code < 400)
			  return new int[] { r.code, (int) r.rtt };
			last5xx = r.code; lastRtt = (int) r.rtt;
		}
		if (last5xx > 0)
		  return new int[] { last5xx, lastRtt };
		return new int[] { -1, -1 };
	}

	/* 向充当 HTTP 代理的本地 mixed-port 发一个 **absolute-form** 的 GET，走的是绕过
	   VPN 的 socket（否则 App 自己的 socket 会被隧道抓走）。返回代理的响应码与 rtt。 */
	static ApiResult proxyFetch(int port, String url) {
		ApiResult r = new ApiResult();
		long t0 = System.currentTimeMillis();
		java.net.Socket s = null;
		try {
			java.net.URL u = new java.net.URL(url);
			s = new java.net.Socket();
			protectLocalSocket(s);
			s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 8000);
			s.setSoTimeout(8000);
			java.io.OutputStream os = s.getOutputStream();
			StringBuilder req = new StringBuilder();
			req.append("GET ").append(url).append(" HTTP/1.1\r\n")
				.append("Host: ").append(u.getHost()).append("\r\n")
				.append("Connection: close\r\n\r\n");
			os.write(req.toString().getBytes(StandardCharsets.UTF_8));
			os.flush();
			java.io.InputStream is = s.getInputStream();
			String status = readLineBytes(is);
			if (status != null) {
				try { r.code = Integer.parseInt(status.split(" ")[1]); } catch (Throwable ignore) {}
			}
			byte[] buf = new byte[2048];
			while (is.read(buf) != -1) ; /* 把 body 读完丢掉 */
		} catch (Throwable e) {
			r.code = -1;
		} finally {
			if (s != null) try { s.close(); } catch (Throwable ignore) {}
		}
		r.rtt = System.currentTimeMillis() - t0;
		return r;
	}

	/* 配置在两份订阅带了同一个节点名时可能补上的 " (N)" 去重后缀。只编译一次：
	   resolveMember 要拿它跟组里**每个**成员比对。 */
	private static final Pattern DEDUP_SUFFIX = Pattern.compile(" \\(\\d+\\)$");

	private static String dedupBase(String name) {
		return name == null ? "" : DEDUP_SUFFIX.matcher(name).replaceAll("");
	}

	/* 把 `wanted` 解析成 `group` 里真实存在的成员名，并容忍配置可能补上的
	   " (N)" 去重后缀（两份订阅带了同一个节点名时会出现）。组读不到时返回 null。 */
	private String resolveMember(String group, String wanted) throws IOException {
		ApiResult r = bridgeApi("GET", apiHost, MihomoConfig.API_PORT,
			"/proxies/" + encodePath(group), null, prefs.getSecret());
		if (r.code == -1 || r.body == null) return null;
		String base = dedupBase(wanted);
		String fallback = null;
		try {
			JSONObject o = new JSONObject(r.body);
			JSONArray all = o.optJSONArray("all");
			if (all != null) {
				for (int i = 0; i < all.length(); i++) {
					String m = all.getString(i);
					if (m.equals(wanted))
					  return m;
					if (fallback == null && dedupBase(m).equals(base))
					  fallback = m;
				}
			}
		} catch (JSONException e) {
		}
		return fallback;
	}

	/* PUT /proxies/{group} {"name": proxy}，用来移动选择器。 */
	private int putSelector(String group, String proxy) throws IOException {
		ApiResult r = bridgeApi("PUT", apiHost, MihomoConfig.API_PORT,
			"/proxies/" + encodePath(group),
			"{\"name\":\"" + escapeJson(proxy) + "\"}", prefs.getSecret());
		return (r.code == -1) ? -1 : r.code;
	}

	private static String encodePath(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (Exception e) {
			return s;
		}
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String readBody(HttpURLConnection c) {
		BufferedReader r;
		try {
			r = new BufferedReader(new InputStreamReader(
				c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(),
				StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
		try {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null)
			  sb.append(line);
			return sb.toString();
		} catch (IOException e) {
			return null;
		} finally {
			try {
				r.close();
			} catch (IOException e) {
			}
		}
	}

	public void stopService() {
		/* 通知所有长时间运行的辅助线程收工（选择器重试、控制器轮询、连通性探测、
		   延迟启动 clash-api）：否则它们会在隧道消失之后还继续干活、还占着这个 Service，
		   最长可达好几分钟。 */
		tunnelStopping = true;
		if (tunFd == null) {
			/* 隧道已经断了，但**服务本身**还是启动状态（未连接时又收到 DISCONNECT，
			   或启动失败后被 revoke）：这里要把它释放掉，否则它会一直以"sInstance
			   还在、却无事可做"的活服务形式挂着。 */
			stopStats();
			stopEmbeddedApi();
			new Preferences(this).setEnable(false);
			stopForeground(true);
			sInstance = null;
			stopSelf();
			return;
		}

		/* 赶在隧道消失之前把流量计数落盘。 */
		stopStats();
		stopEmbeddedApi();

		Preferences p = new Preferences(this);
		p.setEnable(false);
		/* 记录下来的节点属于**刚刚被拆掉**的那条隧道：留着它会让界面显示一个已经
		   不再使用的节点。 */
		p.setActiveNode("");
		QSTileService.requestUpdate(this);

		stopForeground(true);

		/* 先拆隧道，再释放 fd。 */
		try {
			Clash.INSTANCE.stopTun();
		} catch (Throwable e) {
		}

		try {
			tunFd.close();
		} catch (IOException e) {
		}
		tunFd = null;

		stopSelf();
	}

	/* 把在跑的隧道拆掉并立刻重来，作为 onStartCommand 的**单个**动作。用于"仅在
	   establish() 时生效"的设置（按应用范围、全局模式）在连接状态下被改动时。
	   分两条 intent 发 DISCONNECT + CONNECT 会有竞态：CONNECT 可能已经进
	   onStartCommand，而 DISCONNECT 的 stopSelf() 还在排队，于是刚建好的隧道被它一起
	   拆掉 —— 这正是"改了按应用分流，隧道就再也起不来了"的原因。这里**不调用**
	   stopSelf，只停掉旧 fd，然后重建。 */
	private void rebuildTunnel() {
		if (tunFd != null) {
			stopStats();
			try { Clash.INSTANCE.stopTun(); } catch (Throwable ignore) { }
			try { tunFd.close(); } catch (IOException ignore) { }
			tunFd = null;
		}
		startService();
	}

	private void createNotification() {
		Notification notify = buildNotification();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFY_ID, notify);
		} else {
			startForeground(NOTIFY_ID, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
		}
	}

	/* 通知栏每秒都会重建，所以调用方可以把自己已经算好的"实时"行传进来，
	   省掉一次重复的 statsLine 构造。 */
	private Notification buildNotification() {
		return buildNotification(null);
	}

	private Notification buildNotification(String precomputedLine) {
		/* 实时/会话/总展示隧道总流量（取自 getTotalTraffic，独立于 9090）。代理
		   专属统计依赖 /connections，控制接口不可用时为 0，会显得“流量不动”。 */
		String line = precomputedLine != null ? precomputedLine
			: statsLine(R.string.stats_realtime, formatRate(txRate), formatRate(rxRate));
		String big = line + "\n" +
			statsLine(R.string.stats_session,
				formatBytes(sessionTx), formatBytes(sessionRx)) + "\n" +
			statsLine(R.string.stats_total,
				formatBytes(totalTx), formatBytes(totalRx));

		/* 节点名放在**标题**里 —— 名字长了最多被省略号截断 —— 这样下面的实时速率
		   永远不会被挤出通知之外。第一条通知时 statsPrefs 还是 null（它由
		   startStats 设置），所以要有兜底。 */
		Preferences p = (statsPrefs != null) ? statsPrefs : new Preferences(this);
		String node = p.getCurrentNode();
		if (node.isEmpty())
		  node = p.hasSubscription() ? getString(R.string.node_default)
									 : getString(R.string.node_none);
		String bigText = getString(R.string.notify_node, node)
			+ (proxyTestStatus.isEmpty() ? "" : ("\n" + proxyTestStatus)) + "\n" + big;

		/* 两个 PendingIntent 的内容永不改变，而 getActivity/getService 每次都要走
		   Binder；通知每秒重建，缓存下来就省掉了每秒两次跨进程调用。
		   （并发时最多重复创建一次，内容相同，无副作用。） */
		PendingIntent pi = notifyContentPi;
		PendingIntent psi = notifyStopPi;
		if (pi == null || psi == null) {
			Intent i = new Intent(this, MainActivity.class);
			i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
			Intent stop = new Intent(this, TProxyService.class).setAction(ACTION_DISCONNECT);
			psi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
			notifyContentPi = pi;
			notifyStopPi = psi;
		}

		return new NotificationCompat.Builder(this, NOTIFY_CHANNEL)
			.setContentTitle(node)
			.setContentText(line)
			.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
			.setSmallIcon(android.R.drawable.sym_def_app_icon)
			.setContentIntent(pi)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel,
				getString(R.string.control_disable), psi)
			.build();
	}

	private String statsLine(int labelId, String up, String down) {
		return getString(R.string.stats_line, getString(labelId), up, down);
	}

	/* 自己托管一个回环的 HTTP 控制接口（也就是 "clash-api"）。libmihomo 是 JNI 优先的
	   库，自己从不绑 external-controller，所以否则 API 端口上根本没人监听：浏览器连
	   127.0.0.1:<API_PORT> 连不上，App 走 REST 的路径（延迟测试）也会失败。
	   ClashApiServer 负责把 REST 翻译成进程内桥调用。 */
	private void startEmbeddedApi() {
		try {
			if (clashApiServer != null)
			  return;
			clashApiServer = new ClashApiServer(MihomoConfig.API_PORT, prefs);
			if (clashApiServer.start())
			  appendLog("clash-api: 内嵌控制接口已监听 127.0.0.1:" + MihomoConfig.API_PORT
				+ "（浏览器打开 http://127.0.0.1:" + MihomoConfig.API_PORT + "/ 可见）");
			else {
				/* start() 可能在**绑定前**也可能在**绑定后**失败：两种情况下 stop()
				   都能关掉监听并停掉工作线程池；而单纯丢掉引用（旧代码的做法）会把已经
				   创建出来的那一半泄漏掉。 */
				clashApiServer.stop();
				clashApiServer = null;
				appendLog("clash-api: 无法监听 127.0.0.1:" + MihomoConfig.API_PORT
					+ "（原因见上一行）→ 沿用内核自身的监听");
			}
		} catch (Throwable e) {
			clashApiServer = null;
			appendLog("clash-api: 内嵌服务启动失败：" + e);
		}
	}

	private void stopEmbeddedApi() {
		if (clashApiServer != null) {
			clashApiServer.stop();
			clashApiServer = null;
		}
	}

	/* 每秒轮询一次 mihomo 的流量计数。 */
	private void startStats(Preferences prefs) {
		statsPrefs = prefs;
		baseTx = prefs.getTotalTx();
		baseRx = prefs.getTotalRx();
		sessionTx = sessionRx = 0;
		sessionBaseTx = sessionBaseRx = 0;
		lastTx = lastRx = 0;
		txRate = rxRate = 0;
		trafficPrimed = false;
		totalTx = baseTx;
		totalRx = baseRx;
		lastTime = SystemClock.elapsedRealtime();
		lastNotifyText = null;
		trafficSamples = 0;

		proxyBaseTx = prefs.getProxyTotalTx();
		proxyBaseRx = prefs.getProxyTotalRx();
		proxySessionTx = proxySessionRx = 0;
		lastProxyTx = lastProxyRx = 0;
		proxyRateTx = proxyRateRx = 0;
		proxyPrimed = false;
		lastProxyTime = SystemClock.elapsedRealtime();
		connSeen.clear();
		loadRecentRequests();

		prefs.setAllStats(totalTx, totalRx, 0, 0, 0, 0,
			proxyBaseTx, proxyBaseRx, 0, 0, 0, 0);
		prefs.setAppStats(snapshotApps(this, prefs), prefs.getAppTotal());

		/* 采样器跑在专属后台线程上：它会对内核做同步 httpGet()，而主线程是禁止的。 */
		if (statsThread != null) {
			statsThread.quitSafely();
			statsThread = null;
		}
		statsThread = new HandlerThread("traffic-stats");
		statsThread.start();
		statsHandler = new Handler(statsThread.getLooper());
		statsTask = new Runnable() {
			@Override
			public void run() {
				sampleStats(true);
				if (statsHandler != null)
				  statsHandler.postDelayed(this, STATS_INTERVAL);
			}
		};
		statsHandler.postDelayed(statsTask, STATS_INTERVAL);
	}

	private void sampleStats(boolean updateNotify) {
		long now = SystemClock.elapsedRealtime();
		long dt = now - lastTime;
		lastTime = now;

		/* 这两个值必须取自**内核启动至今的累计量**。getTraffic() 只带最近一秒的增量，
		   把它当累计量用，会让速率（增量的增量）和会话统计都失去意义。 */
		long tx = -1, rx = -1;
		String raw = null;
		Throwable error = null;
		try {
			raw = Clash.INSTANCE.getTotalTraffic();
			tx = jsonBytes(raw, "uploadTotal", "up", "Upload");
			rx = jsonBytes(raw, "downloadTotal", "down", "Download");
		} catch (Throwable e) {
			error = e;
		}
		if (tx < 0 || rx < 0) {
			/* 退回到"增量计数器"，以兼容没有累计量的桥。 */
			try {
				String s = Clash.INSTANCE.getTraffic();
				if (s != null) {
					if (tx < 0)
					  tx = jsonBytes(s, "up", "Upload", "uploadTotal");
					if (rx < 0)
					  rx = jsonBytes(s, "down", "Download", "downloadTotal");
					if (raw == null)
					  raw = s;
				}
			} catch (Throwable e) {
				if (error == null)
				  error = e;
			}
		}
		logTraffic(raw, tx, rx, error);

		if (tx >= 0 && rx >= 0) {
			if (!trafficPrimed) {
				/* 第一个有效样本：先定基准，免得第一个速率变成"在一拍里把内核全部
				   累计量都算上"（那会显示一个假的 9G/s 尖峰），会话总量也不会等于内核
				   的全部累计量。 */
				lastTx = tx; lastRx = rx;
				sessionBaseTx = tx; sessionBaseRx = rx;
				trafficPrimed = true;
				sessionTx = 0; sessionRx = 0;
			} else if (rx < lastRx || tx < lastTx) {
				/* 内核计数被重置（重载配置 / 内核重启）：重新定基准，
				   而不是算出一个负增量的尖峰。 */
				lastTx = tx; lastRx = rx;
				sessionBaseTx = tx; sessionBaseRx = rx;
				sessionTx = 0; sessionRx = 0;
			} else {
				if (dt > 0) {
					txRate = Math.max((tx - lastTx) * 1000 / dt, 0);
					rxRate = Math.max((rx - lastRx) * 1000 / dt, 0);
				}
				lastTx = tx; lastRx = rx;
				sessionTx = Math.max(tx - sessionBaseTx, 0);
				sessionRx = Math.max(rx - sessionBaseRx, 0);
			}
			totalTx = baseTx + sessionTx;
			totalRx = baseRx + sessionRx;
		}

		if (updateNotify)
		  updateNotification();

		accumulateProxy();

		saveAllStats();

		/* 每约 30 秒重新读一次"内核真正在用哪个节点"：自动模式下内核自己的健康检查就会
		   移动选择器，否则首页 / 通知栏会永远停在最初那个节点上。 */
		if (++nodeSyncTick >= 30) {
			nodeSyncTick = 0;
			syncActiveNode(MihomoConfig.GROUP);
		}
	}

	/* 流量计数卡住时，从界面上是看不出来的（它就一直显示 0），所以把内核实际返回的内容
	   记下来。只记最初几个样本，之后每分钟一条，否则日志会被刷爆。 */
	private void logTraffic(String raw, long tx, long rx, Throwable error) {
		trafficSamples++;
		if (error != null) {
			if (trafficSamples <= 5)
			  appendLog("traffic: sample failed: " + error);
			return;
		}
		if (trafficSamples <= 3 || (trafficSamples % 60) == 0)
		  appendLog("traffic: raw=" + raw + " tx=" + tx + " rx=" + rx);
	}

	/* 键 -> 已编译正则。jsonBytes 跑在 1 秒一次的统计心跳上（每拍 2~4 次调用），而
	   Pattern.compile 是正则匹配里**最贵**的一步，所以按键缓存编译结果，
	   而不是每次采样都重新编译。 */
	private static final Map<String, Pattern> JSON_PATTERNS =
		new java.util.concurrent.ConcurrentHashMap<String, Pattern>();

	private static Pattern jsonPattern(String key) {
		Pattern p = JSON_PATTERNS.get(key);
		if (p != null)
		  return p;
		p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
		JSON_PATTERNS.put(key, p);
		return p;
	}

	/* mihomo 把累计量报成 uploadTotal/downloadTotal，把每秒增量报成 up/down；
	   这里把见过的各种写法都接受。 */
	private static long jsonBytes(String json, String... keys) {
		if (json == null)
		  return -1;
		for (String key : keys) {
			try {
				Matcher m = jsonPattern(key).matcher(json);
				if (m.find())
				  return Long.parseLong(m.group(1));
			} catch (Exception e) {
			}
		}
		return -1;
	}

	/* 拉取内核的连接列表，把**确实经过节点**的流量累加起来。只累加增量，
	   所以一条长连接会在传输过程中持续贡献。 */
	private void accumulateProxy() {
		/* 通过进程内桥读连接快照（apiAction "getConnections" ==
		   statistic.DefaultManager.Snapshot()）—— 它不需要 HTTP 监听，所以即使
		   clash-api（9090）从没绑上，"代理专属"的流量与连接数也依然准确。快照 JSON 与
		   REST /connections 的响应体形状相同，所以下面所有解析代码都不用改。 */
		String body = apiAction("getConnections", null);
		if (body == null) {
			/* 内核还在预热，或桥出错了。静默计数、只提示一次；
			   总流量（getTotalTraffic）不受影响。 */
			connFailStreak++;
			if (connFailStreak == 5)
			  appendLog("流量统计：无法读取连接快照（in-process 桥 getConnections 不可用，"
				+ lastControllerError + "），仅“代理专属流量/连接数”归零；总流量取自 getTotalTraffic，不受影响");
			return;
		}
		if (connFailStreak >= 5)
		  appendLog("流量统计：/connections 已恢复");
		connFailStreak = 0;
		/* 把这份快照发布出去，好让「连接」页直接展示，而不必自己再发一次（会互相抢的）
		   桥调用。 */
		connSnapshot = body;
		try {
			JSONObject root = new JSONObject(body);
			JSONArray arr = root.optJSONArray("connections");
			if (arr == null)
			  return;

			/* 把实时列表交给主进程的「连接」页。它读不到桥、也读不到 sInstance
			   （不同进程），所以这是唯一通道；只有列表变化时才会写入。 */
			publishConnSnapshot(arr);

			Set<String> alive = new HashSet<String>();
			int proxyConnCount = 0;
			for (int i = 0; i < arr.length(); i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				String id = c.optString("id");
				if (id.isEmpty())
				  continue;
				alive.add(id);
				recordConnInfo(id, c);
				if (isDirectConnection(c))
				  continue;
				proxyConnCount++;

				long up = c.optLong("upload");
				long down = c.optLong("download");
				long[] prev = connSeen.get(id);
				if (prev == null) {
					/* 第一次看到它：在我们注意到之前，它就已经搬了这么多。 */
					proxySessionTx += up;
					proxySessionRx += down;
				} else {
					if (up > prev[0])
					  proxySessionTx += up - prev[0];
					if (down > prev[1])
					  proxySessionRx += down - prev[1];
				}
				connSeen.put(id, new long[] { up, down });
				}
				/* 这一轮里消失的连接就是已经关闭了：把它记成一条"最近请求"
				   （去了哪、走了哪条规则/链路、搬了多少、持续了多久），
				   这样历史记录能留存下来，而不只活在实时列表里。 */
				if (!connInfo.isEmpty()) {
				long endMs = SystemClock.elapsedRealtime();
				Iterator<String> it = connInfo.keySet().iterator();
				boolean changed = false;
				while (it.hasNext()) {
					String cid = it.next();
					if (!alive.contains(cid)) {
						ConnInfo info = connInfo.get(cid);
						RecentRequest rr = new RecentRequest();
						rr.startMs = info.startMs;
						rr.endMs = endMs;
						rr.target = info.target;
						rr.rule = info.rule;
						rr.chain = info.chain;
						rr.process = info.process;
						rr.up = info.up;
						rr.down = info.down;
						synchronized (recentRequests) {
							recentRequests.add(0, rr);
						}
						it.remove();
						changed = true;
					}
				}
				if (changed) {
					synchronized (recentRequests) {
						while (recentRequests.size() > MAX_RECENT_REQUESTS)
						  recentRequests.remove(recentRequests.size() - 1);
					}
					flushRecentRequests(endMs);
				}
				}
				/* 清掉已结束的连接，免得这个 map 无限膨胀。 */
				connSeen.keySet().retainAll(alive);
			/* 流量明明在跑、代理连接数却是 0，通常说明筛选条件不对（字段名变了，
			   或者所有连接都走了 DIRECT）—— 要让它可见，而不是一个无声的空白计数。 */
			if (trafficSamples <= 3 || (trafficSamples % 60) == 0)
			  appendLog("流量统计：活跃连接=" + arr.length()
				+ " 代理连接=" + proxyConnCount
				+ " session tx/rx=" + proxySessionTx + "/" + proxySessionRx);

			long now = SystemClock.elapsedRealtime();
			long dt = now - lastProxyTime;
			lastProxyTime = now;
			if (!proxyPrimed) {
				/* 第一个有效样本用来定基准；不会冒出假的速率尖峰。 */
				proxyPrimed = true;
				lastProxyTx = proxySessionTx;
				lastProxyRx = proxySessionRx;
			} else if (dt > 0) {
				proxyRateTx = Math.max((proxySessionTx - lastProxyTx) * 1000 / dt, 0);
				proxyRateRx = Math.max((proxySessionRx - lastProxyRx) * 1000 / dt, 0);
				lastProxyTx = proxySessionTx;
				lastProxyRx = proxySessionRx;
			}
		} catch (Exception e) {
			/* 这里解析失败会**不声不响地**把"代理专属流量/连接数"冻在 0 —— 这正是当年
			   "统计永远是 0"那个 bug 的样子。所以每连续失败一串就提示一次。 */
			proxyParseFails++;
			if (proxyParseFails == 5)
			  appendLog("流量统计：解析连接快照失败 " + e
				+ "（字段可能变了；代理流量/连接数将保持 0，总流量不受影响）");
		}
	}

	/* 第一次见到某条连接时记住它的元信息，之后持续更新它的最新计数；
	   "最近请求"历史就是靠这个维护的。 */
	private void recordConnInfo(String id, JSONObject c) {
		ConnInfo info = connInfo.get(id);
		long up = c.optLong("upload");
		long down = c.optLong("download");
		if (info == null) {
			info = new ConnInfo();
			info.startMs = SystemClock.elapsedRealtime();
			info.target = connTarget(c);
			info.rule = c.optString("rule", "");
			info.chain = connChain(c);
			info.process = connProcess(c);
			info.up = up;
			info.down = down;
			connInfo.put(id, info);
		} else {
			info.up = up;
			info.down = down;
		}
	}
	private static String connTarget(JSONObject c) {
		JSONObject meta = c.optJSONObject("metadata");
		if (meta == null)
		  return "";
		String host = meta.optString("host", "");
		if (host.isEmpty())
		  host = meta.optString("destinationIP", "");
		String port = meta.optString("destinationPort", "");
		if (host.isEmpty())
		  return port;
		return port.isEmpty() ? host : host + ":" + port;
	}
	private static String connChain(JSONObject c) {
		JSONArray chains = c.optJSONArray("chains");
		if (chains == null || chains.length() == 0)
		  return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < chains.length(); i++) {
			if (i > 0)
			  sb.append(" → ");
			sb.append(chains.optString(i));
		}
		return sb.toString();
	}
	private static String connProcess(JSONObject c) {
		JSONObject meta = c.optJSONObject("metadata");
		return meta != null ? meta.optString("process", "") : "";
	}
	/* 给活动连接生成一份**紧凑且有上限**的快照，发布到 SharedPreferences 供主进程的
	   「连接」页读取。只有内容变化时才重写，所以列表空闲时没有任何开销。 */
	private void publishConnSnapshot(JSONArray arr) {
		try {
			JSONArray out = new JSONArray();
			int n = Math.min(arr.length(), MAX_CONN_SNAPSHOT);
			for (int i = 0; i < n; i++) {
				JSONObject c = arr.optJSONObject(i);
				if (c == null)
				  continue;
				JSONObject o = new JSONObject();
				o.put("t", connTarget(c));
				o.put("r", connRoute(c));
				o.put("p", connProcess(c));
				o.put("u", c.optLong("upload"));
				o.put("d", c.optLong("download"));
				o.put("s", c.optString("start", ""));
				out.put(o);
			}
			String json = out.toString();
			if (json.equals(lastConnSnapshotJson))
			  return;
			lastConnSnapshotJson = json;
			if (statsPrefs != null)
			  statsPrefs.setConnSnapshot(json);
		} catch (Throwable ignore) {
		}
	}
	/* "<规则>(<参数>) · <链路>"：一行说明这行的"为什么 + 去了哪"。 */
	private static String connRoute(JSONObject c) {
		StringBuilder sb = new StringBuilder();
		String rule = c.optString("rule", "");
		if (!rule.isEmpty()) {
			String payload = c.optString("rulePayload", "");
			sb.append(rule);
			if (!payload.isEmpty())
			  sb.append('(').append(payload).append(')');
		}
		String chain = connChain(c);
		if (!chain.isEmpty()) {
			if (sb.length() > 0)
			  sb.append(" · ");
			sb.append(chain);
		}
		return sb.toString();
	}

	/* 把"最近请求"历史序列化进 Preferences，并做节流，避免每轮询一次就写一次磁盘。 */
	private void flushRecentRequests(long now) {
		Preferences sp = statsPrefs;
		if (sp == null)
		  return;
		/* **在锁内**取快照、**在锁外**序列化：这个列表也会被 UI 线程碰到
		   （clearRecentRequests），而对一个普通 ArrayList 共享迭代器迟早会抛
		   ConcurrentModificationException；反过来，把锁一直held到磁盘写完，
		   又会把 UI 的"清空"操作卡住整个写入过程。 */
		List<RecentRequest> snapshot;
		synchronized (recentRequests) {
			if (now - lastRecentFlush < 2000 && recentRequests.size() < 20)
			  return;
			lastRecentFlush = now;
			snapshot = new ArrayList<RecentRequest>(recentRequests);
		}
		JSONArray arr = new JSONArray();
		for (RecentRequest rr : snapshot) {
			JSONObject o = new JSONObject();
			try {
				o.put("t", rr.target);
				o.put("r", rr.rule);
				o.put("c", rr.chain);
				o.put("p", rr.process);
				o.put("u", rr.up);
				o.put("d", rr.down);
				o.put("s", rr.startMs);
				o.put("e", rr.endMs);
				arr.put(o);
			} catch (Exception e) {
			}
		}
		/* 用上面取到的那个非空快照：本次落盘序列化期间，stopStats() 可能已经把字段
		   清掉了。 */
		sp.setRecentRequests(arr.toString());
	}
	private void loadRecentRequests() {
		/* 由 startStats（主线程）调用，而上一轮可能还有一次落盘在途：
		   所以与其它所有访问用同一把锁。 */
		synchronized (recentRequests) {
			recentRequests.clear();
		}
		connInfo.clear();
		if (statsPrefs == null)
		  return;
		String raw = statsPrefs.getRecentRequests();
		if (raw == null || raw.isEmpty())
		  return;
		try {
			JSONArray arr = new JSONArray(raw);
			for (int i = 0; i < arr.length(); i++) {
				JSONObject o = arr.optJSONObject(i);
				if (o == null)
				  continue;
				RecentRequest rr = new RecentRequest();
				rr.target = o.optString("t", "");
				rr.rule = o.optString("r", "");
				rr.chain = o.optString("c", "");
				rr.process = o.optString("p", "");
				rr.up = o.optLong("u", 0);
				rr.down = o.optLong("d", 0);
				rr.startMs = o.optLong("s", 0);
				rr.endMs = o.optLong("e", 0);
				recentRequests.add(rr);
			}
		} catch (Exception e) {
		}
	}

	/* 链路为空、或者其中有一跳是 DIRECT，就说明这个请求从没经过代理节点。mihomo 在不同
	   构建里把代理路径暴露成 "chains"（数组）或 "chain"（单个字符串）两种形式，所以
	   两种写法都接受 —— 否则字段名对不上会让**每条**连接都显得是直连，
	   代理计数就会一直是 0。 */
	private static boolean isDirectConnection(JSONObject c) {
		JSONArray chains = c.optJSONArray("chains");
		if (chains == null) {
			String single = c.optString("chain", null);
			if (single != null && !single.isEmpty()) {
				chains = new JSONArray();
				chains.put(single);
			}
		}
		if (chains == null || chains.length() == 0)
		  return true;
		for (int i = 0; i < chains.length(); i++) {
			if ("DIRECT".equalsIgnoreCase(chains.optString(i)))
			  return true;
		}
		return false;
	}

	/* 只有显示文本真的变了才重新贴通知。 */
	private void updateNotification() {
		String line = statsLine(R.string.stats_realtime,
			formatRate(txRate), formatRate(rxRate));
		if (line.equals(lastNotifyText))
		  return;
		lastNotifyText = line;
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null)
		  nm.notify(NOTIFY_ID, buildNotification(line));
	}

	/* 两套计数**一次**写进 preferences（它们是并排显示的，而这段每秒都跑 ——
	   分两次 commit 就是每拍写两遍完整文件）。 */
	private void saveAllStats() {
		Preferences sp = statsPrefs;
		if (sp != null)
		  sp.setAllStats(totalTx, totalRx, sessionTx, sessionRx, txRate, rxRate,
			proxyBaseTx + proxySessionTx, proxyBaseRx + proxySessionRx,
			proxySessionTx, proxySessionRx, proxyRateTx, proxyRateRx);
	}

	/* 在**采样线程**上把内存计数器归零并重新定基准（只由 resetTraffic() post 过来，
	   所以与 sampleStats() 天然互斥，不会读到写了一半的状态）。

	   关键在"重新定基准"：内核报的是**开机以来的累计值**，我们算的是相对基准的增量。
	   所以归零不是把内核计数抹掉（也抹不掉），而是把基准挪到"现在"——之后只有新增的
	   流量才算进会话 / 总。 */
	private void zeroTraffic() {
		/* 隧道总量：把会话基准挪到最近一次采样到的内核累计值。还没定过基准时保持
		   trafficPrimed=false，让下一次 sampleStats() 自己把基准定在当时的累计值。 */
		if (trafficPrimed) {
			sessionBaseTx = lastTx;
			sessionBaseRx = lastRx;
		}
		baseTx = 0;
		baseRx = 0;
		sessionTx = 0;
		sessionRx = 0;
		totalTx = 0;
		totalRx = 0;
		txRate = 0;
		rxRate = 0;
		/* 代理专属：同理归零。connSeen 保留 —— 它记着每条连接上一次看到的字节数，
		   所以之后算出来的都是"从现在起"的增量，不会把旧流量重新算进来。 */
		proxyBaseTx = 0;
		proxyBaseRx = 0;
		proxySessionTx = 0;
		proxySessionRx = 0;
		lastProxyTx = 0;
		lastProxyRx = 0;
		proxyRateTx = 0;
		proxyRateRx = 0;
		/* 立刻写盘，别等下一拍（否则这中间 UI 可能又读到旧值）。 */
		saveAllStats();
	}

	private void stopStats() {
		if (statsHandler != null) {
			statsHandler.removeCallbacks(statsTask);
			statsHandler = null;
			statsTask = null;
		}
		if (statsThread != null) {
			statsThread.quitSafely();
			statsThread = null;
		}
		if (statsPrefs != null) {
			saveAllStats();
			flushRecentRequests(SystemClock.elapsedRealtime());
			accumulateApps(this, statsPrefs);
			statsPrefs = null;
		}
	}

	/* 按应用的字节计数**当前值**（设备级、自开机起累计）。 */
	private static String snapshotApps(Context context, Preferences prefs) {
		Map<String, long[]> map = new HashMap<String, long[]>();
		PackageManager pm = context.getPackageManager();
		for (String pkg : prefs.getRoutedApps(context)) {
			int uid = uidOf(pm, pkg);
			if (uid < 0)
			  continue;
			long tx = android.net.TrafficStats.getUidTxBytes(uid);
			long rx = android.net.TrafficStats.getUidRxBytes(uid);
			map.put(pkg, new long[] { Math.max(tx, 0), Math.max(rx, 0) });
		}
		return Preferences.formatAppStats(map);
	}

	/* 把本次会话的用量折算进"按应用累计"里。 */
	private static void accumulateApps(Context context, Preferences prefs) {
		Map<String, long[]> base = Preferences.parseAppStats(prefs.getAppBase());
		Map<String, long[]> now = Preferences.parseAppStats(snapshotApps(context, prefs));
		Map<String, long[]> total = Preferences.parseAppStats(prefs.getAppTotal());

		for (Map.Entry<String, long[]> e : now.entrySet()) {
			long[] b = base.get(e.getKey());
			long[] v = e.getValue();
			long tx = b != null ? Math.max(v[0] - b[0], 0) : 0;
			long rx = b != null ? Math.max(v[1] - b[1], 0) : 0;
			long[] t = total.get(e.getKey());
			if (t == null)
			  total.put(e.getKey(), new long[] { tx, rx });
			else {
				t[0] += tx;
				t[1] += rx;
			}
		}
		prefs.setAppStats("", Preferences.formatAppStats(total));
	}

	private static int uidOf(PackageManager pm, String pkg) {
		try {
			return pm.getApplicationInfo(pkg, 0).uid;
		} catch (NameNotFoundException e) {
			return -1;
		}
	}

	public static String formatRate(long bytesPerSecond) {
		return formatBytes(bytesPerSecond) + "/s";
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024)
		  return bytes + " B";
		double value = bytes;
		/* unit 从 -1 开始，因为**第一次**除法就已经把字节变成 KB，结果必须落在
		   BYTE_UNITS[0] 上。从 0 开始（并且先自增再使用）会把**每个**值都顶高一档：
		   100 KB 显示成 "100.0 MB"、3.7 MB 显示成 "3.7 GB"，依此类推。 */
		int unit = -1;
		while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
			value /= 1024;
			unit++;
		}
		if (unit < 0)
		  unit = 0;
		/* 手写定点格式化，不用 String.format：后者每次都要新建 Formatter、解析格式串，
		   而这段每秒被通知栏和首页叫十几次（值本身已经是 double，见上）。 */
		int decimals = value < 10 ? 2 : 1;
		long scale = decimals == 2 ? 100L : 10L;
		long rounded = Math.round(value * scale);
		StringBuilder sb = new StringBuilder(12);
		sb.append(rounded / scale).append('.');
		if (decimals == 2)
		  sb.append((char) ('0' + (rounded % scale) / 10));   /* 十分位，可能为 0 */
		sb.append((char) ('0' + rounded % 10)).append(' ').append(BYTE_UNITS[unit]);
		return sb.toString();
	}

	/* 仅管格式：值是 double，格式化按"小于 10 保留两位小数，否则一位"来做。
	   BYTE_UNITS 是静态常量数组，不产生分配。 */
	private static final String[] BYTE_UNITS = { "KB", "MB", "GB", "TB" };

	private void initNotificationChannel(String channelName) {
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.app_name);
			/* 这条通知每秒都会刷新，所以必须是静音 / 低重要性的通道。重要性只在**创建**
			   通道时才能设定，所以这里先删再建一次。 */
			NotificationChannel old = notificationManager.getNotificationChannel(channelName);
			if (old != null && old.getImportance() != NotificationManager.IMPORTANCE_LOW)
			  notificationManager.deleteNotificationChannel(channelName);
			if (notificationManager.getNotificationChannel(channelName) == null) {
				NotificationChannel channel = new NotificationChannel(channelName, name,
					NotificationManager.IMPORTANCE_LOW);
				channel.setSound(null, null);
				channel.enableVibration(false);
				notificationManager.createNotificationChannel(channel);
			}
		}
	}
}
