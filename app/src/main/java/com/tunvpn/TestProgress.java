/*
 ============================================================================
 Name        : TestProgress.java
 Description : Process-wide state of a latency-test pass.
               A pass OUTLIVES the SubscribeActivity that started it: the user
               can leave the page, and the Activity can even be destroyed while
               the worker threads keep probing. Keeping the counters in the
               Activity meant switching away and back lost the progress bar (and
               the results were only persisted at the very end, so they were lost
               with it). They live here instead, and the page simply re-reads
               them in onResume.
               A generation counter lets a newly started pass cancel the
               remaining work of an orphaned one, so two passes can never fight
               over the single-callback action bridge.
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

	/* Begin a pass. Returns its generation number, or -1 when a pass is already
	   running (the caller then refuses to start another one). */
	static int begin(int total, long deadline, boolean isBatch) {
		if (!RUNNING.compareAndSet(false, true))
		  return -1;
		int gen = GEN.incrementAndGet();
		TOTAL.set(Math.max(1, total));
		DONE.set(0);
		deadlineMs = deadline;
		batch = isBatch;
		return gen;
	}

	/* One node of pass `gen` finished (measured or given up on). Ignored when
	   that pass has since been replaced, so leftover work from an orphaned pass
	   cannot over-count the new one's progress. */
	static void nodeDone(int gen) {
		if (GEN.get() == gen)
		  DONE.incrementAndGet();
	}

	/* End the pass. Idempotent: the normal path, the watchdog and the "reap a
	   pass whose page is gone" path all call it. */
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

	/* True while THIS pass is still the current one and inside its budget. A
	   pass that is no longer current must stop probing, not keep fighting for
	   the core. */
	static boolean isCurrent(int gen) {
		if (!RUNNING.get() || GEN.get() != gen)
		  return false;
		long d = deadlineMs;
		return d <= 0 || System.currentTimeMillis() <= d;
	}

	/* A pass whose page was destroyed never reports its last node, so the next
	   page reaps it here instead of being locked out of testing forever. */
	static boolean stale() {
		if (!RUNNING.get())
		  return false;
		if (pending() <= 0)
		  return true;
		long d = deadlineMs;
		return d > 0 && System.currentTimeMillis() > d;
	}
}
