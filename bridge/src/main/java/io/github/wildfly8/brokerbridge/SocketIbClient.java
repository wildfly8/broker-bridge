package io.github.wildfly8.brokerbridge;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EWrapper;
import com.ib.client.Order;
import com.ib.client.OrderCancel;

/** {@link IbClient} on IB's official client; decoded messages are dispatched on a virtual thread. */
final class SocketIbClient implements IbClient {

	private static final Logger log = LoggerFactory.getLogger(SocketIbClient.class);

	private final EJavaSignal signal = new EJavaSignal();
	private final EClientSocket socket;

	SocketIbClient(EWrapper wrapper) {
		this.socket = new EClientSocket(wrapper, signal);
	}

	@Override
	public boolean connect(String host, int port, int clientId) {
		socket.eConnect(host, port, clientId);
		if (!socket.isConnected()) {
			return false;
		}
		EReader reader = new EReader(socket, signal);
		reader.start();
		Thread.ofVirtual().name("ib-dispatch").start(() -> {
			while (socket.isConnected()) {
				signal.waitForSignal();
				try {
					reader.processMsgs();
				} catch (Exception e) {
					log.warn("IB message processing failed: {}", Json.maskAccounts(String.valueOf(e)));
				}
			}
			log.info("IB dispatch loop ended");
		});
		return true;
	}

	@Override
	public void disconnect() {
		socket.eDisconnect();
		signal.issueSignal();
	}

	@Override
	public boolean isConnected() {
		return socket.isConnected();
	}

	@Override
	public int serverVersion() {
		return socket.serverVersion();
	}

	@Override
	public void reqMarketDataType(int type) {
		socket.reqMarketDataType(type);
	}

	@Override
	public void reqMktData(int reqId, Contract contract, String genericTicks, boolean snapshot) {
		socket.reqMktData(reqId, contract, genericTicks, snapshot, false, List.of());
	}

	@Override
	public void cancelMktData(int reqId) {
		socket.cancelMktData(reqId);
	}

	@Override
	public void reqHistoricalData(int reqId, Contract contract, String end, String duration, String barSize,
			String what, boolean regularHoursOnly) {
		socket.reqHistoricalData(reqId, contract, end, duration, barSize, what, regularHoursOnly ? 1 : 0, 1, false,
				List.of());
	}

	@Override
	public void cancelHistoricalData(int reqId) {
		socket.cancelHistoricalData(reqId);
	}

	@Override
	public void reqContractDetails(int reqId, Contract contract) {
		socket.reqContractDetails(reqId, contract);
	}

	@Override
	public void placeOrder(int ibOrderId, Contract contract, Order order) {
		socket.placeOrder(ibOrderId, contract, order);
	}

	@Override
	public void cancelOrder(int ibOrderId) {
		socket.cancelOrder(ibOrderId, new OrderCancel());
	}

	@Override
	public void reqIds() {
		socket.reqIds(-1);
	}
}
