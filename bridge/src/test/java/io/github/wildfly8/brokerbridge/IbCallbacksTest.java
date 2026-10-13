package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.ib.client.OrderState;
import com.ib.client.TickAttrib;

import io.github.wildfly8.brokerbridge.Model.Instrument;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

class IbCallbacksTest {

	private BridgeState state;
	private EventHub hub;
	private EventHub.Client events;
	private IbCallbacks callbacks;

	@BeforeEach
	void setUp() {
		state = new BridgeState();
		hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		events = hub.register();
		callbacks = new IbCallbacks(state, hub, false);
		state.subscriptions.put(42, new SubscriptionRequest(Instrument.stock("SPY", "SMART", "USD"), "3", false, null));
	}

	/** Drains published events as (name, data) pairs. */
	private List<String[]> drain() throws Exception {
		List<String[]> out = new ArrayList<>();
		String f;
		while (!(f = hub.next(events, 1).text()).equals(EventHub.HEARTBEAT)) {
			String[] lines = f.split("\n");
			int i = lines[0].startsWith("id: ") ? 1 : 0;
			out.add(new String[] { lines[i].substring("event: ".length()), lines[i + 1].substring("data: ".length()) });
		}
		return out;
	}

	private static JsonNode json(String s) throws Exception {
		return Json.MAPPER.readTree(s);
	}

	@Test
	void quotesCarryTagFieldAndCode() throws Exception {
		callbacks.tickPrice(42, 1, 501.25, new TickAttrib());
		callbacks.tickSize(42, 3, Decimal.get(BigDecimal.valueOf(200)));
		callbacks.tickPrice(42, 68, 501.30, new TickAttrib()); // delayed last → LAST
		callbacks.tickPrice(99, 1, 1.0, new TickAttrib()); // unknown request: ignored
		List<String[]> ev = drain();
		assertEquals(3, ev.size());
		JsonNode bid = json(ev.get(0)[1]);
		assertEquals("quote", ev.get(0)[0]);
		assertEquals("3", bid.get("tag").asText());
		assertEquals(42, bid.get("subscriptionId").asInt());
		assertEquals("BID", bid.get("field").asText());
		assertEquals(1, bid.get("code").asInt());
		assertEquals(501.25, bid.get("price").asDouble());
		JsonNode size = json(ev.get(1)[1]);
		assertEquals("ASK_SIZE", size.get("field").asText());
		assertEquals(200.0, size.get("size").asDouble());
		assertEquals("LAST", json(ev.get(2)[1]).get("field").asText());
		assertEquals(4, json(ev.get(2)[1]).get("code").asInt());
		assertFalse(bid.has("delayed"), "a live tick says nothing of delay");
		assertFalse(json(ev.get(1)[1]).has("delayed"));
		assertTrue(json(ev.get(2)[1]).get("delayed").asBoolean(), "a delayed-data code is told, whatever live field it is folded into");
	}

	@Test
	void everyKindOfDelayedTickIsToldAsDelayed() throws Exception {
		callbacks.tickPrice(42, 66, 501.2, new TickAttrib());                          // delayed bid
		callbacks.tickSize(42, 69, Decimal.get(BigDecimal.valueOf(300)));             // delayed bid size
		callbacks.tickString(42, 88, "1790000000");                                    // delayed last timestamp
		callbacks.tickOptionComputation(42, 83, 0, 0.3, 0.5, 2.0, 0, 0.1, 0.2, -0.1, 500.0); // delayed model computation
		callbacks.tickOptionComputation(42, 13, 0, 0.3, 0.5, 2.0, 0, 0.1, 0.2, -0.1, 500.0); // live model computation
		callbacks.tickString(42, 45, "1790000000");                                    // live last timestamp
		List<String[]> ev = drain();
		assertEquals(6, ev.size());
		for (int i = 0; i < 4; i++) {
			assertTrue(json(ev.get(i)[1]).get("delayed").asBoolean(), "event " + i + ": " + ev.get(i)[1]);
		}
		assertFalse(json(ev.get(4)[1]).has("delayed"), ev.get(4)[1]);
		assertFalse(json(ev.get(5)[1]).has("delayed"), ev.get(5)[1]);
		assertEquals("BID", json(ev.get(0)[1]).get("field").asText(), "and the field is the live one");
	}

