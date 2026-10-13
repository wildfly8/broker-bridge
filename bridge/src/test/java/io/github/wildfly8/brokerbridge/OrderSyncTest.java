package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.wildfly8.brokerbridge.BridgeState.OrderRef;
import io.github.wildfly8.brokerbridge.Model.Instrument;

/** The reading of the broker's open and completed orders at a connection: what it concludes of the orders the bridge had not seen end. */
class OrderSyncTest {

	private static final Instrument SPY = Instrument.stock("SPY", "SMART", "USD");

	private BridgeState state;
	private EventHub hub;
	private EventHub.Client events;

	@BeforeEach
	void setUp() {
		state = new BridgeState();
		hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		events = hub.register();
	}

	private EventLog.OrderRecord order(long id, int brokerId, String tag) {
		state.ordersByIbId.put(brokerId, new OrderRef(id, tag, SPY));
		state.ibIdByClientOrderId.put(id, brokerId);
		return new EventLog.OrderRecord(id, brokerId, tag, SPY, "2026-10-13T12:00:00Z");
	}

	private List<JsonNode> statuses() throws Exception {
		List<JsonNode> out = new ArrayList<>();
		String f;
		while (!(f = hub.next(events, 1).text()).equals(EventHub.HEARTBEAT)) {
			String[] lines = f.split("\n");
			int i = lines[0].startsWith("id: ") ? 1 : 0;
			if (lines[i].equals("event: order-status")) {
				out.add(Json.MAPPER.readTree(lines[i + 1].substring("data: ".length())));
			}
		}
		return out;
	}

	@Test
	void anOrderThatTheBrokerListsNeitherOpenNorCompletedIsInactiveWithTheReason() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		sync.openEnd();
		sync.completedEnd();
		assertTrue(sync.await(100, 100));
		assertEquals(1, sync.conclude(state, hub));
		List<JsonNode> s = statuses();
		assertEquals(1, s.size());
		assertEquals(7001, s.get(0).get("clientOrderId").asInt());
		assertEquals("Inactive", s.get(0).get("status").asText());
		assertEquals("not among IB's open orders when the bridge connected", s.get(0).get("whyHeld").asText());
		assertEquals("3", s.get(0).get("tag").asText(), "the event goes back to the listener that placed the order");
		assertTrue(hub.eventLog().isFinal(7001), "journaled like any order event");
	}

	@Test
	void anOrderThatIsStillOpenIsLeftToItsOwnStatus() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		sync.open(7001);
		sync.openEnd();
		sync.completedEnd();
		assertTrue(sync.await(100, 100));
		assertEquals(0, sync.conclude(state, hub));
		assertTrue(statuses().isEmpty());
	}

	@Test
	void anOrderTheBrokerListsAsCompletedGetsTheStatusItEndedWith() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3"), order(7002, 11, "3"), order(7003, 12, "4")));
		sync.completed(7001, "Filled", 30.0, 30.0);
		sync.completed(7002, "Cancelled", 10.0, 25.0);
		sync.completed(7003, "Submitted", 0.0, 5.0); // not an end: the broker's list is not believed for it
		sync.openEnd();
		sync.completedEnd();
		assertTrue(sync.await(100, 100));
		assertEquals(3, sync.conclude(state, hub));
		List<JsonNode> s = statuses();
		assertEquals("Filled", s.get(0).get("status").asText());
		assertEquals(30.0, s.get(0).get("filled").asDouble());
		assertEquals(0.0, s.get(0).get("remaining").asDouble());
		assertEquals("listed by IB as completed when the bridge connected", s.get(0).get("whyHeld").asText());
		assertEquals("Cancelled", s.get(1).get("status").asText());
		assertEquals(10.0, s.get(1).get("filled").asDouble());
		assertEquals(15.0, s.get(1).get("remaining").asDouble());
		assertEquals("Inactive", s.get(2).get("status").asText());
	}

	@Test
	void anOrderThatEndedWhileTheListsWereReadIsNotGivenAnotherStatus() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		hub.publishOrderEvent("order-status", Map.of("clientOrderId", 7001, "status", "Cancelled"));
		sync.openEnd();
		sync.completedEnd();
		assertEquals(0, sync.conclude(state, hub));
		assertEquals(1, statuses().size(), "only the live status");
	}

	@Test
	void nothingIsConcludedWhenTheListOfOpenOrdersDoesNotEnd() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		assertFalse(sync.await(30, 30), "no answer: the time is up");
		assertFalse(sync.openEnded());
		assertFalse(sync.await(30, 30));
	}

	@Test
	void theListOfOpenOrdersIsWaitedForEvenWhenTheListOfCompletedOnesEndedFirst() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		sync.completedEnd();
		Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(80);
			} catch (InterruptedException e) {
				return;
			}
			sync.openEnd();
		});
		assertTrue(sync.await(5_000, 100), "the open orders ended after 80 ms, within the time allowed");
		assertEquals(1, sync.conclude(state, hub));
	}

	@Test
	void aLostConnectionEndsTheWaitAtOnceAndConcludesNothing() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		sync.abort();
		long start = System.nanoTime();
		assertFalse(sync.await(10_000, 10_000));
		assertTrue(System.nanoTime() - start < 2_000_000_000L, "does not wait for the time to be up");
		sync.openEnd();
		assertFalse(sync.openEnded(), "an abort after or before the end: the connection is gone");
	}

	@Test
	void aListOfCompletedOrdersThatDoesNotEndStillAllowsTheConclusionFromTheOpenOnes() throws Exception {
		OrderSync sync = new OrderSync(List.of(order(7001, 10, "3")));
		sync.openEnd();
		assertTrue(sync.await(100, 30), "an older Gateway may not answer the request for completed orders");
		assertEquals(1, sync.conclude(state, hub));
		assertEquals("Inactive", statuses().get(0).get("status").asText());
	}
}
