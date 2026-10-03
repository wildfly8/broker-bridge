package io.github.wildfly8.brokerbridge;

import java.net.InetAddress;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * broker-bridge: a separate GPLv3 program that talks to IB Gateway/TWS with IB's official client and offers a
 * broker-neutral HTTP + SSE API on localhost.
 */
public final class BridgeMain {

	private static final Logger log = LoggerFactory.getLogger(BridgeMain.class);

	private BridgeMain() {}

	public static void main(String[] args) throws Exception {
		BridgeConfig config;
		try {
			config = BridgeConfig.fromEnv(System.getenv());
		} catch (IllegalArgumentException e) {
			System.err.println("Invalid configuration: " + e.getMessage());
			System.exit(2);
			return;
		}
		if (!InetAddress.getByName(config.bind()).isLoopbackAddress()) {
			log.warn("BRIDGE_BIND={} is not a loopback address: the bridge API has no authentication; "
					+ "make sure nothing outside this host can reach it", config.bind());
		}
		BridgeState state = new BridgeState();
		EventHub hub = new EventHub();
		IbCallbacks callbacks = new IbCallbacks(state, hub, config.ordersEnabled());
		IbClient client = new SocketIbClient(callbacks);
		BrokerService broker = new BrokerService(client, state, config.ordersEnabled());
		IbConnection connection = new IbConnection(config, client, state, hub, broker, Duration.ofSeconds(5),
				Duration.ofSeconds(120));
		callbacks.listener(connection);
		BridgeHttpServer server = new BridgeHttpServer(config.bind(), config.port(), broker, hub, 15_000);

		log.info("broker-bridge starting: IB {}:{} client {}, orders {}, market data type {}", config.ibHost(),
				config.ibPort(), config.ibClientId(), config.ordersEnabled() ? "ENABLED" : "disabled",
				config.marketDataType());
		server.start();
		connection.start();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("broker-bridge stopping");
			connection.stop();
			server.stop();
		}, "shutdown"));
		Thread.currentThread().join();
	}
}
