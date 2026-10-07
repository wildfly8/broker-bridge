package io.github.wildfly8.brokerbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.TickAttrib;

class BridgeHttpServerTest {

	private static final String SPY = "{\"symbol\":\"SPY\",\"type\":\"STOCK\",\"exchange\":\"SMART\",\"currency\":\"USD\"}";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private BridgeState state;
	private FakeIbClient ib;
	private IbCallbacks callbacks;
	private EventHub hub;
	private BridgeHttpServer server;

	private void start(boolean ordersEnabled, boolean connected) throws Exception {
		state = new BridgeState();
		hub = new EventHub(EventLog.inMemory(1000, System.currentTimeMillis()));
		ib = new FakeIbClient();
		ib.connected = connected;
		state.connected(connected, connected ? 222 : null);
		callbacks = new IbCallbacks(state, hub, ordersEnabled);
		BrokerService broker = new BrokerService(ib, state, ordersEnabled, hub.eventLog());
		server = new BridgeHttpServer("127.0.0.1", 0, broker, hub, 200);
		server.start();
	}

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
				.timeout(Duration.ofSeconds(10));
		b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode json(HttpResponse<String> r) throws Exception {
		return Json.MAPPER.readTree(r.body());
	}

	@Test
	void statusRunsOnVirtualThread() throws Exception {
		start(false, true);
		state.accounts(Json.maskAccounts("DU1234567"));
		HttpResponse<String> r = send("GET", "/v1/status", null);
		assertEquals(200, r.statusCode());
		JsonNode s = json(r);
		assertTrue(s.get("connected").asBoolean());
		assertEquals(222, s.get("serverVersion").asInt());
		assertEquals("DU*****67", s.get("accounts").asText());
		assertFalse(s.get("ordersEnabled").asBoolean());
		assertTrue(BridgeHttpServer.lastHandlerVirtual, "handlers must run on virtual threads");
	}

	@Test
	void subscribeAndUnsubscribe() throws Exception {
		start(false, true);
		HttpResponse<String> r = send("POST", "/v1/subscriptions",
				"{\"instrument\":" + SPY + ",\"tag\":\"2\",\"genericTicks\":\"100,101,105\"}");
		assertEquals(200, r.statusCode(), r.body());
		int id = json(r).get("subscriptionId").asInt();
		assertTrue(ib.calls.contains("mktData " + id + " SPY [100,101,105] false"), ib.calls.toString());
		assertEquals(204, send("DELETE", "/v1/subscriptions/" + id, null).statusCode());
		assertTrue(ib.calls.contains("cancelMktData " + id));
		assertFalse(state.subscriptions.containsKey(id));
	}

	@Test
	void disconnectedIs503() throws Exception {
		start(true, false);
		HttpResponse<String> r = send("POST", "/v1/subscriptions", "{\"instrument\":" + SPY + "}");
		assertEquals(503, r.statusCode());
		assertTrue(json(r).get("retryable").asBoolean());
		assertEquals(503, send("POST", "/v1/orders", order(1L, false)).statusCode());
		assertEquals(0, ib.count("mktData"));
	}

	@Test
	void snapshot() throws Exception {
		start(false, true);
		ib.onMktData = (id, snapshot) -> {
			callbacks.tickPrice(id, 1, 500.0, new TickAttrib());
			callbacks.tickPrice(id, 2, 500.1, new TickAttrib());
			callbacks.tickSnapshotEnd(id);
		};
		HttpResponse<String> r = send("POST", "/v1/snapshots", "{\"instrument\":" + SPY + ",\"field\":\"ASK\"}");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(500.0, json(r).get("bid").asDouble());
		assertEquals(500.1, json(r).get("ask").asDouble());
		assertTrue(state.snapshots.isEmpty());
	}

	@Test
	void snapshotTimesOut() throws Exception {
		start(false, true);
		HttpResponse<String> r = send("POST", "/v1/snapshots", "{\"instrument\":" + SPY + ",\"timeoutMs\":100}");
		assertEquals(504, r.statusCode());
	}

	@Test
	void history() throws Exception {
		start(false, true);
		ib.onHistory = (id, c) -> {
			callbacks.historicalData(id, new com.ib.client.Bar("20261002", 1, 2, 0.5, 1.5,
					Decimal.get(BigDecimal.valueOf(1000)), 10, Decimal.get(BigDecimal.valueOf(1.2))));
			callbacks.historicalDataEnd(id, "", "");
		};
		HttpResponse<String> r = send("POST", "/v1/history",
				"{\"instrument\":" + SPY + ",\"end\":\"20261003 16:00:00\",\"duration\":\"10 Y\"}");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals(1, json(r).get("bars").size());
		assertTrue(ib.calls.stream().anyMatch(c -> c.endsWith("SPY 20261003 16:00:00|10 Y|1 day|TRADES|true")),
				ib.calls.toString());
	}

