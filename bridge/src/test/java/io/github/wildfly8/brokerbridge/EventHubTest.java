package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EventHubTest {

	@Test
	void onlyOrderEventsAreKeptForReplayAndMarkedAsSuch() throws Exception {
		EventHub hub = new EventHub(EventLog.inMemory(100, System.currentTimeMillis()));
		EventHub.Client c = hub.register();
		hub.publish("connection", Map.of("connected", true));
		hub.publish("quote", Map.of("price", 1.0));
		hub.publishOrderEvent("order-status", Map.of("clientOrderId", 1, "status", "Submitted"));
		EventHub.Frame connection = hub.next(c, 100);
		EventHub.Frame quote = hub.next(c, 100);
		EventHub.Frame order = hub.next(c, 100);
		assertFalse(connection.kept());
		assertFalse(quote.kept());
		assertTrue(order.kept());
		assertEquals(1, hub.eventLog().after(0).size());
		assertFalse(hub.next(c, 10).kept(), "a heartbeat is no order event");
	}

	/** MTS DDD review O15 (002 T046): a connection event published between the registration and the replay's read was dropped. */
	@Test
	void aLiveFrameIsSkippedAfterAResumeOnlyWhenTheReplayHasSentIt() {
		long replayedUpTo = 100;
		assertTrue(EventHub.alreadyReplayed(new EventHub.Frame(100, "", true), replayedUpTo));
		assertTrue(EventHub.alreadyReplayed(new EventHub.Frame(90, "", true), replayedUpTo), "an order event of the replayed range");
		assertFalse(EventHub.alreadyReplayed(new EventHub.Frame(101, "", true), replayedUpTo), "an order event after the replay is live");
		assertFalse(EventHub.alreadyReplayed(new EventHub.Frame(95, "", false), replayedUpTo), "a connection event or a quote is not in the replay, whatever its id");
		assertFalse(EventHub.alreadyReplayed(new EventHub.Frame(0, EventHub.HEARTBEAT, false), replayedUpTo));
		assertFalse(EventHub.alreadyReplayed(new EventHub.Frame(50, "", true), 0), "nothing was replayed");
	}
}
