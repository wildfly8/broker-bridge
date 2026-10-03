package io.github.wildfly8.brokerbridge;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fans events out to SSE clients. Every event gets an id from the {@link EventLog}; order events are also kept
 * there for replay. A client that falls too far behind is dropped (it reconnects and replays).
 */
final class EventHub {

	private static final Logger log = LoggerFactory.getLogger(EventHub.class);

	static final String HEARTBEAT = ": heartbeat\n\n";

	/** One SSE frame; {@code id} is 0 for frames without an id line. */
	record Frame(long id, String text) {}

	static final class Client {
		private final BlockingQueue<Frame> frames;
		private volatile boolean dropped;

		Client(int capacity) {
			this.frames = new ArrayBlockingQueue<>(capacity);
		}

		boolean dropped() {
			return dropped;
		}
	}

	private final Set<Client> clients = ConcurrentHashMap.newKeySet();
	private final EventLog eventLog;
	private final int capacity;

	EventHub(EventLog eventLog) {
		this(eventLog, 10_000);
	}

	EventHub(EventLog eventLog, int capacity) {
		this.eventLog = eventLog;
		this.capacity = capacity;
	}

	EventLog eventLog() {
		return eventLog;
	}

	Client register() {
		Client c = new Client(capacity);
		clients.add(c);
		return c;
	}

	void unregister(Client c) {
		clients.remove(c);
	}

	int clientCount() {
		return clients.size();
	}

	/** Live-only event (quotes, connection, snapshot-end, non-order errors). */
	void publish(String event, Object data) {
		publish(event, data, false);
	}

	/** Order event (order-status, fill, order error): also kept for replay and journaled. */
	void publishOrderEvent(String event, Object data) {
		publish(event, data, true);
	}

	/** Synchronized so frames reach every client in id order. */
	private synchronized void publish(String event, Object data, boolean keep) {
		long id = eventLog.nextId();
		if (keep) {
			eventLog.keepOrderEvent(id, event, data);
		}
		Frame frame = new Frame(id, frame(id, event, Json.write(data)));
		for (Client c : clients) {
			if (!c.frames.offer(frame)) {
				c.dropped = true;
				clients.remove(c);
				log.warn("Dropped a slow event-stream client ({} events queued); it can reconnect and replay", capacity);
			}
		}
	}

	/** Next frame for the client, a heartbeat after {@code timeoutMs} of silence, or null once dropped. */
	Frame next(Client c, long timeoutMs) throws InterruptedException {
		if (c.dropped) {
			return null;
		}
		Frame f = c.frames.poll(timeoutMs, TimeUnit.MILLISECONDS);
		return f != null ? f : c.dropped ? null : new Frame(0, HEARTBEAT);
	}

	static String frame(long id, String event, String json) {
		return (id > 0 ? "id: " + id + "\n" : "") + "event: " + event + "\ndata: " + json + "\n\n";
	}
}