	@Test
	void aSnapshotIsDelayedWhenAnyTickThatMadeItWasAndLiveOtherwise() throws Exception {
		BridgeState.Snapshot live = new BridgeState.Snapshot(null);
		state.snapshots.put(70, live);
		callbacks.tickPrice(70, 1, 500.0, new TickAttrib());
		callbacks.tickPrice(70, 2, 500.1, new TickAttrib());
		callbacks.tickSnapshotEnd(70);
		assertNull(live.done.get().delayed(), "all live ticks: the field is left out");
		BridgeState.Snapshot delayed = new BridgeState.Snapshot(null);
		state.snapshots.put(71, delayed);
		callbacks.tickPrice(71, 1, 500.0, new TickAttrib());
		callbacks.tickPrice(71, 67, 500.1, new TickAttrib());
		callbacks.tickPrice(71, 2, 500.2, new TickAttrib());
		callbacks.tickSnapshotEnd(71);
		assertEquals(Boolean.TRUE, delayed.done.get().delayed(), "one delayed tick of three");
		BridgeState.Snapshot onlyOne = new BridgeState.Snapshot(QuoteFields.LAST);
		state.snapshots.put(72, onlyOne);
		callbacks.tickPrice(72, 68, 500.3, new TickAttrib());
		assertEquals(Boolean.TRUE, onlyOne.done.get().delayed(), "the wanted field arrived as a delayed one");
		BridgeState.Snapshot ignored = new BridgeState.Snapshot(null);
		state.snapshots.put(73, ignored);
		callbacks.tickPrice(73, 6, 1.0, new TickAttrib());                              // a field the snapshot does not keep
		callbacks.tickPrice(73, 72, 1.0, new TickAttrib());                             // delayed high: not kept either
		callbacks.tickSnapshotEnd(73);
		assertNull(ignored.done.get().delayed(), "a tick the snapshot does not keep makes it neither live nor delayed");
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
		state.subscriptions.put(60, new SubscriptionRequest(Instrument.stock("QQQ", "SMART", "USD"), "5", true, null));
		callbacks.tickSnapshotEnd(60);
		List<String[]> ev = drain();
		assertEquals("snapshot-end", ev.get(0)[0]);
		assertEquals("5", json(ev.get(0)[1]).get("tag").asText());
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
		assertEquals(null, pacing.retryAfterSeconds());

		BridgeState.History w = new BridgeState.History();
		state.histories.put(92, w);
		callbacks.error(92, 0L, 162, "Historical data request pacing violation, wait 15 seconds", null);
		ApiException waited = (ApiException) org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, w.done::get).getCause();
		assertEquals(15, waited.retryAfterSeconds());
		List<String[]> ev = drain();
		assertEquals("error", ev.get(1)[0]);
		assertTrue(json(ev.get(1)[1]).get("retryable").asBoolean());
	}

	private static com.ib.client.Order withRef(String orderRef) {
		com.ib.client.Order o = new com.ib.client.Order();
		o.orderRef(orderRef);
		o.totalQuantity(Decimal.get(BigDecimal.valueOf(30)));
		return o;
	}

	/** IB's {@code OrderState} has a package-private constructor (its source is vendored unchanged): the broker's own reader makes it. */
	private static OrderState stateOf(String status) throws Exception {
		java.lang.reflect.Constructor<OrderState> c = OrderState.class.getDeclaredConstructor();
		c.setAccessible(true);
		OrderState st = c.newInstance();
		st.status(status);
		return st;
	}

	/** An order IB lists as open, which the journal does not know (a lost journal), is entered again from the reference the bridge sent with it. */
	@Test
	void anOpenOrderTheBridgeDoesNotKnowIsEnteredAgainFromItsOrderReference() throws Exception {
		callbacks.openOrder(41, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("INV#7001#4"), stateOf("Submitted"));
		assertEquals(7001L, state.ordersByIbId.get(41).clientOrderId());
		assertEquals("4", state.ordersByIbId.get(41).tag());
		assertEquals("SPY", state.ordersByIbId.get(41).instrument().symbol());
		assertEquals(41, state.ibIdByClientOrderId.get(7001L));
		assertEquals(42, state.orderIdFloor.get(), "the counter stays above an order the broker holds");
		assertEquals(1, hub.eventLog().orders().size(), "and the journal has it");
		assertEquals(41, hub.eventLog().orders().get(0).brokerOrderId());
		assertTrue(hub.eventLog().orders().get(0).placedAt() != null);
		// its status now reaches the client, with the tag
		callbacks.orderStatus(41, "Submitted", Decimal.ZERO, Decimal.get(BigDecimal.valueOf(30)), 0, 99L, 0, 0, 11, null, 0);
		List<String[]> ev = drain();
		assertEquals(1, ev.size());
		assertEquals("order-status", ev.get(0)[0]);
		assertEquals(7001, json(ev.get(0)[1]).get("clientOrderId").asInt());
		assertEquals("4", json(ev.get(0)[1]).get("tag").asText());
	}

	@Test
	void anOpenOrderOfAnotherShapeOrOfAnotherClientIsLeftAlone() throws Exception {
		callbacks.openOrder(41, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("INV"), stateOf("Submitted"));
		callbacks.openOrder(42, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef(null), stateOf("Submitted"));
		callbacks.openOrder(0, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("INV#7002#4"), stateOf("Submitted"));
		assertTrue(state.ordersByIbId.isEmpty(), "an older bridge's order, one without a reference, and an order the broker gives the id 0 (another client's)");
		assertTrue(hub.eventLog().orders().isEmpty());
		callbacks.orderStatus(41, "Submitted", Decimal.ZERO, Decimal.ZERO, 0, 0, 0, 0, 11, null, 0);
		assertTrue(drain().isEmpty(), "and its status is dropped as before");
	}

	@Test
	void aClientOrderIdThatBelongsToAnotherBrokerOrderIsNotEnteredASecondTime() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		state.ibIdByClientOrderId.put(7001L, 7);
		callbacks.openOrder(41, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("INV#7001#4"), stateOf("Submitted"));
		assertFalse(state.ordersByIbId.containsKey(41), "the order the bridge has stays the one with that client order id");
		assertEquals(7, state.ibIdByClientOrderId.get(7001L));
		assertTrue(hub.eventLog().orders().isEmpty());
	}

	@Test
	void anOrderTheBridgeKnowsIsNotEnteredAgainAndIsNoted() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		state.ibIdByClientOrderId.put(7001L, 7);
		OrderSync sync = new OrderSync(List.of());
		state.sync = sync;
		callbacks.openOrder(7, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("INV#9999#9"), stateOf("Submitted"));
		assertEquals(7001L, state.ordersByIbId.get(7).clientOrderId(), "the record the bridge has wins over the reference");
		assertTrue(hub.eventLog().orders().isEmpty(), "nothing new to journal");
		callbacks.openOrderEnd();
		callbacks.completedOrdersEnd();
		assertTrue(sync.openEnded());
		callbacks.openOrderEnd(); // no reading under way: nothing happens
		state.sync = null;
		callbacks.openOrderEnd();
		callbacks.completedOrdersEnd();
	}

	@Test
	void theOrdersTheBrokerListsAsCompletedAreNotedByTheirReference() throws Exception {
		OrderSync sync = new OrderSync(List.of(new EventLog.OrderRecord(7001, 7, "4", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z")));
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		state.sync = sync;
		com.ib.client.Order done = withRef("INV#7001#4");
		done.filledQuantity(Decimal.get(BigDecimal.valueOf(30)));
		callbacks.completedOrder(Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), done, stateOf("Filled"));
		callbacks.completedOrder(Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), withRef("not ours"), stateOf("Filled"));
		callbacks.openOrderEnd();
		callbacks.completedOrdersEnd();
		assertTrue(sync.await(50, 50));
		assertEquals(1, sync.conclude(state, hub));
		List<String[]> ev = drain();
		assertEquals("Filled", json(ev.get(0)[1]).get("status").asText());
		assertEquals(30.0, json(ev.get(0)[1]).get("filled").asDouble());
		assertEquals(0.0, json(ev.get(0)[1]).get("remaining").asDouble());
	}

	/** After a lost journal an execution of an order the bridge has no record of finds its order in the reference the broker returns with it. */
	@Test
	void anExecutionOfAnOrderTheBridgeHasNoRecordOfIsDeliveredFromItsOrderReference() throws Exception {
		Execution e = new Execution(7, 11, "0003.01", "20261002 10:00:00 US/Eastern", "DU1234567", "ARCA", "BOT",
				Decimal.get(BigDecimal.valueOf(100)), 500.1, 99L, 0, Decimal.get(BigDecimal.valueOf(100)), 500.1, "INV#7001#4",
				"", 0, "", null, false, null, null);
		callbacks.execDetails(-1, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), e);
		List<String[]> ev = drain();
		assertEquals(1, ev.size());
		assertEquals("fill", ev.get(0)[0]);
		assertEquals(7001, json(ev.get(0)[1]).get("clientOrderId").asInt());
		assertEquals("4", json(ev.get(0)[1]).get("tag").asText());
		assertEquals("SPY", json(ev.get(0)[1]).get("instrument").get("symbol").asText());
		assertEquals(7001L, state.ordersByIbId.get(7).clientOrderId());
		// an execution of another shape is dropped, and its fill id is not used up
		Execution foreign = new Execution(8, 11, "0004.01", "20261002 10:00:00 US/Eastern", "DU1234567", "ARCA", "BOT",
				Decimal.get(BigDecimal.valueOf(1)), 500.1, 99L, 0, Decimal.get(BigDecimal.valueOf(1)), 500.1, "INV",
				"", 0, "", null, false, null, null);
		callbacks.execDetails(-1, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), foreign);
		assertTrue(drain().isEmpty());
		assertTrue(hub.eventLog().firstFill("0004.01"), "a fill that was dropped can still be delivered once its order is known");
	}

	@Test
	void ordersStatusAndFillsUseClientOrderId() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		callbacks.orderStatus(7, "Filled", Decimal.get(BigDecimal.valueOf(100)), Decimal.ZERO, 500.1, 99L, 0, 500.1, 11, null, 0);
		Execution e = new Execution(7, 11, "0001.01", "20261002 10:00:00 US/Eastern", "DU1234567", "ARCA", "BOT",
				Decimal.get(BigDecimal.valueOf(100)), 500.1, 99L, 0, Decimal.get(BigDecimal.valueOf(100)), 500.1, "ref-1",
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
		assertEquals("4", status.get("tag").asText());
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
	void theAnswerToACancelIsPublishedWithItsReasonButIsNotTheBridgesLastError() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		callbacks.error(7, 0L, 202, "Order Canceled - reason:Your order is not accepted because of insufficient margin", null);
		callbacks.error(7, 0L, 161, "Cancel attempted when order is not in a cancellable state", null);
		callbacks.error(7, 0L, 10148, "OrderId 7 that needs to be cancelled can not be cancelled, state: Filled.", null);
		List<String[]> ev = drain();
		assertEquals(3, ev.size(), "each is an error event of its order, with IB's words");
		assertEquals(202, json(ev.get(0)[1]).get("code").asInt());
		assertTrue(json(ev.get(0)[1]).get("message").asText().contains("insufficient margin"));
		assertNull(state.status(false).lastError(), "a cancel is no error of the bridge");
		callbacks.error(7, 0L, 201, "Order rejected - reason:not enough margin", null);
		assertEquals("201 Order rejected - reason:not enough margin", state.status(false).lastError(), "a rejection still is");
	}

	@Test
	void fillsAreDeliveredOnceAndKeptForReplay() throws Exception {
		state.ordersByIbId.put(7, new BridgeState.OrderRef(7001L, "4", Instrument.stock("SPY", "SMART", "USD")));
		Execution e = new Execution(7, 11, "0002.01", "20261002 10:00:00", "DU1234567", "ARCA", "SLD",
				Decimal.get(BigDecimal.valueOf(5)), 10.0, 1L, 0, Decimal.get(BigDecimal.valueOf(5)), 10.0, "ref-1",
				"", 0, "", null, false, null, null);
		callbacks.execDetails(-1, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), e);
		callbacks.execDetails(99, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), e); // again, from reqExecutions
		List<String[]> ev = drain();
		assertEquals(1, ev.size());
		assertEquals("fill", ev.get(0)[0]);
		List<EventLog.Entry> kept = hub.eventLog().after(0);
		assertEquals(1, kept.size());
		assertEquals("fill", kept.get(0).event());
		callbacks.tickPrice(42, 1, 1.0, new TickAttrib());
		assertEquals(1, hub.eventLog().after(0).size(), "quotes are not kept");
	}

	@Test
	void aSecond1100KeepsTheTimeOfTheFirstAndAnyRestoreEndsIt() {
		state.link(false);
		java.time.Instant first = state.linkDownSince();
		state.link(false);
		assertSame(first, state.linkDownSince(), "the link has been down since the first report");
		state.link(true);
		assertTrue(state.linkUp());
		assertNull(state.linkDownSince());
		state.link(true);
		assertTrue(state.linkUp(), "a restore without a loss is nothing");
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
		assertTrue(status.linkUp(), "up unless IB said otherwise");
		callbacks.error(-1, 0L, 1100, "Connectivity between IBKR and Trader Workstation has been lost.", null);
		assertFalse(state.status(false).linkUp(), "IB's own link to the broker's servers is lost though the socket is open");
		assertTrue(state.status(false).connected());
		assertEquals("1100 Connectivity between IBKR and Trader Workstation has been lost.", state.status(false).lastError());
		callbacks.error(-1, 0L, 1101, "Connectivity between IB and TWS has been restored- data lost.", null);
		assertTrue(dataLost.get());
		assertTrue(state.status(false).linkUp());

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
