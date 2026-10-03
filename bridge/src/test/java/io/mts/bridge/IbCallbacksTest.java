package io.mts.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.TickAttrib;

import io.mts.bridge.Model.Instrument;
import io.mts.bridge.Model.SubscriptionRequest;

class IbCallbacksTest {

	private BridgeState state;
	private EventHub hub;
	private EventHub.Client events;
	private IbCallbacks callbacks;

	@BeforeEach
	void setUp() {
		state = new BridgeState();
		hub = new EventHub();
		events = hub.register();
		callbacks = new IbCallbacks(state, hub, false);
		state.subscriptions.put(42, new SubscriptionRequest(Instrument.stock("SPY", "SMART", "USD"), 3, false, null));
	}

	/** Drains published events as (name, data) pairs. */
	private List<String[]> drain() throws Exception {
		List<String[]> out = new ArrayList<>();
		String f;
		while (!(f = hub.next(events, 1)).equals(EventHub.HEARTBEAT)) {
			String[] lines = f.split("\n");
			out.add(new String[] { lines[0].substring("event: ".length()), lines[1].substring("data: ".length()) });
		}
		return out;
	}

	private static JsonNode json(String s) throws Exception {
		return Json.MAPPER.readTree(s);
	}

	@Test
	void quotesCarryRouteFieldAndCode() throws Exception {
		callbacks.tickPrice(42, 1, 501.25, new TickAttrib());
		callbacks.tickSize(42, 3, Decimal.get(BigDecimal.valueOf(200)));
		callbacks.tickPrice(42, 68, 501.30, new TickAttrib()); // delayed last → LAST
		callbacks.tickPrice(99, 1, 1.0, new TickAttrib()); // unknown request: ignored
		List<String[]> ev = drain();
		assertEquals(3, ev.size());
		JsonNode bid = json(ev.get(0)[1]);
		assertEquals("quote", ev.get(0)[0]);
		assertEquals(3, bid.get("route").asInt());
		assertEquals(42, bid.get("subscriptionId").asInt());
		assertEquals("BID", bid.get("field").asText());
		assertEquals(1, bid.get("code").asInt());
		assertEquals(501.25, bid.get("price").asDouble());
		JsonNode size = json(ev.get(1)[1]);
		assertEquals("ASK_SIZE", size.get("field").asText());
		assertEquals(200.0, size.get("size").asDouble());
		assertEquals("LAST", json(ev.get(2)[1]).get("field").asText());
		assertEquals(4, json(ev.get(2)[1]).get("code").asInt());
	}

	@Test
	void optionComputationsMapSentinelsToNull() throws Exception {
		callbacks.tickOptionComputation(42, 13, 0, 0.2, 0.5, 3.1, 0, 0.01, 0.2, -0.1, 501.2);
		callbacks.tickOptionComputation(42, 10, 0, -1, -2, -1, 0, Double.MAX_VALUE, -2, -2, -1);
		callbacks.tickOptionComputation(42, 53, 0, 0.2, 0.5, 3.1, 0, 0.01, 0.2, -0.1, 501.2); // not an option field
		List<String[]> ev = drain();
		assertEquals(2, ev.size());
		JsonNode model = json(ev.get(0)[1]);
		assertEquals("MODEL_OPTION", model.get("field").asText());
		assertEquals(0.5, model.get("option").get("delta").asDouble());
		assertEquals(-0.1, model.get("option").get("theta").asDouble());
		assertEquals(501.2, model.get("option").get("underlyingPrice").asDouble());
		JsonNode empty = json(ev.get(1)[1]).get("option");
		assertEquals(0, empty.size(), empty.toString());
	}

	@Test
	void lastTimestampBecomesIsoTime() throws Exception {
		callbacks.tickString(42, 45, "1790000000");
		callbacks.tickString(42, 32, "ignored");
		List<String[]> ev = drain();
		assertEquals(1, ev.size());
		assertEquals("LAST_TIMESTAMP", json(ev.get(0)[1]).get("field").asText());
		assertEquals("2026-09-21T14:13:20Z", json(ev.get(0)[1]).get("time").asText());
	}

