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
						log.info("Retrying IB connection in {} s", backoff.toSeconds());
						Thread.sleep(backoff);
						backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
						continue;
					}
				}
				synchronized (wake) {
					if (running && client.isConnected()) {
						wake.wait(30_000);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private boolean connect() {
		log.info("Connecting to IB at {}:{} as client {}", config.ibHost(), config.ibPort(), config.ibClientId());
		try {
			if (!client.connect(config.ibHost(), config.ibPort(), config.ibClientId())) {
				return false;
			}
		} catch (RuntimeException e) {
			log.warn("IB connect failed: {}", e.toString());
			return false;
		}
		client.reqMarketDataType(config.marketDataType());
		client.reqIds();
		state.connected(true, client.serverVersion());
		state.lastError(null);
		log.info("Connected to IB, server version {}", client.serverVersion());
		broker.resubscribe();
		hub.publish("connection", broker.status());
		return true;
	}

	@Override
	public void closed() {
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
