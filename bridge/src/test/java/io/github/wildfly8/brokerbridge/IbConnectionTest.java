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
		state.subscriptions.put(42, new SubscriptionRequest(Instrument.stock("SPY", "SMART", "USD"), "1", false, "100"));
		state.subscriptions.put(43, new SubscriptionRequest(Instrument.stock("QQQ", "SMART", "USD"), "1", true, null));

		ib.refuseConnect = true;
		conn.start();
		try {
			waitFor(() -> ib.connectAttempts >= 3);
			assertFalse(state.connected());
			ib.refuseConnect = false;
			waitFor(state::connected);
			assertTrue(ib.calls.contains("connect 127.0.0.1:4002 11"));
			assertTrue(ib.calls.contains("marketDataType 3"));
			assertTrue(ib.calls.contains("reqIds"));
			assertTrue(ib.calls.stream().anyMatch(c -> c.matches("executions \\d+ 11")), "fills made while disconnected are requested: " + ib.calls);
			assertTrue(ib.calls.contains("mktData 42 SPY [100] false"), ib.calls.toString());
			assertFalse(state.subscriptions.containsKey(43), "one-shot snapshots are not re-sent");

			// Gateway restart: socket drops, then comes back
			ib.connected = false;
			callbacks.connectionClosed();
			assertFalse(state.connected());
			waitFor(state::connected);
			assertEquals(2, ib.count("mktData 42 SPY"));

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
}
