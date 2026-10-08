package io.github.wildfly8.brokerbridge;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps one IB session up: connects, reconnects with backoff, resubscribes. */
final class IbConnection implements IbCallbacks.SessionListener {

	private static final Logger log = LoggerFactory.getLogger(IbConnection.class);

	private final BridgeConfig config;
	private final IbClient client;
	private final BridgeState state;
	private final EventHub hub;
	private final BrokerService broker;
	private final Duration minBackoff;
	private final Duration maxBackoff;
	private final Object wake = new Object();
	private volatile boolean running;
	private volatile Thread loop;
	/** Set when the Gateway closes the socket, including a 502 during the handshake. */
	private volatile boolean dropped;

	IbConnection(BridgeConfig config, IbClient client, BridgeState state, EventHub hub, BrokerService broker,
			Duration minBackoff, Duration maxBackoff) {
		this.config = config;
		this.client = client;
		this.state = state;
		this.hub = hub;
		this.broker = broker;
		this.minBackoff = minBackoff;
		this.maxBackoff = maxBackoff;
	}

	void start() {
		running = true;
		loop = Thread.ofVirtual().name("ib-connection").start(this::run);
	}

	void stop() {
		running = false;
		synchronized (wake) {
			wake.notifyAll();
		}
		if (loop != null) {
			loop.interrupt();
		}
		if (client.isConnected()) {
			client.disconnect();
		}
	}

	private void run() {
		Duration backoff = minBackoff;
		while (running) {
			try {
				if (!client.isConnected()) {
					if (connect()) {
						backoff = minBackoff;
					} else {
						backoff = pause(backoff);
						continue;
					}
				}
				synchronized (wake) {
					if (running && client.isConnected()) {
						wake.wait(30_000);
					}
				}
				// A handshake the Gateway accepts and then drops (error 502 during its daily restart)
				// used to loop straight back into connect(). That opened a new API client every few
				// dozen milliseconds, filled the Gateway's client table, and left the session down.
				if (running && !client.isConnected()) {
					backoff = pause(backoff);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/** Sleeps, then returns the next backoff (doubled, capped at {@link #maxBackoff}). */
	private Duration pause(Duration backoff) throws InterruptedException {
		log.info("Retrying IB connection in {} s", backoff.toSeconds());
		Thread.sleep(backoff);
		Duration next = backoff.multipliedBy(2);
		return next.compareTo(maxBackoff) > 0 ? maxBackoff : next;
	}

	private boolean connect() {
		dropped = false;
		log.info("Connecting to IB at {}:{} as client {}", config.ibHost(), config.ibPort(), config.ibClientId());
		try {
			if (!client.connect(config.ibHost(), config.ibPort(), config.ibClientId())) {
				return false;
			}
		} catch (RuntimeException e) {
			log.warn("IB connect failed: {}", e.toString());
			return false;
		}
		if (dropped || !client.isConnected()) {
			return dropHandshake();
		}
		client.reqMarketDataType(config.marketDataType());
		client.reqIds();
		// fills made while the bridge was disconnected; already-delivered ones are dropped by fill id
		client.reqExecutions(state.newRequestId(), config.ibClientId());
		if (dropped || !client.isConnected()) {
			return dropHandshake();
		}
		state.connected(true, client.serverVersion());
		state.lastError(null);
		log.info("Connected to IB, server version {}", client.serverVersion());
		broker.resubscribe();
		hub.publish("connection", broker.status());
		return true;
	}

	/** The Gateway closed the socket during the handshake. Release the client id and do not mark the session up. */
	private boolean dropHandshake() {
		log.warn("IB dropped the handshake; waiting before another attempt");
		try {
			client.disconnect();
		} catch (RuntimeException e) {
			log.warn("IB disconnect after a dropped handshake: {}", e.toString());
		}
		state.connected(false, null);
		return false;
	}

	@Override
	public void closed() {
		dropped = true;
		synchronized (wake) {
			wake.notifyAll();
		}
	}

	@Override
	public void restored(boolean dataLost) {
		if (dataLost) {
			Thread.ofVirtual().name("ib-resubscribe").start(broker::resubscribe);
		}
	}
}