	@Test
	void snapshotCompletesOnWantedFieldWithoutPublishing() throws Exception {
		BridgeState.Snapshot snap = new BridgeState.Snapshot(QuoteFields.LAST);
		state.snapshots.put(50, snap);
		callbacks.tickPrice(50, 1, 10.0, new TickAttrib());
		assertFalse(snap.done.isDone());
		callbacks.tickPrice(50, 4, 10.5, new TickAttrib());
		assertTrue(snap.done.isDone());
		assertEquals(10.0, snap.done.get().bid());
		assertEquals(10.5, snap.done.get().last());
		assertTrue(drain().isEmpty());
	}

	@Test
	void snapshotEndCompletesWithWhatArrived() throws Exception {
		BridgeState.Snapshot snap = new BridgeState.Snapshot(QuoteFields.CLOSE);
		state.snapshots.put(51, snap);
		callbacks.tickPrice(51, 2, 11.0, new TickAttrib());
		callbacks.tickSnapshotEnd(51);
		assertEquals(11.0, snap.done.get().ask());
		assertNull(snap.done.get().close());
	}

	@Test
	void snapshotSubscriptionEndsWithEvent() throws Exception {
		state.subscriptions.put(60, new SubscriptionRequest(Instrument.stock("QQQ", "SMART", "USD"), 5, true, null));
		callbacks.tickSnapshotEnd(60);
		List<String[]> ev = drain();
		assertEquals("snapshot-end", ev.get(0)[0]);
		assertEquals(5, json(ev.get(0)[1]).get("route").asInt());
		assertFalse(state.subscriptions.containsKey(60));
	}

	@Test
	void historyCollectsBarsUntilEnd() throws Exception {
		BridgeState.History h = new BridgeState.History();
		state.histories.put(70, h);
		callbacks.historicalData(70, new com.ib.client.Bar("20261001", 1, 2, 0.5, 1.5,
				Decimal.get(BigDecimal.valueOf(1000)), 10, Decimal.get(BigDecimal.valueOf(1.2))));
		callbacks.historicalData(70, new com.ib.client.Bar("20261002", 1.5, 2.5, 1, 2, Decimal.INVALID, 5, Decimal.INVALID));
		assertFalse(h.done.isDone());
		callbacks.historicalDataEnd(70, "", "");
		var bars = h.done.get().bars();
		assertEquals(2, bars.size());
		assertEquals("20261001", bars.get(0).time());
		assertEquals(1000.0, bars.get(0).volume());
		assertEquals(1.2, bars.get(0).wap());
		assertNull(bars.get(1).volume());
	}