	@Test
	void historyPacingNamesItsWait() throws Exception {
		start(false, true);
		ib.onHistory = (id, c) -> callbacks.error(id, 0L, 162,
				"Historical data request pacing violation, wait 15 seconds", null);
		HttpResponse<String> r = send("POST", "/v1/history", "{\"instrument\":" + SPY + "}");
		assertEquals(429, r.statusCode(), r.body());
		assertTrue(json(r).get("retryable").asBoolean());
		assertEquals("15", r.headers().firstValue("Retry-After").orElse(""));
	}

	@Test
	void resolveFoundAndNotFound() throws Exception {
		start(false, true);
		ib.onDetails = (id, c) -> {
			if ("SPY".equals(c.symbol())) {
				ContractDetails d = new ContractDetails();
				c.conid(756733);
				d.contract(c);
				d.marketName("SPY");
				callbacks.contractDetails(id, d);
				callbacks.contractDetailsEnd(id);
			} else {
				callbacks.error(id, 0L, 200, "No security definition has been found for the request", null);
			}
		};
		HttpResponse<String> r = send("POST", "/v1/contracts/resolve", "{\"instrument\":" + SPY + "}");
		assertEquals(200, r.statusCode(), r.body());
		assertEquals("756733", json(r).get("instrument").get("brokerId").asText());
		assertEquals(404, send("POST", "/v1/contracts/resolve",
				"{\"instrument\":{\"symbol\":\"NOPE\",\"type\":\"STOCK\"}}").statusCode());
	}

	private static String order(long clientOrderId, boolean modify) {
		return "{\"clientOrderId\":" + clientOrderId + ",\"tag\":\"4\",\"instrument\":" + SPY
				+ ",\"side\":\"BUY\",\"quantity\":100,\"type\":\"LIMIT\",\"limitPrice\":500.0,\"timeInForce\":\"DAY\""
				+ (modify ? ",\"modify\":true" : "") + "}";
	}

	@Test
	void ordersRefusedWhenDisabled() throws Exception {
		start(false, true);
		state.nextOrderId.set(10);
		HttpResponse<String> r = send("POST", "/v1/orders", order(7001L, false));
		assertEquals(403, r.statusCode());
		assertTrue(json(r).get("error").asText().contains("BRIDGE_ORDERS_ENABLED"));
		assertEquals(403, send("DELETE", "/v1/orders/7001", null).statusCode());
		assertEquals(0, ib.count("placeOrder"));
		assertEquals(0, ib.count("cancelOrder"));
	}

	@Test
	void placeModifyCancelWhenEnabled() throws Exception {
		start(true, true);
		assertEquals(503, send("POST", "/v1/orders", order(7001L, false)).statusCode(), "no order id from IB yet");
		state.nextOrderId.set(10);
		HttpResponse<String> r = send("POST", "/v1/orders", order(7001L, false));
		assertEquals(202, r.statusCode(), r.body());
		assertEquals(7001, json(r).get("clientOrderId").asLong());
		assertTrue(ib.calls.contains("placeOrder 10 BUY 100 LMT 500.0"), ib.calls.toString());
		assertEquals(409, send("POST", "/v1/orders", order(7001L, false)).statusCode());
		assertEquals(202, send("POST", "/v1/orders", order(7001L, true)).statusCode());
		assertEquals(2, ib.calls.stream().filter(c -> c.startsWith("placeOrder 10 ")).count());
		assertEquals(404, send("POST", "/v1/orders", order(7002L, true)).statusCode());
		assertEquals(202, send("DELETE", "/v1/orders/7001", null).statusCode());
		assertTrue(ib.calls.contains("cancelOrder 10"));
		assertEquals(404, send("DELETE", "/v1/orders/9999", null).statusCode());
		assertEquals(11, state.nextOrderId.get());
	}

	@Test
	void badRequests() throws Exception {
		start(true, true);
		assertEquals(400, send("POST", "/v1/subscriptions", "{not json").statusCode());
		assertEquals(400, send("POST", "/v1/subscriptions", "{\"instrument\":{\"symbol\":\"X\",\"type\":\"BOND\"}}").statusCode());
		assertEquals(400, send("DELETE", "/v1/subscriptions/abc", null).statusCode());
		assertEquals(405, send("GET", "/v1/orders", null).statusCode());
		assertEquals(404, send("GET", "/v1/status/x", null).statusCode());
		assertEquals(404, send("GET", "/nothing", null).statusCode());
	}

