package io.github.wildfly8.brokerbridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.wildfly8.brokerbridge.Model.Bar;
import io.github.wildfly8.brokerbridge.Model.ConnectionStatus;
import io.github.wildfly8.brokerbridge.Model.HistoryResult;
import io.github.wildfly8.brokerbridge.Model.Instrument;
import io.github.wildfly8.brokerbridge.Model.InstrumentDetails;
import io.github.wildfly8.brokerbridge.Model.SnapshotResult;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

/** Shared state between HTTP requests and IB callbacks. */
final class BridgeState {

	record OrderRef(long clientOrderId, String tag, Instrument instrument) {}

	final AtomicInteger nextRequestId = new AtomicInteger(1000);
	/** Active streaming/snapshot subscriptions by request id; re-sent after a reconnect. */
	final Map<Integer, SubscriptionRequest> subscriptions = new ConcurrentHashMap<>();
	final Map<Integer, Snapshot> snapshots = new ConcurrentHashMap<>();
	final Map<Integer, History> histories = new ConcurrentHashMap<>();
	final Map<Integer, Details> details = new ConcurrentHashMap<>();

	/** Next IB order id; negative until IB has sent one. */
	final AtomicInteger nextOrderId = new AtomicInteger(-1);
	/**
	 * Above every broker order id the journal and this run have used. IB's own counter can come back lower (the Gateway's "Reset API order ID
	 * sequence", a lost settings volume), and an id the journal knows belongs to another order whose fills and statuses would then be booked
	 * on the new one: the counter is never below this.
	 */
	final AtomicInteger orderIdFloor = new AtomicInteger(0);
	final Map<Integer, OrderRef> ordersByIbId = new ConcurrentHashMap<>();
	final Map<Long, Integer> ibIdByClientOrderId = new ConcurrentHashMap<>();
	/** The reading of the broker's open and completed orders that the connection is making, or null when none is. */
	volatile OrderSync sync;

	private final String startedAt = Instant.now().toString();
	private final Map<String, Boolean> dataFarms = new ConcurrentHashMap<>();
	private volatile boolean connected;
	private volatile Integer serverVersion;
	private volatile String accounts;
	private volatile String lastError;
	private volatile Instant since = Instant.now();
	private volatile boolean durable;
	/** IB's own link to the broker's servers (error 1100 lost, 1101 and 1102 restored): the socket to the Gateway can be open while it is down. */
	private volatile Instant linkDownSince;

	int newRequestId() {
		return nextRequestId.incrementAndGet();
	}

	boolean connected() {
		return connected;
	}

	void connected(boolean up, Integer version) {
		if (up != connected) {
			since = Instant.now();
		}
		connected = up;
		serverVersion = up ? version : null;
		if (!up) {
			// no session says nothing about a link that was lost on the old one. A session that is marked up keeps what IB told it while it was
			// being set up (the reading of the orders takes seconds): the connection starts with the link up (link(true)), and marking it up
			// does not say so again
			linkDownSince = null;
			dataFarms.clear();
			nextOrderId.set(-1);
		}
	}

	void accounts(String masked) {
		accounts = masked;
	}

	void lastError(String text) {
		lastError = text;
	}

	void dataFarm(String name, boolean up) {
		dataFarms.put(name, up);
	}

	/** IB's error 1100 (lost) or 1101 and 1102 (restored): see {@link Model.ConnectionStatus#linkUp}. A second 1100 keeps the first time. */
	void link(boolean up) {
		if (up) {
			linkDownSince = null;
		} else if (linkDownSince == null) {
			linkDownSince = Instant.now();
		}
	}

	boolean linkUp() {
		return linkDownSince == null;
	}

	Instant linkDownSince() {
		return linkDownSince;
	}

	/** Whether the order events are journaled on disk ({@link EventLog#durable}); told once, by the service that owns the log. */
	void durable(boolean durable) {
		this.durable = durable;
	}

	ConnectionStatus status(boolean ordersEnabled) {
		return new ConnectionStatus(connected, serverVersion, accounts, lastError, ordersEnabled, since.toString(),
				new TreeMap<>(dataFarms), startedAt, durable, linkUp());
	}

	/** Collects one snapshot; completes when the wanted field arrives or at snapshot end. */
	static final class Snapshot {
		final Integer wantedCode;
		final CompletableFuture<SnapshotResult> done = new CompletableFuture<>();
		private Double bid, ask, last, close;
		private boolean delayed;

		Snapshot(Integer wantedCode) {
			this.wantedCode = wantedCode;
		}

		synchronized void price(int code, double price, boolean delayedTick) {
			switch (code) {
				case QuoteFields.BID -> bid = price;
				case QuoteFields.ASK -> ask = price;
				case QuoteFields.LAST -> last = price;
				case QuoteFields.CLOSE -> close = price;
				default -> { return; }
			}
			delayed |= delayedTick;
			if (wantedCode != null && wantedCode == code) {
				end();
			}
		}

		synchronized void end() {
			done.complete(new SnapshotResult(bid, ask, last, close, Instant.now().toString(), delayed ? Boolean.TRUE : null));
		}
	}

	static final class History {
		final CompletableFuture<HistoryResult> done = new CompletableFuture<>();
		private final List<Bar> bars = new ArrayList<>();

		synchronized void add(Bar bar) {
			bars.add(bar);
		}

		synchronized void end() {
			done.complete(new HistoryResult(List.copyOf(bars)));
		}
	}

	static final class Details {
		final CompletableFuture<List<InstrumentDetails>> done = new CompletableFuture<>();
		private final List<InstrumentDetails> found = new ArrayList<>();

		synchronized void add(InstrumentDetails d) {
			found.add(d);
		}

		synchronized void end() {
			done.complete(List.copyOf(found));
		}
	}
}
