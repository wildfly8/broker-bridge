package io.github.wildfly8.brokerbridge;

import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.Execution;
import com.ib.client.TickAttrib;

import io.github.wildfly8.brokerbridge.BridgeState.OrderRef;
import io.github.wildfly8.brokerbridge.Model.Bar;
import io.github.wildfly8.brokerbridge.Model.ErrorEvent;
import io.github.wildfly8.brokerbridge.Model.FillEvent;
import io.github.wildfly8.brokerbridge.Model.OptionValues;
import io.github.wildfly8.brokerbridge.Model.OrderStatusEvent;
import io.github.wildfly8.brokerbridge.Model.QuoteEvent;
import io.github.wildfly8.brokerbridge.Model.SnapshotEnd;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

/** Turns IB callbacks into neutral events and completes pending requests. */
class IbCallbacks extends DefaultEWrapper {

	private static final Logger log = LoggerFactory.getLogger(IbCallbacks.class);

	/** Notified of IB session changes so the connection can resubscribe or reconnect. */
	interface SessionListener {
		void closed();

		void restored(boolean dataLost);
	}

	static final int NO_SECURITY = 200;
	/** Warnings: logged and published, but don't fail a pending request. */
	private static final Set<Integer> WARNINGS = Set.of(399, 10167, 10197, 2174);
	private static final SessionListener NO_LISTENER = new SessionListener() {
		@Override public void closed() {}
		@Override public void restored(boolean dataLost) {}
	};

	private final BridgeState state;
	private final EventHub hub;
	private final boolean ordersEnabled;
	private volatile SessionListener listener = NO_LISTENER;

	IbCallbacks(BridgeState state, EventHub hub, boolean ordersEnabled) {
		this.state = state;
		this.hub = hub;
		this.ordersEnabled = ordersEnabled;
	}

	void listener(SessionListener l) {
		this.listener = l;
	}

	// ---- market data ----

	@Override
	public void tickPrice(int reqId, int field, double price, TickAttrib attrib) {
		int code = QuoteFields.normalize(field);
		BridgeState.Snapshot snap = state.snapshots.get(reqId);
		if (snap != null) {
			snap.price(code, price);
			return;
		}
		quote(reqId, code, price, null, null, null);
	}

	@Override
	public void tickSize(int reqId, int field, Decimal size) {
		if (state.snapshots.containsKey(reqId)) {
			return;
		}
		quote(reqId, QuoteFields.normalize(field), null, number(size), null, null);
	}

	@Override
	public void tickString(int reqId, int field, String value) {
		int code = QuoteFields.normalize(field);
		if (code != QuoteFields.LAST_TIMESTAMP || value == null) {
			return;
		}
		try {
			quote(reqId, code, null, null, null, Instant.ofEpochSecond(Long.parseLong(value.trim())).toString());
		} catch (NumberFormatException e) {
			log.debug("Ignoring timestamp tick {}", value);
		}
	}

	@Override
	public void tickOptionComputation(int reqId, int field, int tickAttrib, double impliedVol, double delta,
			double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice) {
		int code = QuoteFields.normalize(field);
		if (!QuoteFields.isOption(code)) {
			return;
		}
		OptionValues values = new OptionValues(nonNegative(impliedVol), computed(delta), computed(gamma),
				computed(theta), computed(vega), nonNegative(optPrice), nonNegative(undPrice));
		quote(reqId, code, null, null, values, null);
	}

	@Override
	public void tickSnapshotEnd(int reqId) {
		BridgeState.Snapshot snap = state.snapshots.get(reqId);
		if (snap != null) {
			snap.end();
			return;
		}
		SubscriptionRequest sub = state.subscriptions.remove(reqId);
		if (sub != null) {
			hub.publish("snapshot-end", new SnapshotEnd(reqId, sub.tag()));
		}
	}

	private void quote(int reqId, int code, Double price, Double size, OptionValues option, String time) {
		SubscriptionRequest sub = state.subscriptions.get(reqId);
		if (sub == null) {
			return;
		}
		hub.publish("quote", new QuoteEvent(sub.tag(), reqId, QuoteFields.name(code), code, price, size, option,
				time != null ? time : Instant.now().toString()));
	}

	// ---- history and contract details ----

	@Override
	public void historicalData(int reqId, com.ib.client.Bar bar) {
		BridgeState.History h = state.histories.get(reqId);
		if (h != null) {
			h.add(new Bar(bar.time(), bar.open(), bar.high(), bar.low(), bar.close(), number(bar.volume()),
					bar.count(), number(bar.wap())));
		}
	}

	@Override
	public void historicalDataEnd(int reqId, String start, String end) {
		BridgeState.History h = state.histories.get(reqId);
		if (h != null) {
			h.end();
		}
	}

	@Override
	public void contractDetails(int reqId, ContractDetails details) {
		BridgeState.Details d = state.details.get(reqId);
		if (d != null) {
			d.add(Contracts.fromIb(details));
		}
	}

	@Override
	public void contractDetailsEnd(int reqId) {
		BridgeState.Details d = state.details.get(reqId);
		if (d != null) {
			d.end();
		}
	}

	// ---- orders ----

	@Override
	public void nextValidId(int orderId) {
		state.nextOrderId.accumulateAndGet(orderId, Math::max);
	}

