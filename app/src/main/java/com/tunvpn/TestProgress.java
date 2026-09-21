/*
 ============================================================================
 文件名  : TestProgress.java
 说明    : 一轮延迟测速的**进程级**状态。
           一轮测速的寿命长于发起它的 SubscribeActivity：用户可以离开页面，甚至
           Activity 被销毁，worker 线程仍在继续探测。把计数器放在 Activity 里时，
           切走再回来进度条就没了（而且结果只在整轮结束时才落盘，等于一起丢了）。
           所以它们住在:这里，页面在 onResume 里重新读取即可。
           另有一个 generation 计数：新开的一轮可以取消"孤儿轮"剩余的工作，这样两轮
           永远不会去抢那条单回调的动作桥。
 ============================================================================
*/

package com.tunvpn;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class TestProgress {
	private TestProgress() { }

	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static final AtomicInteger GEN = new AtomicInteger();
	private static final AtomicInteger TOTAL = new AtomicInteger();
	private static final AtomicInteger DONE = new AtomicInteger();
	private static volatile long deadlineMs = 0;
	private static volatile boolean batch = false;
	private static volatile long startedAtMs = 0;

	/* 开始一轮。返回该轮的 generation 号；已有轮次在跑时返回 -1（调用方据此拒绝
	   重复发起）。 */
	static int begin(int total, long deadline, boolean isBatch) {
		if (!RUNNING.compareAndSet(false, true))
		  return -1;
		int gen = GEN.incrementAndGet();
		TOTAL.set(Math.max(1, total));
		DONE.set(0);
		deadlineMs = deadline;
		batch = isBatch;
		startedAtMs = System.currentTimeMillis();
		return gen;
	}

	/* 当前（或最近一轮）已运行了多久。 */
	static long elapsedMs() {
		long t = startedAtMs;
		return t <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - t);
	}

	/* 按已观测到的速度粗略估计"还剩多久"；还算不出来时返回 0。
	   因为动作桥是串行的，这个数字才是诚实的 —— 一轮的耗时≈各次探测之和，而不是
	   最慢那个节点的耗时。 */
	static long etaMs() {
		int d = DONE.get();
		if (d <= 0)
		  return 0;
		return elapsedMs() * pending() / d;
	}

	/* 第 `gen` 轮的某个节点完成了（测出结果或已放弃）。若该轮已被替换则忽略，
	   这样孤儿轮遗留的工作不会把新轮的进度计多。 */
	static void nodeDone(int gen) {
		if (GEN.get() == gen)
		  DONE.incrementAndGet();
	}

	/* 结束该轮。幂等：正常路径、看门狗、以及"回收页面已死的轮次"三条路都会调它。 */
	static void finish() {
		DONE.set(TOTAL.get());
		deadlineMs = 0;
		batch = false;
		RUNNING.set(false);
	}

	static boolean running() {
		return RUNNING.get();
	}

	static boolean batch() {
		return batch;
	}

	static int total() {
		return TOTAL.get();
	}

	static int done() {
		return DONE.get();
	}

	static int pending() {
		return Math.max(0, TOTAL.get() - DONE.get());
	}

	static int percent() {
		int t = Math.max(1, TOTAL.get());
		return (int) Math.min(100, DONE.get() * 100L / t);
	}

	/* 当**本轮**仍是当前轮、且还在时间预算内时为真。已经不是当前轮的必须停止探测，
	   而不是继续去抢内核。 */
	static boolean isCurrent(int gen) {
		if (!RUNNING.get() || GEN.get() != gen)
		  return false;
		long d = deadlineMs;
		return d <= 0 || System.currentTimeMillis() <= d;
	}

	/* 页面被销毁的轮次永远不会报告它的最后一个节点，所以由下一个页面在这里回收它，
	   否则会被"上一轮还没结束"永久挡住。 */
	static boolean stale() {
		if (!RUNNING.get())
		  return false;
		if (pending() <= 0)
		  return true;
		long d = deadlineMs;
		return d > 0 && System.currentTimeMillis() > d;
	}
}
