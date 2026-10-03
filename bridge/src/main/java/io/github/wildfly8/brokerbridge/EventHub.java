package io.github.wildfly8.brokerbridge;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fans events out to SSE clients. A client that falls too far behind is dropped (it reconnects). */
final class EventHub {

	private static final Logger log = LoggerFactory.getLogger(EventHub.class);

	static final String HEARTBEAT = ": heartbeat\n\n";

	static final class Client {
		private final BlockingQueue<String> frames;
		private volatile boolean dropped;

		Client(int capacity) {
			this.frames = new ArrayBlockingQueue<>(capacity);
		}

		boolean dropped() {
			return dropped;
		}
	}

	private final Set<Client> clients = ConcurrentHashMap.newKeySet();
	private final int capacity;

	EventHub() {
		this(10_000);
	}

	EventHub(int capacity) {
		this.capacity = capacity;
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

	void publish(String event, Object data) {
		String frame = frame(event, data);
		for (Client c : clients) {
			if (!c.frames.offer(frame)) {
				c.dropped = true;
				clients.remove(c);
				log.warn("Dropped a slow event-stream client ({} events queued)", capacity);
			}
		}
	}

	/** Sends one event to one client only (e.g. the current status on connect). */
	void send(Client c, String event, Object data) {
		if (!c.frames.offer(frame(event, data))) {
			c.dropped = true;
		}
	}

	/** Next frame for the client, a heartbeat after {@code timeoutMs} of silence, or null once dropped. */
	String next(Client c, long timeoutMs) throws InterruptedException {
		if (c.dropped) {
			return null;
		}
		String f = c.frames.poll(timeoutMs, TimeUnit.MILLISECONDS);
		return f != null ? f : c.dropped ? null : HEARTBEAT;
	}

	static String frame(String event, Object data) {
		return "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
	}
}
