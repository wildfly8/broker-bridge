package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), java.time.Instant.now().toString()));
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
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), java.time.Instant.now().toString()));
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7002, 11, "3", Instrument.stock("SPY", "SMART", "USD"), java.time.Instant.now().toString()));
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
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), java.time.Instant.now().toString()));
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
		hub.eventLog().recordOrder(new EventLog.OrderRecord(7001, 10, "3", Instrument.stock("SPY", "SMART", "USD"), java.time.Instant.now().toString()));
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

	/**
	 * A failure between the handshake and the marking up used to leave the socket open: the loop connects only when the client says it is not
	 * connected, so the session was never marked up, every request got a 503, and nothing tried again until the Gateway closed the socket.
	 */
	@Test
	void aFailureAfterTheHandshakeClosesTheSocketAndTheNextAttemptConnects() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(800), Duration.ofMillis(800));
		callbacks.listener(conn);
		java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
		ib.onOpenOrders = () -> {
			if (asked.incrementAndGet() == 1) {
				callbacks.nextValidId(10);
				throw new IllegalStateException("the first reading fails");
			}
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(() -> ib.calls.contains("disconnect"));
			assertFalse(ib.isConnected(), "the socket is closed");
			assertFalse(state.connected());
			assertNull(state.sync, "no reading is left armed");
			waitFor(() -> state.nextOrderId.get() == -1);
			assertEquals(1, ib.connectAttempts, "the next attempt waits the backoff");
			waitFor(state::connected);
			assertEquals(2, ib.connectAttempts);
			assertEquals(2, asked.get());
			assertTrue(ib.isConnected());
		} finally {
			conn.stop();
		}
	}

	/** A failure after the session is marked up leaves the session alone: only a socket without a marked-up session is closed. */
	@Test
	void aFailureAfterTheSessionIsMarkedUpLeavesTheSessionAlone() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		java.util.concurrent.atomic.AtomicInteger versionAsked = new java.util.concurrent.atomic.AtomicInteger();
		FakeIbClient ib = new FakeIbClient() {
			/** Asked once to mark the session up and once for the log line that follows: the second answer fails. */
			@Override
			public int serverVersion() {
				if (versionAsked.incrementAndGet() == 2) {
					throw new IllegalStateException("the second ask fails");
				}
				return super.serverVersion();
			}
		};
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		EventHub.Client events = hub.register();
		conn.start();
		try {
			waitFor(() -> versionAsked.get() >= 2);
			Thread.sleep(250);
			assertTrue(state.connected(), "the session was marked up before the failure");
			assertTrue(ib.isConnected());
			assertEquals(1, ib.connectAttempts, "a marked-up session is not reconnected");
			assertEquals(0, ib.count("disconnect"));
			assertTrue(drainUntilConnected(hub, events).contains("\"connected\":true"), "and its clients were told, before the line of the log that failed");
		} finally {
			conn.stop();
		}
	}

	/** IB's client fails in the same way at every call a setup makes; whichever it is, the socket is closed and the next attempt connects. */
	private static class FailOnce extends FakeIbClient {
		final String point;
		final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();

		FailOnce(String point) {
			this.point = point;
		}

		private void maybe(String at) {
			if (at.equals(point) && failed.compareAndSet(false, true)) {
				throw new IllegalStateException("fails at " + at);
			}
		}

		@Override public void reqMarketDataType(int type) { maybe("reqMarketDataType"); super.reqMarketDataType(type); }
		@Override public void reqIds() { maybe("reqIds"); super.reqIds(); }
		@Override public void reqOpenOrders() { maybe("reqOpenOrders"); super.reqOpenOrders(); }
		@Override public void reqCompletedOrders(boolean apiOnly) { maybe("reqCompletedOrders"); super.reqCompletedOrders(apiOnly); }
		@Override public void reqExecutions(int reqId, int clientId) { maybe("reqExecutions"); super.reqExecutions(reqId, clientId); }
		@Override public int serverVersion() { maybe("serverVersion"); return super.serverVersion(); }
	}

	/**
	 * The failure of one call after the handshake is not the only one: the setup asks IB for the market-data type, the order ids, the open and the
	 * completed orders and the executions, and marks the session up with the server version. A socket that stays open at any of them is never tried
	 * again, and a client that reports the closing of the socket to its callbacks, as IB's does, must not make the loop forget its own state.
	 */
	@Test
	void aFailureAtAnyPointOfTheSetupClosesTheSocketAndTheNextAttemptConnects() throws Exception {
		for (String point : new String[] { "reqMarketDataType", "reqIds", "reqOpenOrders", "reqCompletedOrders", "reqExecutions", "serverVersion" }) {
			BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
			BridgeState state = new BridgeState();
			EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
			FailOnce ib = new FailOnce(point);
			IbCallbacks callbacks = new IbCallbacks(state, hub, false);
			BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
			IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(800), Duration.ofMillis(800));
			callbacks.listener(conn);
			ib.onDisconnect = callbacks::connectionClosed;
			ib.onOpenOrders = () -> {
				callbacks.openOrderEnd();
				callbacks.completedOrdersEnd();
			};

			conn.start();
			try {
				waitFor(() -> ib.calls.contains("disconnect"));
				assertFalse(ib.isConnected(), "the socket is closed after a failure at " + point);
				assertFalse(state.connected(), point);
				assertNull(state.sync, "no reading is left armed: " + point);
				assertEquals(1, ib.connectAttempts, "the next attempt waits the backoff: " + point);
				waitFor(state::connected);
				assertEquals(2, ib.connectAttempts, point);
				assertTrue(ib.isConnected(), point);
			} finally {
				conn.stop();
			}
		}
	}

	/** A client that cannot say whether its socket is open is taken for open: closing a socket that is not open does no harm, leaving one open does. */
	@Test
	void aClientThatCannotSayWhetherItsSocketIsOpenIsClosedAfterAFailure() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		java.util.concurrent.atomic.AtomicBoolean cannotSay = new java.util.concurrent.atomic.AtomicBoolean();
		FakeIbClient ib = new FakeIbClient() {
			@Override
			public boolean isConnected() {
				if (cannotSay.compareAndSet(true, false)) {
					throw new IllegalStateException("the client cannot say");
				}
				return super.isConnected();
			}
		};
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(800), Duration.ofMillis(800));
		callbacks.listener(conn);
		java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
		ib.onOpenOrders = () -> {
			if (asked.incrementAndGet() == 1) {
				cannotSay.set(true);
				throw new IllegalStateException("the first reading fails");
			}
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(() -> ib.calls.contains("disconnect"));
			assertFalse(ib.isConnected());
			waitFor(state::connected);
			assertEquals(2, ib.connectAttempts);
		} finally {
			conn.stop();
		}
	}

	/** What goes wrong while the failure is being handled does not end the loop: an Error from the closing of the socket is logged, and the next attempt connects. */
	@Test
	void aFailureWhileTheSocketIsBeingClosedDoesNotEndTheLoop() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(100), Duration.ofMillis(100));
		callbacks.listener(conn);
		java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicBoolean closeFails = new java.util.concurrent.atomic.AtomicBoolean(true);
		ib.onDisconnect = () -> {
			if (closeFails.compareAndSet(true, false)) {
				throw new StackOverflowError("an error, not an exception");
			}
		};
		ib.onOpenOrders = () -> {
			if (asked.incrementAndGet() == 1) {
				throw new IllegalStateException("the first reading fails");
			}
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(state::connected);
			assertEquals(2, ib.connectAttempts, "the loop went on after the failure of the closing");
			assertFalse(closeFails.get());
		} finally {
			conn.stop();
		}
	}

	/** IB's error 1100 reported while the orders are read is not erased by marking the session up: the link is down until 1101 or 1102. */
	@Test
	void aLossOfIbsLinkReportedWhileTheOrdersAreReadStandsWhenTheSessionIsMarkedUp() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		EventHub.Client events = hub.register();
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			callbacks.error(-1, 0L, 1100, "Connectivity between IBKR and Trader Workstation has been lost.", null);
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(state::connected);
			assertFalse(state.linkUp(), "orders must not go out while IB's link is down");
			assertFalse(broker.status().linkUp());
			String seen = drainUntilConnected(hub, events);
			String announced = seen.substring(seen.lastIndexOf("\"connected\":true"));
			assertTrue(announced.contains("\"linkUp\":false"), "the connection event says so: " + seen);
			callbacks.error(-1, 0L, 1102, "Connectivity between IB and TWS has been restored- data maintained.", null);
			assertTrue(state.linkUp());
		} finally {
			conn.stop();
		}
	}

	/** The same loss, reported in the window between the handshake and the reading of the orders. */
	@Test
	void aLossOfIbsLinkReportedRightAfterTheHandshakeStandsWhenTheSessionIsMarkedUp() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onConnect = () -> callbacks.error(-1, 0L, 1100, "Connectivity between IBKR and Trader Workstation has been lost.", null);
		ib.onOpenOrders = () -> {
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(state::connected);
			assertFalse(state.linkUp(), "orders must not go out while IB's link is down");
		} finally {
			conn.stop();
		}
	}

	/** A failure while there is no socket has nothing to close, and does not end the loop. */
	@Test
	void aFailureBeforeTheSocketIsOpenClosesNothingAndTheLoopGoesOn() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		FakeIbClient ib = new FakeIbClient() {
			@Override
			public boolean connect(String host, int port, int clientId) {
				if (attempts.incrementAndGet() == 1) {
					throw new AssertionError("an error, not an exception: nothing catches it before the loop does");
				}
				return super.connect(host, port, clientId);
			}
		};
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};

		conn.start();
		try {
			waitFor(state::connected);
			assertEquals(2, attempts.get());
			assertEquals(0, ib.count("disconnect"), "there was no socket to close");
		} finally {
			conn.stop();
		}
	}

	/** What an earlier attempt was told about the link says nothing about the next session: it starts with the link up. */
	@Test
	void aSessionStartsWithTheLinkUpWhateverAnEarlierAttemptWasTold() throws Exception {
		BridgeConfig config = BridgeConfig.fromEnv(java.util.Map.of());
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		FakeIbClient ib = new FakeIbClient();
		IbCallbacks callbacks = new IbCallbacks(state, hub, false);
		BrokerService broker = new BrokerService(ib, state, false, hub.eventLog());
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(20), Duration.ofMillis(80));
		callbacks.listener(conn);
		ib.onOpenOrders = () -> {
			callbacks.openOrderEnd();
			callbacks.completedOrdersEnd();
		};
		state.link(false);

		conn.start();
		try {
			waitFor(state::connected);
			assertTrue(state.linkUp());
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
		IbConnection conn = new IbConnection(config, ib, state, hub, broker, Duration.ofMillis(800), Duration.ofMillis(800));
		callbacks.listener(conn);
		ib.onConnect = () -> callbacks.error(-1, 0L, 502, "Couldn't connect to TWS", null);

		conn.start();
		try {
			waitFor(() -> ib.connectAttempts >= 1);
			Thread.sleep(120);
			assertEquals(1, ib.connectAttempts, "a 502 must not open another API client in the same breath");
			assertFalse(state.connected());
			assertTrue(ib.calls.contains("disconnect"), ib.calls.toString());
			waitFor(() -> ib.connectAttempts >= 2);
			assertTrue(ib.connectAttempts <= 3, "attempts after the backoff: " + ib.connectAttempts);
			assertFalse(state.connected());
		} finally {
			conn.stop();
		}
	}
}