	@Test
	void eventStreamDeliversStatusEventsAndHeartbeats() throws Exception {
		start(false, true);
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/events")).build();
		HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
		assertEquals(200, r.statusCode());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
			assertEquals("event: connection", in.readLine());
			assertTrue(in.readLine().contains("\"connected\":true"));
			assertEquals("", in.readLine());
			assertEquals("event: replay-complete", in.readLine());
			assertTrue(in.readLine().contains("\"replayed\":0"), "an empty replay still ends");
			assertEquals("", in.readLine());
			waitFor(() -> hub.clientCount() == 1);
			state.subscriptions.put(42, new Model.SubscriptionRequest(Model.Instrument.stock("SPY", "SMART", "USD"), "1", false, null));
			callbacks.tickPrice(42, 4, 501.0, new TickAttrib());
			assertTrue(in.readLine().matches("id: \\d+"), "every live event has an id");
			assertEquals("event: quote", in.readLine());
			assertTrue(in.readLine().contains("\"field\":\"LAST\""));
			assertEquals("", in.readLine());
			assertEquals(": heartbeat", in.readLine());
		}
	}

	/** Reads SSE frames as [id or "", event, data] until {@code n} frames of {@code event} have arrived. */
	private static java.util.List<String[]> readUntil(BufferedReader in, String event, int n) throws Exception {
		java.util.List<String[]> frames = new java.util.ArrayList<>();
		int seen = 0;
		String id = "";
		String name = null;
		String line;
		while (seen < n && (line = in.readLine()) != null) {
			if (line.startsWith("id: ")) {
				id = line.substring(4);
			} else if (line.startsWith("event: ")) {
				name = line.substring(7);
			} else if (line.startsWith("data: ")) {
				frames.add(new String[] { id, name, line.substring(6) });
				if (event.equals(name)) {
					seen++;
				}
				id = "";
				name = null;
			}
		}
		return frames;
	}

	private HttpResponse<InputStream> openEvents(String query, String lastEventId) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/events" + query));
		if (lastEventId != null) {
			b.header("Last-Event-ID", lastEventId);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
	}

	private void orderStatus(String status) {
		callbacks.orderStatus(10, status, Decimal.ZERO, Decimal.get(BigDecimal.valueOf(100)), 0, 1L, 0, 0, 11, null, 0);
	}

	@Test
	void reconnectingClientGetsMissedOrderEventsThenLiveOnesWithoutDuplicates() throws Exception {
		start(true, true);
		state.nextOrderId.set(10);
		assertEquals(202, send("POST", "/v1/orders", order(7001L, false)).statusCode());
		orderStatus("PreSubmitted");
		orderStatus("Submitted");
		orderStatus("Filled");
		java.util.List<EventLog.Entry> kept = hub.eventLog().after(0);
		assertEquals(3, kept.size());
		long firstId = kept.get(0).id();

		HttpResponse<InputStream> r = openEvents("", String.valueOf(firstId));
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
			java.util.List<String[]> replayed = readUntil(in, "order-status", 2);
			assertEquals("connection", replayed.get(0)[1], "current status first");
			assertEquals(String.valueOf(kept.get(1).id()), replayed.get(1)[0]);
			assertTrue(replayed.get(1)[2].contains("\"status\":\"Submitted\""));
			assertTrue(replayed.get(2)[2].contains("\"status\":\"Filled\""));
			waitFor(() -> hub.clientCount() == 1);
			orderStatus("Cancelled");
			java.util.List<String[]> live = readUntil(in, "order-status", 1);
			assertEquals("replay-complete", live.get(0)[1], "the replay ends before live events");
			assertTrue(live.get(0)[2].contains("\"replayed\":2"));
			String[] last = live.get(live.size() - 1);
			assertTrue(last[2].contains("Cancelled"));
			assertTrue(Long.parseLong(last[0]) > kept.get(2).id(), "ids keep increasing; nothing repeated at the seam");
		}
	}

	@Test
	void resumeByQueryParameterAndTruncationNotice() throws Exception {
		start(false, true);
		state.ordersByIbId.put(10, new BridgeState.OrderRef(7001L, "4", Model.Instrument.stock("SPY", "SMART", "USD")));
		orderStatus("Submitted");
		long id = hub.eventLog().after(0).get(0).id();
		HttpResponse<InputStream> r = openEvents("?lastEventId=" + (id - 1), null);
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
			java.util.List<String[]> frames = readUntil(in, "order-status", 1);
			assertEquals(String.valueOf(id), frames.get(frames.size() - 1)[0]);
		}
		HttpResponse<InputStream> old = openEvents("", "1");
		try (BufferedReader in = new BufferedReader(new InputStreamReader(old.body(), StandardCharsets.UTF_8))) {
			java.util.List<String[]> frames = readUntil(in, "order-status", 1);
			assertEquals("replay-truncated", frames.get(1)[1], "an id from before this process can't be fully replayed");
			assertTrue(frames.get(1)[2].contains("\"after\":1"));
		}
		assertEquals(400, openEvents("", "abc").statusCode());
	}

	private static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!cond.getAsBoolean() && System.nanoTime() < until) {
			Thread.sleep(10);
		}
		assertTrue(cond.getAsBoolean());
	}
}