	@Test
	void contractDetailsAndNoSecurity() throws Exception {
		BridgeState.Details d = new BridgeState.Details();
		state.details.put(80, d);
		ContractDetails cd = new ContractDetails();
		cd.contract(Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")));
		cd.marketName("SPY");
		callbacks.contractDetails(80, cd);
		callbacks.contractDetailsEnd(80);
		assertEquals("SPY", d.done.get().get(0).marketName());

		BridgeState.Details none = new BridgeState.Details();
		state.details.put(81, none);
		callbacks.error(81, 0L, 200, "No security definition has been found for the request", null);
		assertTrue(none.done.get().isEmpty());
	}

	@Test
	void historyErrorFailsPendingAndPacingIsRetryable() throws Exception {
		BridgeState.History h = new BridgeState.History();
		state.histories.put(90, h);
		callbacks.error(90, 0L, 162, "Historical Market Data Service error message:API historical data query cancelled", null);
		ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, h.done::get);
		assertEquals(502, ((ApiException) e.getCause()).status());

		BridgeState.History p = new BridgeState.History();
		state.histories.put(91, p);
		callbacks.error(91, 0L, 162, "Historical Market Data Service error message:Historical data request pacing violation", null);
		ApiException pacing = (ApiException) org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, p.done::get).getCause();
		assertEquals(429, pacing.status());
		assertTrue(pacing.retryable());
		List<String[]> ev = drain();
		assertEquals("error", ev.get(1)[0]);
		assertTrue(json(ev.get(1)[1]).get("retryable").asBoolean());
	}

	@Test
	void ordersStatusAndFillsUseClientOrderId() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, 4, Instrument.stock("SPY", "SMART", "USD")));
		callbacks.orderStatus(7, "Filled", Decimal.get(BigDecimal.valueOf(100)), Decimal.ZERO, 500.1, 99L, 0, 500.1, 11, null, 0);
		Execution e = new Execution(7, 11, "0001.01", "20261002 10:00:00 US/Eastern", "DU1234567", "ARCA", "BOT",
				Decimal.get(BigDecimal.valueOf(100)), 500.1, 99L, 0, Decimal.get(BigDecimal.valueOf(100)), 500.1, "INV",
				"", 0, "", null, false, null, null);
		callbacks.execDetails(-1, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), e);
		callbacks.orderStatus(8, "Submitted", Decimal.ZERO, Decimal.ZERO, 0, 0, 0, 0, 11, null, 0); // not ours
		callbacks.error(7, 0L, 201, "Order rejected - reason:not enough margin", null);
		List<String[]> ev = drain();
		assertEquals(3, ev.size());
		JsonNode status = json(ev.get(0)[1]);
		assertEquals("order-status", ev.get(0)[0]);
		assertEquals(7001, status.get("clientOrderId").asLong());
		assertEquals("Filled", status.get("status").asText());
		assertEquals(100.0, status.get("filled").asDouble());
		assertEquals(4, status.get("route").asInt());
		assertNull(status.get("parentId"));
		JsonNode fill = json(ev.get(1)[1]);
		assertEquals("fill", ev.get(1)[0]);
		assertEquals("BOUGHT", fill.get("side").asText());
		assertEquals("0001.01", fill.get("fillId").asText());
		assertEquals("SPY", fill.get("instrument").get("symbol").asText());
		assertFalse(ev.get(1)[1].contains("DU1234567"), "account ids never leave the bridge");
		JsonNode err = json(ev.get(2)[1]);
		assertEquals(7001, err.get("clientOrderId").asLong());
		assertNull(err.get("requestId"));
		assertEquals(201, err.get("code").asInt());
	}

	@Test
	void sessionEvents() throws Exception {
		AtomicBoolean closed = new AtomicBoolean();
		AtomicBoolean dataLost = new AtomicBoolean();
		callbacks.listener(new IbCallbacks.SessionListener() {
			@Override public void closed() { closed.set(true); }
			@Override public void restored(boolean lost) { dataLost.set(lost); }
		});
		state.connected(true, 222);
		callbacks.managedAccounts("DU1234567");
		callbacks.nextValidId(10);
		callbacks.nextValidId(5);
		assertEquals(10, state.nextOrderId.get());
		callbacks.error(-1, 0L, 2104, "Market data farm connection is OK:usfarm", null);
		callbacks.error(-1, 0L, 2105, "HMDS data farm connection is broken:ushmds", null);
		var status = state.status(false);
		assertEquals("DU*****67", status.accounts());
		assertEquals(Boolean.TRUE, status.dataFarms().get("usfarm"));
		assertEquals(Boolean.FALSE, status.dataFarms().get("ushmds"));
		callbacks.error(-1, 0L, 1101, "Connectivity between IB and TWS has been restored- data lost.", null);
		assertTrue(dataLost.get());

		BridgeState.Snapshot pending = new BridgeState.Snapshot(1);
		state.snapshots.put(5, pending);
		callbacks.connectionClosed();
		assertTrue(closed.get());
		assertFalse(state.connected());
		assertEquals(-1, state.nextOrderId.get());
		assertTrue(pending.done.isCompletedExceptionally());
		List<String[]> ev = drain();
		String[] last = ev.get(ev.size() - 1);
		assertEquals("connection", last[0]);
		assertFalse(json(last[1]).get("connected").asBoolean());
		assertNotNull(json(last[1]).get("since"));
		assertNotNull(json(last[1]).get("startedAt"));
	}
}
