package io.github.wildfly8.brokerbridge;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Lets the first message of a kind through and then at most one per window, telling how many it held back. IB repeats some of its
 * information messages for every request (2188, "Up-to-the-second historical data requires additional subscription", once per
 * historical request: hundreds an hour while the bars are filled), which pushed the lines that matter out of the log a person reads
 * after a night (MTS live-log finding of 2026-10-11).
 */
final class RepeatGate {

	private final long windowMs;
	private final LongSupplier clock;
	/** Per kind: when one last went through, and how many were held back since. */
	private final Map<Integer, long[]> kinds = new HashMap<>();

	RepeatGate(long windowMs, LongSupplier clock) {
		this.windowMs = windowMs;
		this.clock = clock;
	}

	/**
	 * @return -1 when the message is to be held back; otherwise how many of its kind were held back since the last one went through
	 *         (0 for the first, or when none were)
	 */
	synchronized int admit(int kind) {
		long now = clock.getAsLong();
		long[] k = kinds.get(kind);
		if (k == null) {
			kinds.put(kind, new long[] { now, 0 });
			return 0;
		}
		if (now - k[0] < windowMs) {
			k[1]++;
			return -1;
		}
		int held = (int) k[1];
		k[0] = now;
		k[1] = 0;
		return held;
	}
}
