package io.github.wildfly8.brokerbridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ib.client.Contract;

import io.github.wildfly8.brokerbridge.BridgeState.OrderRef;
import io.github.wildfly8.brokerbridge.Model.ConnectionStatus;
import io.github.wildfly8.brokerbridge.Model.HistoryRequest;
import io.github.wildfly8.brokerbridge.Model.HistoryResult;
import io.github.wildfly8.brokerbridge.Model.InstrumentDetails;
import io.github.wildfly8.brokerbridge.Model.OrderRequest;
import io.github.wildfly8.brokerbridge.Model.SnapshotRequest;
import io.github.wildfly8.brokerbridge.Model.SnapshotResult;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

/** The bridge API operations, on top of an {@link IbClient}. */
final class BrokerService {

	private static final Logger log = LoggerFactory.getLogger(BrokerService.class);

	static final long SNAPSHOT_TIMEOUT_MS = 10_000;
	static final long HISTORY_TIMEOUT_MS = 60_000;
	static final long RESOLVE_TIMEOUT_MS = 15_000;
	private static final Map<String, Integer> SNAPSHOT_FIELDS = Map.of("BID", QuoteFields.BID, "ASK",
			QuoteFields.ASK, "LAST", QuoteFields.LAST, "CLOSE", QuoteFields.CLOSE);

	private final IbClient client;
	private final BridgeState state;
	private final boolean ordersEnabled;
	private final EventLog eventLog;

	BrokerService(IbClient client, BridgeState state, boolean ordersEnabled, EventLog eventLog) {
		this.client = client;
		this.state = state;
		this.ordersEnabled = ordersEnabled;
		this.eventLog = eventLog;
		for (EventLog.OrderRecord o : eventLog.orders()) {
			state.ordersByIbId.put(o.brokerOrderId(), new OrderRef(o.clientOrderId(), o.tag(), o.instrument()));
			state.ibIdByClientOrderId.put(o.clientOrderId(), o.brokerOrderId());
		}
	}

	ConnectionStatus status() {
		return state.status(ordersEnabled);
	}

	int subscribe(SubscriptionRequest r) {
		Contract contract = Contracts.toIb(r.instrument());
		requireConnected();
		int id = state.newRequestId();
		boolean snapshot = Boolean.TRUE.equals(r.snapshot());
		state.subscriptions.put(id, r);
		client.reqMktData(id, contract, snapshot ? "" : ticks(r.genericTicks()), snapshot);
		log.info("Subscribed {} {} tag={} snapshot={}", id, r.instrument().symbol(), r.tag(), snapshot);
		return id;
	}

	void unsubscribe(int id) {
		if (state.subscriptions.remove(id) != null && client.isConnected()) {
			client.cancelMktData(id);
		}
	}

	/** Re-sends streaming subscriptions after IB reconnects; one-shot snapshots are dropped. */
	void resubscribe() {
		for (Map.Entry<Integer, SubscriptionRequest> e : state.subscriptions.entrySet()) {
			SubscriptionRequest r = e.getValue();
			if (Boolean.TRUE.equals(r.snapshot())) {
				state.subscriptions.remove(e.getKey());
				continue;
			}
			try {
				client.reqMktData(e.getKey(), Contracts.toIb(r.instrument()), ticks(r.genericTicks()), false);
			} catch (RuntimeException ex) {
				log.warn("Resubscribe {} failed: {}", e.getKey(), ex.toString());
			}
		}
		log.info("Resubscribed {} streams", state.subscriptions.size());
	}

	SnapshotResult snapshot(SnapshotRequest r) {
		Contract contract = Contracts.toIb(r.instrument());
		Integer wanted = null;
		if (r.field() != null) {
			wanted = SNAPSHOT_FIELDS.get(r.field().toUpperCase());
			if (wanted == null) {
				throw new ApiException(400, "unsupported snapshot field: " + r.field());
			}
		}
		requireConnected();
		int id = state.newRequestId();
		BridgeState.Snapshot pending = new BridgeState.Snapshot(wanted);
		state.snapshots.put(id, pending);
		try {
			client.reqMktData(id, contract, "", true);
			return await(pending.done, timeout(r.timeoutMs(), SNAPSHOT_TIMEOUT_MS), "snapshot");
		} finally {
			state.snapshots.remove(id);
		}
	}