	@Override
	public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice,
			long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
		OrderRef ref = state.ordersByIbId.get(orderId);
		if (ref == null) {
			log.debug("Status for an order this bridge didn't place: {} {}", orderId, status);
			return;
		}
		hub.publishOrderEvent("order-status", new OrderStatusEvent(ref.clientOrderId(), status, number(filled),
				number(remaining), avgFillPrice, lastFillPrice, String.valueOf(permId),
				parentId == 0 ? null : String.valueOf(parentId), ref.tag(), whyHeld));
	}

	@Override
	public void execDetails(int reqId, Contract contract, Execution e) {
		OrderRef ref = state.ordersByIbId.get(e.orderId());
		if (ref == null) {
			log.debug("Fill for an order this bridge didn't place: {}", e.orderId());
			return;
		}
		if (!hub.eventLog().firstFill(e.execId())) {
			log.debug("Fill {} already delivered", e.execId());
			return;
		}
		String side = switch (String.valueOf(e.side())) {
			case "BOT" -> "BOUGHT";
			case "SLD" -> "SOLD";
			default -> e.side();
		};
		hub.publishOrderEvent("fill", new FillEvent(ref.clientOrderId(), e.execId(), side, number(e.shares()), e.price(),
				e.avgPrice(), number(e.cumQty()), e.exchange(), String.valueOf(e.permId()), ref.tag(), e.time(),
				Contracts.fromIb(contract)));
	}

	// ---- session ----

	@Override
	public void managedAccounts(String accountsList) {
		state.accounts(Json.maskAccounts(accountsList));
	}

	@Override
	public void connectionClosed() {
		log.warn("IB connection closed");
		state.connected(false, null);
		failPending(new ApiException(503, "broker disconnected", true));
		hub.publish("connection", state.status(ordersEnabled));
		listener.closed();
	}

	@Override
	public void error(Exception e) {
		log.warn("IB client error: {}", Json.maskAccounts(String.valueOf(e)));
	}

	@Override
	public void error(String str) {
		log.warn("IB client error: {}", Json.maskAccounts(str));
	}

	@Override
	public void error(int id, long errorTime, int code, String msg, String advancedOrderRejectJson) {
		String text = Json.maskAccounts(msg);
		if (code >= 2100 && code < 2200 && !WARNINGS.contains(code)) {
			dataFarm(code, text);
			log.info("IB {}: {}", code, text);
			return;
		}
		switch (code) {
			case 1100 -> {
				log.warn("IB 1100: {}", text);
				state.lastError(code + " " + text);
				hub.publish("connection", state.status(ordersEnabled));
				return;
			}
			case 1101, 1102 -> {
				log.info("IB {}: {}", code, text);
				listener.restored(code == 1101);
				hub.publish("connection", state.status(ordersEnabled));
				return;
			}
			default -> { }
		}
		boolean warning = WARNINGS.contains(code);
		boolean retryable = isPacing(code, text);
		if (warning) {
			log.info("IB warning {} (id {}): {}", code, id, text);
		} else {
			log.warn("IB error {} (id {}): {}", code, id, text);
			state.lastError(code + " " + text);
		}
		OrderRef order = state.ordersByIbId.get(id);
		if (order != null) {
			hub.publishOrderEvent("error", new ErrorEvent(null, order.clientOrderId(), code, text, retryable));
			return;
		}
		if (!warning) {
			completeWithError(id, code, text, retryable);
		}
		hub.publish("error", new ErrorEvent(id < 0 ? null : id, null, code, text, retryable));
	}

	private void completeWithError(int id, int code, String text, boolean retryable) {
		BridgeState.Details d = state.details.get(id);
		if (d != null) {
			if (code == NO_SECURITY) {
				d.end();
			} else {
				d.done.completeExceptionally(failure(code, text, retryable));
			}
		}
		BridgeState.Snapshot s = state.snapshots.get(id);
		if (s != null) {
			s.done.completeExceptionally(failure(code, text, retryable));
		}
		BridgeState.History h = state.histories.get(id);
		if (h != null) {
			h.done.completeExceptionally(failure(code, text, retryable));
		}
		if (state.subscriptions.containsKey(id) && code == NO_SECURITY) {
			state.subscriptions.remove(id);
		}
	}

	private static ApiException failure(int code, String text, boolean retryable) {
		int status = retryable ? 429 : code == NO_SECURITY ? 404 : 502;
		return new ApiException(status, "broker error " + code + ": " + text, retryable,
				retryable ? ApiException.retryAfterSeconds(text) : null);
	}

	private void failPending(ApiException e) {
		state.snapshots.values().forEach(s -> s.done.completeExceptionally(e));
		state.histories.values().forEach(h -> h.done.completeExceptionally(e));
		state.details.values().forEach(d -> d.done.completeExceptionally(e));
	}

	/** "Market data farm connection is OK:usfarm" → usfarm up. */
	private void dataFarm(int code, String text) {
		int colon = text == null ? -1 : text.lastIndexOf(':');
		if (colon < 0) {
			return;
		}
		String farm = text.substring(colon + 1).trim();
		switch (code) {
			case 2104, 2106, 2107, 2108, 2158 -> state.dataFarm(farm, true);
			case 2103, 2105, 2157 -> state.dataFarm(farm, false);
			default -> { return; }
		}
		hub.publish("connection", state.status(ordersEnabled));
	}

	static boolean isPacing(int code, String text) {
		return code == 100 || code == 420
				|| (text != null && text.toLowerCase().contains("pacing violation"));
	}

	private static Double number(Decimal d) {
		return d == null || !d.isValid() ? null : d.value().doubleValue();
	}

	/** IB marks "not computed" with Double.MAX_VALUE or a negative sentinel. */
	private static Double nonNegative(double v) {
		return v == Double.MAX_VALUE || v < 0 ? null : v;
	}

	private static Double computed(double v) {
		return v == Double.MAX_VALUE || v == -2 ? null : v;
	}
}
