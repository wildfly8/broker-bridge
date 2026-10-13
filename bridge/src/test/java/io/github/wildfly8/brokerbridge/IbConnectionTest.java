package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import io.github.wildfly8.brokerbridge.Model.Instrument;
import io.github.wildfly8.brokerbridge.Model.SubscriptionRequest;

class IbConnectionTest {

	private static void waitFor(BooleanSupplier cond) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!cond.getAsBoolean() && System.nanoTime() < until) {
			Thread.sleep(10);
		}
		assertTrue(cond.getAsBoolean());
	}

	@Test
	void retriesWithBackoffThenResubscribesAfterReconnect() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of("IB_MARKET_DATA_TYPE", "3"));
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		EventHub.Client events = hub.register();
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};
		state.subscriptions.put(42, new SubscriptionRequest(Instrument.stock("SPY", "SMART", "USD"), "1", false, "100"));
		state.subscriptions.put(43, new SubscriptionRequest(Instrument.stock("QQQ", "SMART", "USD"), "1", true, null));

		ib.refuseConnect = true;
		conn.start();
		try {
			waitFor(() -> ib.connectAttempts >= 3);
			assertFalse(state.connected());
			ib.refuseConnect = false;
			waitFor(state::connected);
			waitFor(() -> ib.calls.contains("mktData 42 SPY [100] false"));
			assertTrue(ib.calls.contains("connect 127.0.0.1:4002 11"));
			assertTrue(ib.calls.contains("marketDataType 3"));
			assertTrue(ib.calls.contains("reqIds"));
			assertTrue(ib.calls.stream().anyMatch(c -> c.matches("executions \\d+ 11")), "fills made while disconnected are requested: " + ib.calls);
			assertFalse(state.subscriptions.containsKey(43), "one-shot snapshots are not re-sent");

			// Gateway restart: socket drops, then comes back
			ib.connected = false;
			callbacks.connectionClosed();
			assertFalse(state.connected());
			waitFor(state::connected);
			waitFor(() -> ib.count("mktData 42 SPY") == 2);

			StringBuilder seen = new StringBuilder();
			String f;
			while (!(f = hub.next(events, 1).text()).equals(EventHub.HEARTBEAT)) {
				seen.append(f);
			}
			assertTrue(seen.indexOf("\"connected\":false") > 0 && seen.lastIndexOf("\"connected\":true") > seen.indexOf("\"connected\":false"),
					seen.toString());
		} finally {
			conn.stop();
		}
	}

	private static String drainText(EventHub hub, EventHub.Client events) throws Exception {
		StringBuilder seen = new StringBuilder();
		String f;
		while (!(f = hub.next(events, 1).text()).equals(EventHub.HEARTBEAT)) {
			seen.append(f);
		}
		return seen.toString();
	}

	/** The events published so far, until the one that announces the connection as up has been read. */
	private static String drainUntilConnected(EventHub hub, EventHub.Client events) throws Exception {
		StringBuilder seen = new StringBuilder();
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!seen.toString().contains("\"connected\":true") && System.nanoTime() < until) {
			seen.append(drainText(hub, events));
		}
		return seen.toString();
	}

	/**
	 * The bridge asks the broker what it holds open and has completed (before the day's executions), and the session is marked up, and
	 * so takes orders, only when it has the answer; the order the journal had not seen end and the broker lists nowhere ends first.
	 */
	@Test
	void theSessionIsMarkedUpOnlyAfterTheBrokerHasListedItsOrdersAndWhatItDoesNotListHasEnded() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		EventHub.Client events = hub.register();
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		hub.eventLog().keepOrderEvent(hub.eventLog().nextId(), "order-status", java.util.Map.of("clientOrderId", 7001, "status", "Submitted"));
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);

		conn.start();
		try {
			waitFor(() -> ib.calls.contains("completedOrders true"));
			Thread.sleep(150);
			assertFalse(state.connected(), "the broker has not answered yet");
			int ids = ib.calls.indexOf("reqIds");
			int open = ib.calls.indexOf("openOrders");
			int completed = ib.calls.indexOf("completedOrders true");
			int executions = ib.calls.stream().filter(c -> c.startsWith("executions")).findFirst().map(ib.calls::indexOf).orElse(-1);
			assertTrue(ids >= 0 && ids < open && open < completed && completed < executions, "ids, open orders, completed orders, executions: " + ib.calls);
			callbacks.openOrderEnd();
			Thread.sleep(150);
			assertFalse(state.connected(), "the list of completed orders has not ended yet");
			callbacks.completedOrdersEnd();
			waitFor(state::connected);

			String seen = drainUntilConnected(hub, events);
			int inactive = seen.indexOf("\"status\":\"Inactive\"");
			int up = seen.lastIndexOf("\"connected\":true");
			assertTrue(inactive > 0, seen);
			assertTrue(seen.contains("\"whyHeld\":\"not among IB's open orders when the bridge connected\""), seen);
			assertTrue(up > inactive, "the order's end goes out before the connection is announced: " + seen);
			assertEquals(null, state.sync, "the reading is over");
		} finally {
			conn.stop();
		}
	}

	/** An order IB lists as open is still working: it is not given an end, and its status is the broker's own. */
	@Test
	void anOrderTheBrokerListsAsOpenIsNotConcludedToHaveEnded() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		EventHub.Client events = hub.register();
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7002, 11, "3", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		// from before the placement time was kept, and no status among the kept events: not concluded to have ended
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7003, 12, "3", Instrument.stock("SPY", "SMART", "USD")));
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			com.ib.client.Order working = new com.ib.client.Order();
			working.orderRef("INV#7001#3");
			callbacks.openOrder(10, Contracts.toIb(Instrument.stock("SPY", "SMART", "USD")), working, null);
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(state::connected);
			String seen = drainUntilConnected(hub, events);
			assertFalse(seen.contains("\"clientOrderId\":7001"), "7001 is open at IB: " + seen);
			assertTrue(seen.contains("\"clientOrderId\":7002") && seen.contains("Inactive"), "7002 is not listed: " + seen);
			assertFalse(seen.contains("\"clientOrderId\":7003"), "7003 is old and unseen: " + seen);
		} finally {
			conn.stop();
		}
	}

	@Test
	void aBrokerThatDoesNotEndItsListsIsWaitedForOnlyAShortTimeAndNothingIsConcluded() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		EventHub.Client events = hub.register();
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80), 100, 20);
		callbacks.listener(conn);

		conn.start();
		try {
			waitFor(state::connected);
			String seen = drainUntilConnected(hub, events);
			assertFalse(seen.contains("Inactive"), "no answer is no evidence: " + seen);
			assertTrue(seen.contains("\"connected\":true"), seen);
		} finally {
			conn.stop();
		}
	}

	@Test
	void aConnectionLostWhileTheListsAreReadEndsTheWaitAtOnce() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), "2026-10-13T12:00:00Z"));
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80), 60_000, 60_000);
		callbacks.listener(conn);
		java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
		ib.onOpenOrders = () -> {
			if (asked.incrementAndGet() >= 2) {
				callbacks.openOrderEnd();
				callbacks.completedOrdersEnd();
			}
		};

		conn.start();
		try {
			waitFor(() -> asked.get() == 1);
			assertFalse(state.connected());
			ib.connected = false;
			callbacks.connectionClosed();
			// the second reading comes long before the minute the first one was waited for, and the second answer is the one that counts
			waitFor(state::connected);
			assertEquals(2, asked.get());
			assertTrue(hub.eventLog().isFinal(7001), "the next reading ended the order");
		} finally {
			conn.stop();
		}
	}

	/** The Gateway's daily restart accepts the socket, then sends 502. The next attempt waits the backoff. */
	@Test
	void a502DuringTheHandshakeWaitsBeforeAnotherAttempt() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(100, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(300), Duration.ofMillis(300));
		callbacks.listener(conn);
		ib.onConnect = () -> callbacks.error(-1, 0L, 502, "Couldn't connect to TWS", null);

		conn.start();
		try {
			waitFor(() -> ib.connectAttempts >= 1);
			Thread.sleep(120);
			assertEquals(1, ib.connectAttempts, "a 502 must not open another API client in the same breath");
			assertFalse(state.connected());
			assertTrue(ib.calls.contains("disconnect"), ib.calls.toString());
			Thread.sleep(400);
			assertTrue(ib.connectAttempts >= 2 && ib.connectAttempts <= 3, "attempts after the backoff: " + ib.connectAttempts);
			assertFalse(state.connected());
		} finally {
			conn.stop();
		}
	}
}
