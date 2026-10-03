package io.github.wildfly8.brokerbridge;

import com.ib.client.Contract;
import com.ib.client.Order;

/** The IB client calls the bridge makes; a thin seam over {@code EClientSocket} so tests can fake it. */
interface IbClient {

	/** Opens the socket and completes the handshake; returns whether the session is up. */
	boolean connect(String host, int port, int clientId);

	void disconnect();

	boolean isConnected();

	int serverVersion();

	void reqMarketDataType(int type);

	void reqMktData(int reqId, Contract contract, String genericTicks, boolean snapshot);

	void cancelMktData(int reqId);

	void reqHistoricalData(int reqId, Contract contract, String end, String duration, String barSize, String what,
			boolean regularHoursOnly);

	void cancelHistoricalData(int reqId);

	void reqContractDetails(int reqId, Contract contract);

	void placeOrder(int ibOrderId, Contract contract, Order order);

	void cancelOrder(int ibOrderId);

	void reqIds();

	/** Today's executions for this client id; they arrive as execDetails callbacks. */
	void reqExecutions(int reqId, int clientId);
}
