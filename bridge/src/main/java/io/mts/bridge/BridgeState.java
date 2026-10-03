package io.mts.bridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.mts.bridge.Model.Bar;
import io.mts.bridge.Model.ConnectionStatus;
import io.mts.bridge.Model.HistoryResult;
import io.mts.bridge.Model.Instrument;
import io.mts.bridge.Model.InstrumentDetails;
import io.mts.bridge.Model.SnapshotResult;
import io.mts.bridge.Model.SubscriptionRequest;

/** Shared state between HTTP requests and IB callbacks. */
final class BridgeState {

	record OrderRef(long clientOrderId, Integer route, Instrument instrument) {}

	final AtomicInteger nextRequestId = new AtomicInteger(1000);
	/** Active streaming/snapshot subscriptions by request id; re-sent after a reconnect. */
	final Map<Integer, SubscriptionRequest> subscriptions = new ConcurrentHashMap<>();
	final Map<Integer, Snapshot> snapshots = new ConcurrentHashMap<>();
	final Map<Integer, History> histories = new ConcurrentHashMap<>();
	final Map<Integer, Details> details = new ConcurrentHashMap<>();

	/** Next IB order id; negative until IB has sent one. */
	final AtomicInteger nextOrderId = new AtomicInteger(-1);
	final Map<Integer, OrderRef> ordersByIbId = new ConcurrentHashMap<>();
	final Map<Long, Integer> ibIdByClientOrderId = new ConcurrentHashMap<>();

	private final String startedAt = Instant.now().toString();
	private final Map<String, Boolean> dataFarms = new ConcurrentHashMap<>();
	private volatile boolean connected;
	private volatile Integer serverVersion;
	private volatile String accounts;
	private volatile String lastError;
	private volatile Instant since = Instant.now();

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

	ConnectionStatus status(boolean ordersEnabled) {
		return new ConnectionStatus(connected, serverVersion, accounts, lastError, ordersEnabled, since.toString(),
				new TreeMap<>(dataFarms), startedAt);
	}

	/** Collects one snapshot; completes when the wanted field arrives or at snapshot end. */
	static final class Snapshot {
		final Integer wantedCode;
		final CompletableFuture<SnapshotResult> done = new CompletableFuture<>();
		private Double bid, ask, last, close;

		Snapshot(Integer wantedCode) {
			this.wantedCode = wantedCode;
		}

		synchronized void price(int code, double price) {
			switch (code) {
				case QuoteFields.BID -> bid = price;
				case QuoteFields.ASK -> ask = price;
				case QuoteFields.LAST -> last = price;
				case QuoteFields.CLOSE -> close = price;
				default -> { return; }
			}
			if (wantedCode != null && wantedCode == code) {
				end();
			}
		}

		synchronized void end() {
			done.complete(new SnapshotResult(bid, ask, last, close, Instant.now().toString()));
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