	HistoryResult history(HistoryRequest r) {
		Contract contract = Contracts.toIb(r.instrument());
		requireConnected();
		int id = state.newRequestId();
		BridgeState.History pending = new BridgeState.History();
		state.histories.put(id, pending);
		try {
			client.reqHistoricalData(id, contract, r.end() == null ? "" : r.end(), or(r.duration(), "1 D"),
					or(r.barSize(), "1 day"), or(r.what(), "TRADES"), !Boolean.FALSE.equals(r.regularHoursOnly()));
			return await(pending.done, timeout(r.timeoutMs(), HISTORY_TIMEOUT_MS), "history");
		} catch (ApiException e) {
			if (e.status() == 504 && client.isConnected()) {
				client.cancelHistoricalData(id);
			}
			throw e;
		} finally {
			state.histories.remove(id);
		}
	}

	InstrumentDetails resolve(Model.Instrument instrument) {
		Contract contract = Contracts.toIb(instrument);
		requireConnected();
		int id = state.newRequestId();
		BridgeState.Details pending = new BridgeState.Details();
		state.details.put(id, pending);
		try {
			client.reqContractDetails(id, contract);
			List<InstrumentDetails> found = await(pending.done, RESOLVE_TIMEOUT_MS, "contract resolve");
			if (found.isEmpty()) {
				throw new ApiException(404, "no instrument found for " + instrument.symbol());
			}
			if (found.size() > 1) {
				log.info("{} instruments match {}; returning the first", found.size(), instrument.symbol());
			}
			return found.get(0);
		} finally {
			state.details.remove(id);
		}
	}

	long placeOrder(OrderRequest r) {
		requireOrdersEnabled("order " + r.clientOrderId());
		if (r.clientOrderId() == null) {
			throw new ApiException(400, "clientOrderId is required");
		}
		Contract contract = Contracts.toIb(r.instrument());
		requireConnected();
		long clientOrderId = r.clientOrderId();
		int ibId;
		if (Boolean.TRUE.equals(r.modify())) {
			Integer existing = state.ibIdByClientOrderId.get(clientOrderId);
			if (existing == null) {
				throw new ApiException(404, "unknown clientOrderId " + clientOrderId);
			}
			ibId = existing;
		} else {
			if (state.ibIdByClientOrderId.containsKey(clientOrderId)) {
				throw new ApiException(409, "clientOrderId " + clientOrderId + " already used");
			}
			ibId = state.nextOrderId.getAndUpdate(v -> v < 0 ? v : v + 1);
			if (ibId < 0) {
				throw new ApiException(503, "broker has not issued order ids yet", true);
			}
		}
		com.ib.client.Order order = Orders.toIb(r, ibId);
		state.ordersByIbId.put(ibId, new OrderRef(clientOrderId, r.tag(), r.instrument()));
		state.ibIdByClientOrderId.put(clientOrderId, ibId);
		if (!Boolean.TRUE.equals(r.modify())) {
			eventLog.recordOrder(new EventLog.OrderRecord(clientOrderId, ibId, r.tag(), r.instrument()));
		}
		client.placeOrder(ibId, contract, order);
		log.info("{} order {} (broker {}) {} {} {} @ {}", Boolean.TRUE.equals(r.modify()) ? "Modified" : "Placed",
				clientOrderId, ibId, r.side(), r.quantity(), r.instrument().symbol(), r.limitPrice());
		return clientOrderId;
	}

	void cancelOrder(long clientOrderId) {
		requireOrdersEnabled("cancel " + clientOrderId);
		requireConnected();
		Integer ibId = state.ibIdByClientOrderId.get(clientOrderId);
		if (ibId == null) {
			throw new ApiException(404, "unknown clientOrderId " + clientOrderId);
		}
		client.cancelOrder(ibId);
		log.info("Cancel requested for order {} (broker {})", clientOrderId, ibId);
	}

	private void requireOrdersEnabled(String what) {
		if (!ordersEnabled) {
			log.warn("Refused {}: BRIDGE_ORDERS_ENABLED is not true", what);
			throw new ApiException(403, "orders are disabled on the bridge (BRIDGE_ORDERS_ENABLED=false)");
		}
	}

	private void requireConnected() {
		if (!state.connected() || !client.isConnected()) {
			throw new ApiException(503, "broker disconnected", true);
		}
	}

	private static <T> T await(CompletableFuture<T> f, long timeoutMs, String what) {
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new ApiException(504, what + " timed out after " + timeoutMs + " ms");
		} catch (ExecutionException e) {
			if (e.getCause() instanceof ApiException a) {
				throw a;
			}
			throw new ApiException(502, what + " failed: " + e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(503, what + " interrupted");
		}
	}

	private static long timeout(Long requested, long def) {
		return requested == null || requested <= 0 ? def : requested;
	}

	private static String ticks(String genericTicks) {
		return genericTicks == null ? "" : genericTicks;
	}

	private static String or(String v, String def) {
		return v == null || v.isBlank() ? def : v;
	}
}
