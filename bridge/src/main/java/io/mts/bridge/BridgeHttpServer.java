package io.mts.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JacksonException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.mts.bridge.Model.HistoryRequest;
import io.mts.bridge.Model.OrderAccepted;
import io.mts.bridge.Model.OrderRequest;
import io.mts.bridge.Model.ResolveRequest;
import io.mts.bridge.Model.SnapshotRequest;
import io.mts.bridge.Model.SubscriptionId;
import io.mts.bridge.Model.SubscriptionRequest;

/** Bridge API v1 over the JDK HTTP server; every request runs on its own virtual thread. */
final class BridgeHttpServer {

	private static final Logger log = LoggerFactory.getLogger(BridgeHttpServer.class);

	/** Whether the most recent request ran on a virtual thread (checked by tests). */
	static volatile boolean lastHandlerVirtual;

	private final BrokerService broker;
	private final EventHub hub;
	private final long heartbeatMs;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final HttpServer server;

	interface Handler {
		void handle(HttpExchange ex) throws IOException;
	}

	BridgeHttpServer(String bind, int port, BrokerService broker, EventHub hub, long heartbeatMs) throws IOException {
		this.broker = broker;
		this.hub = hub;
		this.heartbeatMs = heartbeatMs;
		this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
		server.setExecutor(executor);
		route("/v1/status", this::status);
		route("/v1/subscriptions", this::subscriptions);
		route("/v1/snapshots", this::snapshots);
		route("/v1/history", this::history);
		route("/v1/contracts/resolve", this::resolve);
		route("/v1/orders", this::orders);
		route("/v1/events", this::events);
	}

	void start() {
		server.start();
		log.info("Bridge API listening on {}", server.getAddress());
	}

	void stop() {
		server.stop(0);
		executor.shutdownNow();
	}

	int port() {
		return server.getAddress().getPort();
	}

	private void route(String path, Handler h) {
		server.createContext(path, ex -> {
			lastHandlerVirtual = Thread.currentThread().isVirtual();
			try {
				h.handle(ex);
			} catch (ApiException e) {
				reply(ex, e.status(), e.retryable() ? Map.of("error", e.getMessage(), "retryable", true)
						: Map.of("error", e.getMessage()));
			} catch (JacksonException e) {
				reply(ex, 400, Map.of("error", "malformed request: " + e.getOriginalMessage()));
			} catch (NumberFormatException e) {
				reply(ex, 400, Map.of("error", "malformed id: " + e.getMessage()));
			} catch (IOException e) {
				log.debug("Client went away: {}", e.toString());
			} catch (RuntimeException e) {
				log.error("Request {} {} failed", ex.getRequestMethod(), ex.getRequestURI(), e);
				reply(ex, 500, Map.of("error", "internal error"));
			} finally {
				ex.close();
			}
		});
	}

	private void status(HttpExchange ex) throws IOException {
		expect(ex, "GET", "/v1/status");
		reply(ex, 200, broker.status());
	}

	private void subscriptions(HttpExchange ex) throws IOException {
		String id = tail(ex, "/v1/subscriptions");
		if (id == null) {
			expect(ex, "POST", "/v1/subscriptions");
			int subscriptionId = broker.subscribe(read(ex, SubscriptionRequest.class));
			reply(ex, 200, new SubscriptionId(subscriptionId));
		} else {
			expect(ex, "DELETE", ex.getRequestURI().getPath());
			broker.unsubscribe(Integer.parseInt(id));
			reply(ex, 204, null);
		}
	}

	private void snapshots(HttpExchange ex) throws IOException {
		expect(ex, "POST", "/v1/snapshots");
		reply(ex, 200, broker.snapshot(read(ex, SnapshotRequest.class)));
	}

	private void history(HttpExchange ex) throws IOException {
		expect(ex, "POST", "/v1/history");
		reply(ex, 200, broker.history(read(ex, HistoryRequest.class)));
	}

	private void resolve(HttpExchange ex) throws IOException {
		expect(ex, "POST", "/v1/contracts/resolve");
		reply(ex, 200, broker.resolve(read(ex, ResolveRequest.class).instrument()));
	}

	private void orders(HttpExchange ex) throws IOException {
		String id = tail(ex, "/v1/orders");
		if (id == null) {
			expect(ex, "POST", "/v1/orders");
			reply(ex, 202, new OrderAccepted(broker.placeOrder(read(ex, OrderRequest.class))));
		} else {
			expect(ex, "DELETE", ex.getRequestURI().getPath());
			long clientOrderId = Long.parseLong(id);
			broker.cancelOrder(clientOrderId);
			reply(ex, 202, new OrderAccepted(clientOrderId));
		}
	}

	private void events(HttpExchange ex) throws IOException {
		expect(ex, "GET", "/v1/events");
		ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.sendResponseHeaders(200, 0);
		EventHub.Client client = hub.register();
		hub.send(client, "connection", broker.status());
		log.info("Event-stream client connected ({} total)", hub.clientCount());
		try (OutputStream out = ex.getResponseBody()) {
			String frame;
			while ((frame = hub.next(client, heartbeatMs)) != null) {
				out.write(frame.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			hub.unregister(client);
			log.info("Event-stream client disconnected ({} left)", hub.clientCount());
		}
	}

	/** The path segment after {@code base}, or null when the path is exactly {@code base}. */
	private static String tail(HttpExchange ex, String base) {
		String path = ex.getRequestURI().getPath();
		if (path.equals(base) || path.equals(base + "/")) {
			return null;
		}
		String rest = path.substring(base.length() + 1);
		if (rest.isEmpty() || rest.contains("/")) {
			throw new ApiException(404, "not found: " + path);
		}
		return rest;
	}

	private static void expect(HttpExchange ex, String method, String path) {
		if (!ex.getRequestURI().getPath().equals(path)) {
			throw new ApiException(404, "not found: " + ex.getRequestURI().getPath());
		}
		if (!ex.getRequestMethod().equals(method)) {
			ex.getResponseHeaders().set("Allow", method);
			throw new ApiException(405, "method not allowed");
		}
	}

	private static <T> T read(HttpExchange ex, Class<T> type) throws IOException {
		T value = Json.MAPPER.readValue(ex.getRequestBody(), type);
		if (value == null) {
			throw new ApiException(400, "request body is required");
		}
		return value;
	}

	private static void reply(HttpExchange ex, int status, Object body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}
}
