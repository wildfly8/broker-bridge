package io.github.wildfly8.brokerbridge;

import java.util.List;
import java.util.Map;

/**
 * Broker-neutral wire model (API v1, see README).
 * No IB type, enum or field layout appears here: plain names and values only. {@code tag} is an opaque string the
 * client attaches to a subscription or order; every event that results from it carries the same tag back.
 */
public final class Model {

	private Model() {}

	public record Leg(String brokerId, int ratio, String side, String exchange) {}

	public record Instrument(String symbol, String type, String exchange, String primaryExchange, String currency,
			String expiry, String right, Double strike, String multiplier, String brokerId, List<Leg> legs) {

		public static Instrument stock(String symbol, String exchange, String currency) {
			return new Instrument(symbol, "STOCK", exchange, null, currency, null, null, null, null, null, null);
		}
	}

	public record InstrumentDetails(Instrument instrument, String marketName, Double minTick, Integer priceMagnifier,
			String orderTypes, String validExchanges) {}

	public record OptionValues(Double impliedVol, Double delta, Double gamma, Double theta, Double vega, Double optionPrice,
			Double underlyingPrice) {}

	/** {@code delayed}: true when the broker's tick was a delayed one (its field code is a delayed-data code); left out otherwise. */
	public record QuoteEvent(String tag, int subscriptionId, String field, int code, Double price, Double size,
			OptionValues option, String time, Boolean delayed) {

		public QuoteEvent(String tag, int subscriptionId, String field, int code, Double price, Double size,
				OptionValues option, String time) {
			this(tag, subscriptionId, field, code, price, size, option, time, null);
		}
	}

	public record Bar(String time, double open, double high, double low, double close, Double volume, Integer count, Double wap) {}

	/**
	 * {@code owner}: an opaque mark of whoever opened the stream, listed back by {@code GET /v1/subscriptions} so that a client can tell its own
	 * streams from those an earlier run of it left behind (a run id, for instance).
	 */
	public record SubscriptionRequest(Instrument instrument, String tag, Boolean snapshot, String genericTicks, String owner) {

		public SubscriptionRequest(Instrument instrument, String tag, Boolean snapshot, String genericTicks) {
			this(instrument, tag, snapshot, genericTicks, null);
		}
	}

	/** One stream the bridge holds, as {@code GET /v1/subscriptions} lists it; {@code owner} and {@code genericTicks} are left out when there are none. */
	public record SubscriptionInfo(int subscriptionId, String tag, String owner, Instrument instrument, boolean snapshot, String genericTicks) {}

	/** {@code eventClients}: the clients attached to {@code /v1/events} now, the caller's own included. */
	public record SubscriptionList(List<SubscriptionInfo> subscriptions, int eventClients) {}

	public record SnapshotRequest(Instrument instrument, String field, Long timeoutMs) {}

	/** {@code delayed}: true when any tick that made up the snapshot was a delayed one; left out otherwise. */
	public record SnapshotResult(Double bid, Double ask, Double last, Double close, String time, Boolean delayed) {

		public SnapshotResult(Double bid, Double ask, Double last, Double close, String time) {
			this(bid, ask, last, close, time, null);
		}
	}

	public record HistoryRequest(Instrument instrument, String end, String duration, String barSize, String what,
			Boolean regularHoursOnly, Long timeoutMs) {}

	public record HistoryResult(List<Bar> bars) {}

	public record OrderRequest(Long clientOrderId, String tag, Instrument instrument, String side, Double quantity,
			String type, Double limitPrice, Double stopPrice, String timeInForce, Boolean allOrNone,
			String orderRef, Boolean modify) {}

	/**
	 * {@code instrument}: the order's own, from the reference the bridge holds for it (placed, or adopted from the broker's open orders); left out
	 * when the bridge does not hold it (an event that an earlier bridge journaled has none). A status carries no contract otherwise.
	 */
	public record OrderStatusEvent(Long clientOrderId, String status, Double filled, Double remaining, Double avgFillPrice,
			Double lastFillPrice, String permId, String parentId, String tag, String whyHeld, Instrument instrument) {

		public OrderStatusEvent(Long clientOrderId, String status, Double filled, Double remaining, Double avgFillPrice,
				Double lastFillPrice, String permId, String parentId, String tag, String whyHeld) {
			this(clientOrderId, status, filled, remaining, avgFillPrice, lastFillPrice, permId, parentId, tag, whyHeld, null);
		}
	}

	public record FillEvent(Long clientOrderId, String fillId, String side, Double quantity, Double price, Double avgPrice,
			Double cumQuantity, String exchange, String permId, String tag, String time, Instrument instrument) {}

	/**
	 * {@code dataFarms}: broker data-farm name → connected (e.g. usfarm, usopt, ushmds). {@code startedAt} changes
	 * only when the bridge restarts, so a client can tell its subscriptions are gone. {@code durable}: the order events are journaled on
	 * disk ({@code BRIDGE_DATA_DIR}), so a restart of the bridge keeps them; false, they are lost with it.
	 * {@code linkUp}: false from IB's error 1100 (its connection to the broker's servers is lost, the socket to the bridge stays open) until
	 * 1101 or 1102 or a new connection; orders, modifications and cancels are refused meanwhile.
	 */
	public record ConnectionStatus(boolean connected, Integer serverVersion, String accounts, String lastError,
			boolean ordersEnabled, String since, Map<String, Boolean> dataFarms, String startedAt, boolean durable, boolean linkUp) {}

	public record SnapshotEnd(int subscriptionId, String tag) {}

	/** {@code clientOrderId} is set when the error belongs to an order, {@code requestId} otherwise. */
	public record ErrorEvent(Integer requestId, Long clientOrderId, int code, String message, boolean retryable) {}

	public record SubscriptionId(int subscriptionId) {}

	public record OrderAccepted(long clientOrderId) {}

	public record ResolveRequest(Instrument instrument) {}
}
