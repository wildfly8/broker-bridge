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

	public record QuoteEvent(String tag, int subscriptionId, String field, int code, Double price, Double size,
			OptionValues option, String time) {}

	public record Bar(String time, double open, double high, double low, double close, Double volume, Integer count, Double wap) {}

	public record SubscriptionRequest(Instrument instrument, String tag, Boolean snapshot, String genericTicks) {}

	public record SnapshotRequest(Instrument instrument, String field, Long timeoutMs) {}

	public record SnapshotResult(Double bid, Double ask, Double last, Double close, String time) {}

	public record HistoryRequest(Instrument instrument, String end, String duration, String barSize, String what,
			Boolean regularHoursOnly, Long timeoutMs) {}

	public record HistoryResult(List<Bar> bars) {}

	public record OrderRequest(Long clientOrderId, String tag, Instrument instrument, String side, Double quantity,
			String type, Double limitPrice, Double stopPrice, String timeInForce, Boolean allOrNone,
			String orderRef, Boolean modify) {}

	public record OrderStatusEvent(Long clientOrderId, String status, Double filled, Double remaining, Double avgFillPrice,
			Double lastFillPrice, String permId, String parentId, String tag, String whyHeld) {}

	public record FillEvent(Long clientOrderId, String fillId, String side, Double quantity, Double price, Double avgPrice,
			Double cumQuantity, String exchange, String permId, String tag, String time, Instrument instrument) {}

	/**
	 * {@code dataFarms}: broker data-farm name → connected (e.g. usfarm, usopt, ushmds). {@code startedAt} changes
	 * only when the bridge restarts, so a client can tell its subscriptions are gone.
	 */
	public record ConnectionStatus(boolean connected, Integer serverVersion, String accounts, String lastError,
			boolean ordersEnabled, String since, Map<String, Boolean> dataFarms, String startedAt) {}

	public record SnapshotEnd(int subscriptionId, String tag) {}

	/** {@code clientOrderId} is set when the error belongs to an order, {@code requestId} otherwise. */
	public record ErrorEvent(Integer requestId, Long clientOrderId, int code, String message, boolean retryable) {}

	public record SubscriptionId(int subscriptionId) {}

	public record OrderAccepted(long clientOrderId) {}

	public record ResolveRequest(Instrument instrument) {}
}
